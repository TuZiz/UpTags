package cn.aing.uptags.repository.store

import cn.aing.uptags.model.runtime.PlayerTagData
import cn.aing.uptags.model.runtime.TagProgress
import cn.aing.uptags.repository.PlayerDataSnapshot
import cn.aing.uptags.repository.SaveResult
import java.sql.Connection
import java.sql.DriverManager
import java.util.LinkedHashMap
import java.util.UUID

class PostgresPlayerDataStore(
    private val jdbcUrl: String,
    private val username: String,
    private val password: String,
    private val table: String,
) : PlayerDataStore {
    override fun initialize() {
        ensureDriverLoaded()
        try {
            connection().use { connection ->
                connection.createStatement().use { statement ->
                    statement.execute(
                        """
                        CREATE TABLE IF NOT EXISTS $table (
                            uuid VARCHAR(36) PRIMARY KEY,
                            data_json TEXT NOT NULL,
                            version BIGINT NOT NULL,
                            updated_at BIGINT NOT NULL
                        )
                        """.trimIndent(),
                    )
                }
            }
        } catch (ex: Exception) {
            throw IllegalStateException(
                "PostgreSQL 初始化失败，请检查 storage.pg.jdbc-url / username / password / table 配置。原始错误: ${ex.message}",
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
                        data = deserialize(uniqueId, result.getString("data_json")),
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
                if (expectedVersion == null || expectedVersion == 0L) {
                    connection.prepareStatement(
                        "INSERT INTO $table (uuid, data_json, version, updated_at) VALUES (?, ?, ?, ?) ON CONFLICT (uuid) DO UPDATE SET data_json = EXCLUDED.data_json, version = EXCLUDED.version, updated_at = EXCLUDED.updated_at",
                    ).use { statement ->
                        bindSnapshot(statement, snapshot)
                        statement.executeUpdate()
                    }
                } else {
                    connection.prepareStatement(
                        "UPDATE $table SET data_json = ?, version = ?, updated_at = ? WHERE uuid = ? AND version = ?",
                    ).use { statement ->
                        statement.setString(1, serialize(snapshot.data))
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
        } catch (ex: Exception) {
            SaveResult.Failure("PostgreSQL 保存失败: ${ex.message}", ex)
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

    private fun bindSnapshot(statement: java.sql.PreparedStatement, snapshot: PlayerDataSnapshot) {
        statement.setString(1, snapshot.data.uniqueId.toString())
        statement.setString(2, serialize(snapshot.data))
        statement.setLong(3, snapshot.version)
        statement.setLong(4, snapshot.updatedAt)
    }

    private fun serialize(data: PlayerTagData): String {
        val tagParts = data.tagProgress.entries.joinToString(";;") { (tagId, progress) ->
            listOf(
                tagId,
                progress.selectedParticleId ?: "",
                progress.ownedParticles.joinToString(","),
                progress.activeBuffs.joinToString(","),
                progress.buffLevels.entries.joinToString(",") { "${it.key}:${it.value}" },
            ).joinToString("|")
        }
        return listOf(
            data.ownedTags.joinToString(","),
            data.equippedTagId ?: "",
            tagParts,
        ).joinToString("###")
    }

    private fun deserialize(uniqueId: UUID, raw: String): PlayerTagData {
        val data = PlayerTagData(uniqueId)
        val parts = raw.split("###")
        if (parts.isNotEmpty() && parts[0].isNotBlank()) {
            data.ownedTags += parts[0].split(',').filter { it.isNotBlank() }
        }
        data.equippedTagId = parts.getOrNull(1)?.ifBlank { null }
        parts.getOrNull(2)
            ?.takeIf { it.isNotBlank() }
            ?.split(";;")
            ?.forEach { entry ->
                val entryParts = entry.split('|')
                val tagId = entryParts.getOrNull(0)?.takeIf { it.isNotBlank() } ?: return@forEach
                val progress = TagProgress()
                progress.selectedParticleId = entryParts.getOrNull(1)?.ifBlank { null }
                entryParts.getOrNull(2)?.takeIf { it.isNotBlank() }?.split(',')?.filter { it.isNotBlank() }?.let { progress.ownedParticles += it }
                entryParts.getOrNull(3)?.takeIf { it.isNotBlank() }?.split(',')?.filter { it.isNotBlank() }?.let { progress.activeBuffs += it }
                entryParts.getOrNull(4)?.takeIf { it.isNotBlank() }?.split(',')?.forEach { pair ->
                    val pieces = pair.split(':')
                    val buffId = pieces.getOrNull(0)?.takeIf { it.isNotBlank() } ?: return@forEach
                    val level = pieces.getOrNull(1)?.toIntOrNull() ?: 0
                    progress.buffLevels[buffId] = level
                }
                data.tagProgress[tagId] = progress
            }
        return data
    }

    private fun ensureDriverLoaded() {
        Class.forName("org.postgresql.Driver")
    }

    private fun connection(): Connection {
        ensureDriverLoaded()
        return try {
            DriverManager.getConnection(jdbcUrl, username, password)
        } catch (ex: Exception) {
            throw IllegalStateException(
                "无法连接 PostgreSQL: $jdbcUrl，请检查数据库地址、用户名和密码。原始错误: ${ex.message}",
                ex,
            )
        }
    }
}
