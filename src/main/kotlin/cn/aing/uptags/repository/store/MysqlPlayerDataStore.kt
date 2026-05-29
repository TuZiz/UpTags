package cn.aing.uptags.repository.store

import cn.aing.uptags.config.OrderRetentionSettings
import cn.aing.uptags.model.config.CurrencyType
import cn.aing.uptags.model.runtime.CustomTitleOrderStatus
import cn.aing.uptags.model.runtime.CustomTitlePurchaseOrderData
import cn.aing.uptags.model.runtime.PlayerTagData
import cn.aing.uptags.model.runtime.PurchaseOrderData
import cn.aing.uptags.model.runtime.PurchaseOrderStatus
import cn.aing.uptags.repository.PlayerDataSnapshot
import cn.aing.uptags.repository.SaveResult
import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import java.sql.Connection
import java.sql.PreparedStatement
import java.sql.ResultSet
import java.sql.SQLIntegrityConstraintViolationException
import java.util.Base64
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
    private val orderRetention: OrderRetentionSettings = OrderRetentionSettings(),
    private val purchaseOrderTable: String = "uptags_purchase_orders",
    private val customTitleOrderTable: String = "uptags_custom_title_orders",
) : PlayerDataStore {
    private var dataSource: HikariDataSource? = null

    override fun initialize() {
        validateTableName(table)
        validateTableName(purchaseOrderTable)
        validateTableName(customTitleOrderTable)
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
                createTables(connection)
                migrateLegacyOrders(connection)
                cleanupOrders(connection)
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
                    val data = PlayerDataCodec.deserialize(uniqueId, result.getString("data_json"))
                    val orders = loadOrders(connection, uniqueId, orderRetention.maxPerPlayer)
                    data.purchaseOrders.putAll(orders.purchaseOrders.mapValues { it.value.copyDeep() })
                    data.customTitleOrders.putAll(orders.customTitleOrders.mapValues { it.value.copyDeep() })
                    return PlayerDataSnapshot(
                        data = data,
                        version = result.getLong("version"),
                        updatedAt = result.getLong("updated_at"),
                    )
                }
            }
        }
    }

    override fun loadOrders(uniqueId: UUID): PlayerOrdersSnapshot {
        connection().use { connection ->
            return loadOrders(connection, uniqueId, orderRetention.maxPerPlayer)
        }
    }

    override fun save(snapshot: PlayerDataSnapshot, expectedVersion: Long?): SaveResult {
        return saveInternal(
            snapshot = snapshot,
            expectedVersion = expectedVersion,
            serializedMainData = PlayerDataCodec.serialize(snapshot.data, includeOrders = false),
            mainDataChanged = null,
        )
    }

    override fun save(
        snapshot: PlayerDataSnapshot,
        expectedVersion: Long?,
        serializedMainData: String,
        mainDataChanged: Boolean,
    ): SaveResult {
        return saveInternal(snapshot, expectedVersion, serializedMainData, mainDataChanged)
    }

    private fun saveInternal(
        snapshot: PlayerDataSnapshot,
        expectedVersion: Long?,
        serializedMainData: String,
        mainDataChanged: Boolean?,
    ): SaveResult {
        var connection: Connection? = null
        return try {
            connection = connection()
            connection.autoCommit = false
            val mainSave = saveMainSnapshot(connection, snapshot, expectedVersion, serializedMainData, mainDataChanged)
            if (mainSave == null) {
                connection.rollback()
                return SaveResult.Conflict(load(snapshot.data.uniqueId))
            }
            upsertPurchaseOrders(connection, snapshot.data.uniqueId, snapshot.data.purchaseOrders.values)
            upsertCustomTitleOrders(connection, snapshot.data.uniqueId, snapshot.data.customTitleOrders.values)
            cleanupPlayerOrders(connection, snapshot.data.uniqueId)
            connection.commit()
            SaveResult.Success(mainSave.version, mainSave.updatedAt)
        } catch (ex: SQLIntegrityConstraintViolationException) {
            runCatching { connection?.rollback() }
            SaveResult.Conflict(load(snapshot.data.uniqueId))
        } catch (ex: Exception) {
            runCatching { connection?.rollback() }
            SaveResult.Failure("MySQL save failed: ${ex.message}", ex)
        } finally {
            runCatching {
                connection?.autoCommit = true
                connection?.close()
            }
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

    private fun createTables(connection: Connection) {
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
            statement.execute(
                """
                CREATE TABLE IF NOT EXISTS $purchaseOrderTable (
                    order_id VARCHAR(64) PRIMARY KEY,
                    uuid VARCHAR(36) NOT NULL,
                    status VARCHAR(32) NOT NULL,
                    currency_type VARCHAR(32) NOT NULL,
                    amount DOUBLE NOT NULL,
                    target_id VARCHAR(128) NOT NULL,
                    product_id VARCHAR(128) NOT NULL,
                    failure_reason TEXT NULL,
                    created_at BIGINT NOT NULL,
                    updated_at BIGINT NOT NULL,
                    submitted_items TEXT NULL,
                    compensated_items TEXT NULL
                )
                """.trimIndent(),
            )
            statement.execute(
                """
                CREATE TABLE IF NOT EXISTS $customTitleOrderTable (
                    order_id VARCHAR(64) PRIMARY KEY,
                    uuid VARCHAR(36) NOT NULL,
                    status VARCHAR(32) NOT NULL,
                    currency_type VARCHAR(32) NOT NULL,
                    amount DOUBLE NOT NULL,
                    title_id VARCHAR(128) NOT NULL,
                    raw_text TEXT NOT NULL,
                    preset_id VARCHAR(128) NOT NULL,
                    group_id VARCHAR(128) NULL,
                    failure_reason TEXT NULL,
                    created_at BIGINT NOT NULL,
                    updated_at BIGINT NOT NULL,
                    submitted_items TEXT NULL,
                    compensated_items TEXT NULL,
                    previous_equipped_tag_id VARCHAR(128) NULL,
                    previous_equipped_custom_title_id VARCHAR(128) NULL
                )
                """.trimIndent(),
            )
            createIndex(statement, "idx_${purchaseOrderTable}_uuid_updated", purchaseOrderTable, "uuid, updated_at")
            createIndex(statement, "idx_${purchaseOrderTable}_status_updated", purchaseOrderTable, "status, updated_at")
            createIndex(statement, "idx_${customTitleOrderTable}_uuid_updated", customTitleOrderTable, "uuid, updated_at")
            createIndex(statement, "idx_${customTitleOrderTable}_status_updated", customTitleOrderTable, "status, updated_at")
        }
    }

    private fun createIndex(statement: java.sql.Statement, index: String, tableName: String, columns: String) {
        runCatching {
            statement.execute("CREATE INDEX $index ON $tableName ($columns)")
        }
    }

    private fun saveMainSnapshot(
        connection: Connection,
        snapshot: PlayerDataSnapshot,
        expectedVersion: Long?,
        serializedMainData: String,
        mainDataChanged: Boolean?,
    ): MainSnapshotSave? {
        val uniqueId = snapshot.data.uniqueId
        if (expectedVersion == null) {
            val existing = if (mainDataChanged != true) loadMainRow(connection, uniqueId) else null
            if (existing != null && (mainDataChanged == false || existing.dataJson == serializedMainData)) {
                return MainSnapshotSave(existing.version, existing.updatedAt)
            }
            connection.prepareStatement(
                "INSERT INTO $table (uuid, data_json, version, updated_at) VALUES (?, ?, ?, ?) " +
                    "ON DUPLICATE KEY UPDATE data_json = VALUES(data_json), version = VALUES(version), updated_at = VALUES(updated_at)",
            ).use { statement ->
                bindSnapshot(statement, snapshot, serializedMainData)
                statement.executeUpdate()
            }
            return MainSnapshotSave(snapshot.version, snapshot.updatedAt)
        }
        if (expectedVersion == 0L) {
            connection.prepareStatement(
                "INSERT INTO $table (uuid, data_json, version, updated_at) VALUES (?, ?, ?, ?)",
            ).use { statement ->
                bindSnapshot(statement, snapshot, serializedMainData)
                statement.executeUpdate()
            }
            return MainSnapshotSave(snapshot.version, snapshot.updatedAt)
        }
        if (mainDataChanged != true) {
            val existing = loadMainRow(connection, uniqueId) ?: return null
            if (existing.version != expectedVersion) {
                return null
            }
            if (mainDataChanged == false || existing.dataJson == serializedMainData) {
                return MainSnapshotSave(existing.version, existing.updatedAt)
            }
        }
        connection.prepareStatement(
            "UPDATE $table SET data_json = ?, version = ?, updated_at = ? WHERE uuid = ? AND version = ?",
        ).use { statement ->
            statement.setString(1, serializedMainData)
            statement.setLong(2, snapshot.version)
            statement.setLong(3, snapshot.updatedAt)
            statement.setString(4, snapshot.data.uniqueId.toString())
            statement.setLong(5, expectedVersion)
            return if (statement.executeUpdate() > 0) {
                MainSnapshotSave(snapshot.version, snapshot.updatedAt)
            } else {
                null
            }
        }
    }

    private fun bindSnapshot(statement: PreparedStatement, snapshot: PlayerDataSnapshot, serializedMainData: String) {
        statement.setString(1, snapshot.data.uniqueId.toString())
        statement.setString(2, serializedMainData)
        statement.setLong(3, snapshot.version)
        statement.setLong(4, snapshot.updatedAt)
    }

    private fun loadMainRow(connection: Connection, uniqueId: UUID): MainRow? {
        connection.prepareStatement("SELECT data_json, version, updated_at FROM $table WHERE uuid = ?").use { statement ->
            statement.setString(1, uniqueId.toString())
            statement.executeQuery().use { result ->
                if (!result.next()) {
                    return null
                }
                return MainRow(
                    dataJson = result.getString("data_json"),
                    version = result.getLong("version"),
                    updatedAt = result.getLong("updated_at"),
                )
            }
        }
    }

    private fun upsertPurchaseOrders(connection: Connection, uniqueId: UUID, orders: Collection<PurchaseOrderData>) {
        if (orders.isEmpty()) return
        connection.prepareStatement(
            """
            INSERT INTO $purchaseOrderTable
                (order_id, uuid, status, currency_type, amount, target_id, product_id, failure_reason, created_at, updated_at, submitted_items, compensated_items)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            ON DUPLICATE KEY UPDATE
                uuid = VALUES(uuid),
                status = VALUES(status),
                currency_type = VALUES(currency_type),
                amount = VALUES(amount),
                target_id = VALUES(target_id),
                product_id = VALUES(product_id),
                failure_reason = VALUES(failure_reason),
                created_at = VALUES(created_at),
                updated_at = VALUES(updated_at),
                submitted_items = VALUES(submitted_items),
                compensated_items = VALUES(compensated_items)
            """.trimIndent(),
        ).use { statement ->
            orders.forEach { order ->
                statement.setString(1, order.orderId)
                statement.setString(2, uniqueId.toString())
                statement.setString(3, order.status.name)
                statement.setString(4, order.currencyType.name)
                statement.setDouble(5, order.currencyAmount)
                statement.setString(6, order.targetId)
                statement.setString(7, order.productId)
                statement.setString(8, order.failureReason)
                statement.setLong(9, order.createdAt)
                statement.setLong(10, order.updatedAt)
                statement.setString(11, encodeStringList(order.submittedItems))
                statement.setString(12, encodeStringList(order.compensatedItems))
                statement.addBatch()
            }
            statement.executeBatch()
        }
    }

    private fun upsertCustomTitleOrders(connection: Connection, uniqueId: UUID, orders: Collection<CustomTitlePurchaseOrderData>) {
        if (orders.isEmpty()) return
        connection.prepareStatement(
            """
            INSERT INTO $customTitleOrderTable
                (order_id, uuid, status, currency_type, amount, title_id, raw_text, preset_id, group_id, failure_reason, created_at, updated_at, submitted_items, compensated_items, previous_equipped_tag_id, previous_equipped_custom_title_id)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            ON DUPLICATE KEY UPDATE
                uuid = VALUES(uuid),
                status = VALUES(status),
                currency_type = VALUES(currency_type),
                amount = VALUES(amount),
                title_id = VALUES(title_id),
                raw_text = VALUES(raw_text),
                preset_id = VALUES(preset_id),
                group_id = VALUES(group_id),
                failure_reason = VALUES(failure_reason),
                created_at = VALUES(created_at),
                updated_at = VALUES(updated_at),
                submitted_items = VALUES(submitted_items),
                compensated_items = VALUES(compensated_items),
                previous_equipped_tag_id = VALUES(previous_equipped_tag_id),
                previous_equipped_custom_title_id = VALUES(previous_equipped_custom_title_id)
            """.trimIndent(),
        ).use { statement ->
            orders.forEach { order ->
                statement.setString(1, order.orderId)
                statement.setString(2, uniqueId.toString())
                statement.setString(3, order.status.name)
                statement.setString(4, order.currencyType.name)
                statement.setDouble(5, order.currencyAmount)
                statement.setString(6, order.titleId)
                statement.setString(7, order.rawText)
                statement.setString(8, order.presetId)
                statement.setString(9, order.groupId)
                statement.setString(10, order.failureReason)
                statement.setLong(11, order.createdAt)
                statement.setLong(12, order.updatedAt)
                statement.setString(13, "")
                statement.setString(14, "")
                statement.setString(15, order.previousEquippedTagId)
                statement.setString(16, order.previousEquippedCustomTitleId)
                statement.addBatch()
            }
            statement.executeBatch()
        }
    }

    private fun loadOrders(connection: Connection, uniqueId: UUID, limit: Int): PlayerOrdersSnapshot {
        return PlayerOrdersSnapshot(
            purchaseOrders = loadPurchaseOrders(connection, uniqueId, limit),
            customTitleOrders = loadCustomTitleOrders(connection, uniqueId, limit),
        )
    }

    private fun loadPurchaseOrders(connection: Connection, uniqueId: UUID, limit: Int): Map<String, PurchaseOrderData> {
        val recoverable = OrderStatusPolicies.recoverablePurchaseStatuses.joinToString(",") { "'${it.name}'" }
        val orders = LinkedHashMap<String, PurchaseOrderData>()
        connection.prepareStatement(
            """
            SELECT * FROM $purchaseOrderTable
            WHERE uuid = ?
            ORDER BY CASE WHEN status IN ($recoverable) THEN 0 ELSE 1 END, updated_at DESC
            LIMIT ?
            """.trimIndent(),
        ).use { statement ->
            statement.setString(1, uniqueId.toString())
            statement.setInt(2, limit.coerceAtLeast(1))
            statement.executeQuery().use { result ->
                while (result.next()) {
                    val order = result.toPurchaseOrder()
                    orders[order.orderId] = order
                }
            }
        }
        return orders
    }

    private fun loadCustomTitleOrders(connection: Connection, uniqueId: UUID, limit: Int): Map<String, CustomTitlePurchaseOrderData> {
        val recoverable = OrderStatusPolicies.recoverableCustomTitleStatuses.joinToString(",") { "'${it.name}'" }
        val orders = LinkedHashMap<String, CustomTitlePurchaseOrderData>()
        connection.prepareStatement(
            """
            SELECT * FROM $customTitleOrderTable
            WHERE uuid = ?
            ORDER BY CASE WHEN status IN ($recoverable) THEN 0 ELSE 1 END, updated_at DESC
            LIMIT ?
            """.trimIndent(),
        ).use { statement ->
            statement.setString(1, uniqueId.toString())
            statement.setInt(2, limit.coerceAtLeast(1))
            statement.executeQuery().use { result ->
                while (result.next()) {
                    val order = result.toCustomTitleOrder()
                    orders[order.orderId] = order
                }
            }
        }
        return orders
    }

    private fun migrateLegacyOrders(connection: Connection) {
        val migrated = ArrayList<Pair<UUID, PlayerTagData>>()
        connection.prepareStatement("SELECT uuid, data_json FROM $table").use { statement ->
            statement.executeQuery().use { result ->
                while (result.next()) {
                    val uniqueId = runCatching { UUID.fromString(result.getString("uuid")) }.getOrNull() ?: continue
                    val data = PlayerDataCodec.deserialize(uniqueId, result.getString("data_json"))
                    if (data.purchaseOrders.isNotEmpty() || data.customTitleOrders.isNotEmpty()) {
                        migrated += uniqueId to data
                    }
                }
            }
        }
        if (migrated.isEmpty()) {
            return
        }
        val previousAutoCommit = connection.autoCommit
        connection.autoCommit = false
        try {
            migrated.forEach { (uniqueId, data) ->
                upsertPurchaseOrders(connection, uniqueId, data.purchaseOrders.values)
                upsertCustomTitleOrders(connection, uniqueId, data.customTitleOrders.values)
                data.purchaseOrders.clear()
                data.customTitleOrders.clear()
                connection.prepareStatement("UPDATE $table SET data_json = ? WHERE uuid = ?").use { statement ->
                    statement.setString(1, PlayerDataCodec.serialize(data, includeOrders = false))
                    statement.setString(2, uniqueId.toString())
                    statement.executeUpdate()
                }
            }
            connection.commit()
        } catch (ex: Exception) {
            connection.rollback()
            throw ex
        } finally {
            connection.autoCommit = previousAutoCommit
        }
    }

    private fun cleanupOrders(connection: Connection) {
        deleteTerminalOlderThan(connection, purchaseOrderTable, PurchaseOrderStatus.GRANTED.name, orderRetention.completedDays)
        deleteTerminalOlderThan(connection, purchaseOrderTable, PurchaseOrderStatus.FAILED.name, orderRetention.failedDays)
        deleteTerminalOlderThan(connection, purchaseOrderTable, PurchaseOrderStatus.REFUNDED.name, orderRetention.refundedDays)
        deleteTerminalOlderThan(connection, customTitleOrderTable, CustomTitleOrderStatus.COMPLETED.name, orderRetention.completedDays)
        deleteTerminalOlderThan(connection, customTitleOrderTable, CustomTitleOrderStatus.FAILED.name, orderRetention.failedDays)
        deleteTerminalOlderThan(connection, customTitleOrderTable, CustomTitleOrderStatus.REFUNDED.name, orderRetention.refundedDays)
        trimTerminalOrders(connection, purchaseOrderTable, OrderStatusPolicies.recoverablePurchaseStatuses.map { it.name }.toSet())
        trimTerminalOrders(connection, customTitleOrderTable, OrderStatusPolicies.recoverableCustomTitleStatuses.map { it.name }.toSet())
    }

    private fun cleanupPlayerOrders(connection: Connection, uniqueId: UUID) {
        deleteTerminalOlderThan(connection, purchaseOrderTable, PurchaseOrderStatus.GRANTED.name, orderRetention.completedDays, uniqueId)
        deleteTerminalOlderThan(connection, purchaseOrderTable, PurchaseOrderStatus.FAILED.name, orderRetention.failedDays, uniqueId)
        deleteTerminalOlderThan(connection, purchaseOrderTable, PurchaseOrderStatus.REFUNDED.name, orderRetention.refundedDays, uniqueId)
        deleteTerminalOlderThan(connection, customTitleOrderTable, CustomTitleOrderStatus.COMPLETED.name, orderRetention.completedDays, uniqueId)
        deleteTerminalOlderThan(connection, customTitleOrderTable, CustomTitleOrderStatus.FAILED.name, orderRetention.failedDays, uniqueId)
        deleteTerminalOlderThan(connection, customTitleOrderTable, CustomTitleOrderStatus.REFUNDED.name, orderRetention.refundedDays, uniqueId)
        trimTerminalOrders(connection, purchaseOrderTable, OrderStatusPolicies.recoverablePurchaseStatuses.map { it.name }.toSet(), uniqueId)
        trimTerminalOrders(connection, customTitleOrderTable, OrderStatusPolicies.recoverableCustomTitleStatuses.map { it.name }.toSet(), uniqueId)
    }

    private fun deleteTerminalOlderThan(
        connection: Connection,
        tableName: String,
        status: String,
        days: Int,
        uniqueId: UUID? = null,
    ) {
        val cutoff = System.currentTimeMillis() - days.coerceAtLeast(0) * 86_400_000L
        val sql = buildString {
            append("DELETE FROM $tableName WHERE status = ? AND updated_at < ?")
            if (uniqueId != null) append(" AND uuid = ?")
        }
        connection.prepareStatement(sql).use { statement ->
            statement.setString(1, status)
            statement.setLong(2, cutoff)
            if (uniqueId != null) {
                statement.setString(3, uniqueId.toString())
            }
            statement.executeUpdate()
        }
    }

    private fun trimTerminalOrders(
        connection: Connection,
        tableName: String,
        recoverableStatuses: Set<String>,
        uniqueId: UUID? = null,
    ) {
        val recoverable = recoverableStatuses.joinToString(",") { "'$it'" }
        val sql = buildString {
            append("SELECT uuid, order_id FROM $tableName WHERE status NOT IN ($recoverable)")
            if (uniqueId != null) append(" AND uuid = ?")
            append(" ORDER BY uuid ASC, updated_at DESC")
        }
        val toDelete = ArrayList<String>()
        val counts = LinkedHashMap<String, Int>()
        connection.prepareStatement(sql).use { statement ->
            if (uniqueId != null) {
                statement.setString(1, uniqueId.toString())
            }
            statement.executeQuery().use { result ->
                while (result.next()) {
                    val uuid = result.getString("uuid")
                    val count = counts.getOrDefault(uuid, 0) + 1
                    counts[uuid] = count
                    if (count > orderRetention.maxPerPlayer) {
                        toDelete += result.getString("order_id")
                    }
                }
            }
        }
        deleteOrders(connection, tableName, toDelete)
    }

    private fun deleteOrders(connection: Connection, tableName: String, orderIds: Collection<String>) {
        if (orderIds.isEmpty()) return
        connection.prepareStatement("DELETE FROM $tableName WHERE order_id = ?").use { statement ->
            orderIds.forEach { orderId ->
                statement.setString(1, orderId)
                statement.addBatch()
            }
            statement.executeBatch()
        }
    }

    private fun ResultSet.toPurchaseOrder(): PurchaseOrderData {
        return PurchaseOrderData(
            orderId = getString("order_id"),
            productId = getString("product_id"),
            targetId = getString("target_id"),
            status = PurchaseOrderStatus.from(getString("status")),
            currencyType = CurrencyType.from(getString("currency_type")),
            currencyAmount = getDouble("amount"),
            submittedItems = decodeStringList(getString("submitted_items")).toMutableList(),
            compensatedItems = decodeStringList(getString("compensated_items")).toMutableList(),
            createdAt = getLong("created_at"),
            updatedAt = getLong("updated_at"),
            failureReason = getString("failure_reason"),
        )
    }

    private fun ResultSet.toCustomTitleOrder(): CustomTitlePurchaseOrderData {
        return CustomTitlePurchaseOrderData(
            orderId = getString("order_id"),
            titleId = getString("title_id"),
            rawText = getString("raw_text"),
            presetId = getString("preset_id"),
            groupId = getString("group_id"),
            currencyType = CurrencyType.from(getString("currency_type")),
            currencyAmount = getDouble("amount"),
            status = CustomTitleOrderStatus.from(getString("status")),
            createdAt = getLong("created_at"),
            updatedAt = getLong("updated_at"),
            failureReason = getString("failure_reason"),
            previousEquippedTagId = getString("previous_equipped_tag_id"),
            previousEquippedCustomTitleId = getString("previous_equipped_custom_title_id"),
        )
    }

    private fun encodeStringList(values: List<String>): String {
        return values.joinToString("\n") { Base64.getEncoder().encodeToString(it.toByteArray(Charsets.UTF_8)) }
    }

    private fun decodeStringList(raw: String?): List<String> {
        if (raw.isNullOrBlank()) {
            return emptyList()
        }
        return raw.lineSequence()
            .filter { it.isNotBlank() }
            .map { encoded ->
                runCatching { String(Base64.getDecoder().decode(encoded), Charsets.UTF_8) }.getOrElse { encoded }
            }
            .toList()
    }

    private fun ensureDriverLoaded() {
        if (!jdbcUrl.startsWith("jdbc:h2:", ignoreCase = true)) {
            Class.forName("com.mysql.cj.jdbc.Driver")
        }
    }

    private fun validateTableName(tableName: String) {
        require(tableName.matches(tableNamePattern)) {
            "Invalid MySQL table name '$tableName'. Only letters, numbers, and underscores are allowed."
        }
    }

    private fun connection(): Connection {
        val source = dataSource ?: throw IllegalStateException("MySQL connection pool is not initialized.")
        return source.connection
    }

    private data class MainSnapshotSave(
        val version: Long,
        val updatedAt: Long,
    )

    private data class MainRow(
        val dataJson: String,
        val version: Long,
        val updatedAt: Long,
    )

    companion object {
        private val tableNamePattern = Regex("^[A-Za-z0-9_]+$")
    }
}
