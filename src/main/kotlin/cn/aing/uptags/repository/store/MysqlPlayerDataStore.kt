package cn.aing.uptags.repository.store

import cn.aing.uptags.repository.PlayerDataSnapshot
import cn.aing.uptags.repository.SaveResult
import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import java.sql.Connection
import java.sql.SQLIntegrityConstraintViolationException
import java.sql.PreparedStatement
import java.util.LinkedHashMap
import java.util.UUID

data class MysqlImportSummary(
    val imported: Int,
    val skipped: Int,
    val failed: Int,
)

class MysqlPlayerDataStore(
    private val jdbcUrl: String,
    private val username: String,
    private val password: String,
    private val table: String,
) : PlayerDataStore {
    private var dataSource: HikariDataSource? = null

    override fun initialize() {
        validateTableName()
        if (dataSource != null) {
            return
        }
        ensureDriverLoaded()
        try {
            dataSource = HikariDataSource(
                HikariConfig().apply {
                    this.jdbcUrl = this@MysqlPlayerDataStore.jdbcUrl
                    this.username = this@MysqlPlayerDataStore.username
                    this.password = this@MysqlPlayerDataStore.password
                    poolName = "UpTags-MySQL"
                    maximumPoolSize = 10
                    minimumIdle = 1
                    connectionTimeout = 10_000
                    validationTimeout = 5_000
                    idleTimeout = 60_000
                    maxLifetime = 1_800_000
                },
            )
            connection().use { connection ->
                connection.createStatement().use { statement ->
                    statement.execute(
                        """
                        CREATE TABLE IF NOT EXISTS $table (
                            uuid VARCHAR(36) PRIMARY KEY,
                            data_json LONGTEXT NOT NULL,
                            version BIGINT NOT NULL,
                            updated_at BIGINT NOT NULL
                        )
                        """.trimIndent(),
                    )
                }
            }
        } catch (ex: Exception) {
            dataSource?.close()
            dataSource = null
            throw IllegalStateException(
                "MySQL initialization failed. Check storage.mysql.jdbc-url / username / password / table. Cause: ${ex.message}",
                ex,
            )
        }
    }

    override fun load(uniqueId: UUID): PlayerDataSnapshot? {
        connection().use { connection ->
            connection.prepareStatement("SELECT data_json, version, updated_at FROM $table WHERE uuid = ?").use { statement ->
                statement.setString(1, uniqueId.toString())
                statement.executeQuery().use { result ->
                    if (!result.next()) return null
                    return PlayerDataSnapshot(
                        data = PlayerDataCodec.deserialize(uniqueId, result.getString("data_json")),
                        version = result.getLong("version"),
                        updatedAt = result.getLong("updated_at"),
                    )
                }
            }
        }
    }

    override fun save(snapshot: PlayerDataSnapshot, expectedVersion: Long?): SaveResult {
        return try {
            connection().use { connection ->
                if (expectedVersion == null) {
                    connection.prepareStatement(
                        "INSERT INTO $table (uuid, data_json, version, updated_at) VALUES (?, ?, ?, ?) " +
                            "ON DUPLICATE KEY UPDATE data_json = VALUES(data_json), version = VALUES(version), updated_at = VALUES(updated_at)",
                    ).use { statement ->
                        bindSnapshot(statement, snapshot)
                        statement.executeUpdate()
                    }
                } else if (expectedVersion == 0L) {
                    connection.prepareStatement(
                        "INSERT INTO $table (uuid, data_json, version, updated_at) VALUES (?, ?, ?, ?)",
                    ).use { statement ->
                        bindSnapshot(statement, snapshot)
                        statement.executeUpdate()
                    }
                } else {
                    connection.prepareStatement(
                        "UPDATE $table SET data_json = ?, version = ?, updated_at = ? WHERE uuid = ? AND version = ?",
                    ).use { statement ->
                        statement.setString(1, PlayerDataCodec.serialize(snapshot.data))
                        statement.setLong(2, snapshot.version)
                        statement.setLong(3, snapshot.updatedAt)
                        statement.setString(4, snapshot.data.uniqueId.toString())
                        statement.setLong(5, expectedVersion)
                        val updated = statement.executeUpdate()
                        if (updated == 0) {
                            return SaveResult.Conflict(load(snapshot.data.uniqueId))
                        }
                    }
                }
            }
            SaveResult.Success(snapshot.version, snapshot.updatedAt)
        } catch (ex: SQLIntegrityConstraintViolationException) {
            SaveResult.Conflict(load(snapshot.data.uniqueId))
        } catch (ex: Exception) {
            SaveResult.Failure("MySQL save failed: ${ex.message}", ex)
        }
    }

    override fun loadVersions(uniqueIds: Collection<UUID>): Map<UUID, Long> {
        if (uniqueIds.isEmpty()) return emptyMap()
        val placeholders = uniqueIds.joinToString(",") { "?" }
        val versions = LinkedHashMap<UUID, Long>()
        connection().use { connection ->
            connection.prepareStatement("SELECT uuid, version FROM $table WHERE uuid IN ($placeholders)").use { statement ->
                uniqueIds.forEachIndexed { index, uuid -> statement.setString(index + 1, uuid.toString()) }
                statement.executeQuery().use { result ->
                    while (result.next()) {
                        versions[UUID.fromString(result.getString("uuid"))] = result.getLong("version")
                    }
                }
            }
        }
        return versions
    }

    fun importSnapshots(snapshots: Collection<PlayerDataSnapshot>): MysqlImportSummary {
        if (snapshots.isEmpty()) {
            return MysqlImportSummary(imported = 0, skipped = 0, failed = 0)
        }
        val existingVersions = LinkedHashMap<UUID, Long>()
        snapshots.map { it.data.uniqueId }.chunked(500).forEach { chunk ->
            existingVersions.putAll(loadVersions(chunk))
        }
        var imported = 0
        var skipped = 0
        var failed = 0
        snapshots.forEach { snapshot ->
            val existingVersion = existingVersions[snapshot.data.uniqueId]
            if (existingVersion != null && existingVersion >= snapshot.version) {
                skipped++
                return@forEach
            }
            when (save(snapshot, existingVersion)) {
                is SaveResult.Success -> imported++
                is SaveResult.Conflict -> skipped++
                is SaveResult.Failure -> failed++
            }
        }
        return MysqlImportSummary(imported, skipped, failed)
    }

    override fun shutdown() {
        dataSource?.close()
        dataSource = null
    }

    private fun bindSnapshot(statement: PreparedStatement, snapshot: PlayerDataSnapshot) {
        statement.setString(1, snapshot.data.uniqueId.toString())
        statement.setString(2, PlayerDataCodec.serialize(snapshot.data))
        statement.setLong(3, snapshot.version)
        statement.setLong(4, snapshot.updatedAt)
    }

    private fun ensureDriverLoaded() {
        Class.forName("com.mysql.cj.jdbc.Driver")
    }

    private fun validateTableName() {
        require(table.matches(tableNamePattern)) {
            "Invalid storage.mysql.table '$table'. Only letters, numbers, and underscores are allowed."
        }
    }

    private fun connection(): Connection {
        val source = dataSource ?: throw IllegalStateException("MySQL connection pool is not initialized.")
        return source.connection
    }

    companion object {
        private val tableNamePattern = Regex("^[A-Za-z0-9_]+$")
    }
}
