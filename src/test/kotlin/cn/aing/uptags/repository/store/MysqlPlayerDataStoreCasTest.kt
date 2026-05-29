package cn.aing.uptags.repository.store

import cn.aing.uptags.model.runtime.PlayerTagData
import cn.aing.uptags.model.config.CurrencyType
import cn.aing.uptags.model.runtime.CustomTitleOrderStatus
import cn.aing.uptags.model.runtime.CustomTitlePurchaseOrderData
import cn.aing.uptags.model.runtime.PurchaseOrderData
import cn.aing.uptags.model.runtime.PurchaseOrderStatus
import cn.aing.uptags.repository.PlayerDataSnapshot
import cn.aing.uptags.repository.SaveResult
import java.sql.DriverManager
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class MysqlPlayerDataStoreCasTest {
    @Test
    fun expectedVersionZeroConflictDoesNotOverwriteExistingRow() {
        val table = "uptags_test_${System.nanoTime()}"
        val store = MysqlPlayerDataStore(
            jdbcUrl = "jdbc:h2:mem:$table;MODE=MySQL;DATABASE_TO_UPPER=false",
            username = "sa",
            password = "",
            table = table,
        )
        store.initialize()
        try {
            val uniqueId = UUID.randomUUID()
            val firstData = PlayerTagData(uniqueId).apply { ownedTags += "first" }
            val secondData = PlayerTagData(uniqueId).apply { ownedTags += "second" }

            assertIs<SaveResult.Success>(
                store.save(PlayerDataSnapshot(firstData, version = 1L, updatedAt = 100L), expectedVersion = 0L),
            )
            val conflict = store.save(PlayerDataSnapshot(secondData, version = 1L, updatedAt = 200L), expectedVersion = 0L)

            assertIs<SaveResult.Conflict>(conflict)
            assertEquals(setOf("first"), store.load(uniqueId)?.data?.ownedTags?.toSet())
            assertEquals(100L, store.load(uniqueId)?.updatedAt)
        } finally {
            store.shutdown()
        }
    }

    @Test
    fun mysqlStoresOrdersOutsidePlayerDataJsonAndReloadsThem() {
        val table = "uptags_test_${System.nanoTime()}"
        val jdbcUrl = "jdbc:h2:mem:$table;MODE=MySQL;DATABASE_TO_UPPER=false"
        val store = MysqlPlayerDataStore(
            jdbcUrl = jdbcUrl,
            username = "sa",
            password = "",
            table = table,
        )
        store.initialize()
        try {
            val uniqueId = UUID.randomUUID()
            val data = PlayerTagData(uniqueId).apply {
                ownedTags += "vip"
                purchaseOrders["order-1"] = PurchaseOrderData(
                    orderId = "order-1",
                    productId = "vip",
                    targetId = "vip",
                    status = PurchaseOrderStatus.PENDING,
                    currencyType = CurrencyType.POINTS,
                    currencyAmount = 5.0,
                    submittedItems = mutableListOf("32xbread"),
                )
                customTitleOrders["custom-order-1"] = CustomTitlePurchaseOrderData(
                    orderId = "custom-order-1",
                    titleId = "custom-1",
                    rawText = "Hero",
                    presetId = "default",
                    groupId = "starter",
                    currencyType = CurrencyType.MONEY,
                    currencyAmount = 10.0,
                    status = CustomTitleOrderStatus.PENDING,
                )
            }

            assertIs<SaveResult.Success>(
                store.save(PlayerDataSnapshot(data, version = 1L, updatedAt = 100L), expectedVersion = 0L),
            )

            val loaded = store.load(uniqueId)?.data
            assertEquals(PurchaseOrderStatus.PENDING, loaded?.purchaseOrders?.get("order-1")?.status)
            assertEquals(listOf("32xbread"), loaded?.purchaseOrders?.get("order-1")?.submittedItems?.toList())
            assertEquals(CustomTitleOrderStatus.PENDING, loaded?.customTitleOrders?.get("custom-order-1")?.status)

            DriverManager.getConnection(jdbcUrl, "sa", "").use { connection ->
                connection.prepareStatement("SELECT data_json FROM $table WHERE uuid = ?").use { statement ->
                    statement.setString(1, uniqueId.toString())
                    statement.executeQuery().use { result ->
                        result.next()
                        val mainData = PlayerDataCodec.deserialize(uniqueId, result.getString("data_json"))
                        assertEquals(emptyMap(), mainData.purchaseOrders)
                        assertEquals(emptyMap(), mainData.customTitleOrders)
                    }
                }
            }
        } finally {
            store.shutdown()
        }
    }

    @Test
    fun sameMainJsonDoesNotAdvancePlayerDataRowVersion() {
        val table = "uptags_test_${System.nanoTime()}"
        val jdbcUrl = "jdbc:h2:mem:$table;MODE=MySQL;DATABASE_TO_UPPER=false"
        val store = MysqlPlayerDataStore(
            jdbcUrl = jdbcUrl,
            username = "sa",
            password = "",
            table = table,
        )
        store.initialize()
        try {
            val uniqueId = UUID.randomUUID()
            val data = PlayerTagData(uniqueId).apply { ownedTags += "vip" }

            assertIs<SaveResult.Success>(
                store.save(PlayerDataSnapshot(data, version = 1L, updatedAt = 100L), expectedVersion = 0L),
            )
            val second = store.save(
                PlayerDataSnapshot(data.copyDeep(), version = 2L, updatedAt = 200L),
                expectedVersion = 1L,
            )

            assertIs<SaveResult.Success>(second)
            assertEquals(1L, second.version)
            assertEquals(100L, second.updatedAt)

            DriverManager.getConnection(jdbcUrl, "sa", "").use { connection ->
                connection.prepareStatement("SELECT version, updated_at FROM $table WHERE uuid = ?").use { statement ->
                    statement.setString(1, uniqueId.toString())
                    statement.executeQuery().use { result ->
                        result.next()
                        assertEquals(1L, result.getLong("version"))
                        assertEquals(100L, result.getLong("updated_at"))
                    }
                }
            }
        } finally {
            store.shutdown()
        }
    }
}
