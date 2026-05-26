package cn.aing.uptags.repository

import cn.aing.uptags.compat.PlatformScheduler
import cn.aing.uptags.compat.TaskHandle
import cn.aing.uptags.model.config.CurrencyType
import cn.aing.uptags.model.runtime.CustomTitleOrderStatus
import cn.aing.uptags.model.runtime.CustomTitlePurchaseOrderData
import cn.aing.uptags.model.runtime.PlayerTagData
import cn.aing.uptags.repository.store.PlayerDataStore
import io.mockk.every
import io.mockk.mockk
import java.util.UUID
import java.util.logging.Logger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import org.bukkit.plugin.java.JavaPlugin

class PlayerDataRepositoryStrictSaveTest {
    @Test
    fun strictSaveCallbackBelongsToStrictSnapshotEvenWhenNormalSaveIsQueuedLater() {
        val playerId = UUID.randomUUID()
        val store = RecordingStore()
        val tasks = ArrayDeque<() -> Unit>()
        val scheduler = mockk<PlatformScheduler>()
        val plugin = mockk<JavaPlugin>(relaxed = true)
        val callbackResults = mutableListOf<SaveResult>()

        every { plugin.logger } returns Logger.getLogger("PlayerDataRepositoryStrictSaveTest")
        every { scheduler.runAsync(any()) } answers {
            tasks += firstArg<() -> Unit>()
            mockk<TaskHandle>(relaxed = true)
        }

        val repository = PlayerDataRepository(plugin, scheduler, store)
        repository.preparePlayerAsync(playerId)
        while (tasks.isNotEmpty()) {
            tasks.removeFirst().invoke()
        }
        val strictData = PlayerTagData(playerId).apply {
            customTitleOrders["order-1"] = order("order-1", CustomTitleOrderStatus.PENDING)
        }
        repository.saveAsyncStrict(strictData) { callbackResults += it }

        val normalData = PlayerTagData(playerId).apply {
            customTitleOrders["order-2"] = order("order-2", CustomTitleOrderStatus.COMPLETED)
        }
        repository.saveAsync(normalData)

        while (tasks.isNotEmpty()) {
            tasks.removeFirst().invoke()
        }

        assertEquals(2, store.saved.size)
        assertEquals(setOf("order-1"), store.saved[0].data.customTitleOrders.keys)
        assertEquals(setOf("order-2"), store.saved[1].data.customTitleOrders.keys)
        assertEquals(1, callbackResults.size)
        assertIs<SaveResult.Success>(callbackResults.single())
        assertTrue(store.saved[0].data !== strictData)
    }

    @Test
    fun saveAllSyncOnlyFlushesDirtyPlayers() {
        val firstId = UUID.randomUUID()
        val secondId = UUID.randomUUID()
        val store = RecordingStore()
        val tasks = ArrayDeque<() -> Unit>()
        val scheduler = mockk<PlatformScheduler>()
        val plugin = mockk<JavaPlugin>(relaxed = true)

        every { plugin.logger } returns Logger.getLogger("PlayerDataRepositoryStrictSaveTest")
        every { scheduler.runAsync(any()) } answers {
            tasks += firstArg<() -> Unit>()
            mockk<TaskHandle>(relaxed = true)
        }

        val repository = PlayerDataRepository(plugin, scheduler, store)
        repository.preparePlayerAsync(firstId)
        repository.preparePlayerAsync(secondId)
        while (tasks.isNotEmpty()) {
            tasks.removeFirst().invoke()
        }

        val first = repository.get(firstId)
        first.ownedTags += "dirty"
        repository.markDirty(first)

        repository.saveAllSync()

        assertEquals(1, store.saved.size)
        assertEquals(firstId, store.saved.single().data.uniqueId)
    }


    private fun order(orderId: String, status: CustomTitleOrderStatus): CustomTitlePurchaseOrderData {
        return CustomTitlePurchaseOrderData(
            orderId = orderId,
            titleId = "custom-$orderId",
            rawText = "桜咲く",
            presetId = "unicode",
            groupId = "starter",
            currencyType = CurrencyType.TITLE_COIN,
            currencyAmount = 1.0,
            status = status,
        )
    }

    private class RecordingStore : PlayerDataStore {
        val saved = mutableListOf<PlayerDataSnapshot>()
        private val versions = mutableMapOf<UUID, Long>()

        override fun load(uniqueId: UUID): PlayerDataSnapshot? = null

        override fun save(snapshot: PlayerDataSnapshot, expectedVersion: Long?): SaveResult {
            saved += snapshot.copy(data = snapshot.data.copyDeep())
            val version = versions.getOrDefault(snapshot.data.uniqueId, 0L) + 1L
            versions[snapshot.data.uniqueId] = version
            return SaveResult.Success(version, snapshot.updatedAt)
        }
    }
}
