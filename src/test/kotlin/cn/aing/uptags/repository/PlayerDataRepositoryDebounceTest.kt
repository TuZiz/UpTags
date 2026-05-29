package cn.aing.uptags.repository

import cn.aing.uptags.compat.PlatformScheduler
import cn.aing.uptags.compat.TaskHandle
import cn.aing.uptags.model.config.CurrencyType
import cn.aing.uptags.model.runtime.PlayerTagData
import cn.aing.uptags.model.runtime.PurchaseOrderData
import cn.aing.uptags.model.runtime.PurchaseOrderStatus
import cn.aing.uptags.repository.store.PlayerDataStore
import cn.aing.uptags.service.sync.PlayerSyncMessage
import cn.aing.uptags.service.sync.RedisSyncService
import io.mockk.every
import io.mockk.mockk
import java.util.UUID
import java.util.logging.Logger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import org.bukkit.plugin.java.JavaPlugin

class PlayerDataRepositoryDebounceTest {
    @Test
    fun normalSavesAreDebouncedToLatestSnapshot() {
        val playerId = UUID.randomUUID()
        val store = RecordingStore()
        val repository = repository(store, normalSaveDebounceMillis = 50L, maxSaveDelayMillis = 1_000L)
        repository.preparePlayerAsync(playerId).join()

        repeat(100) { index ->
            val data = repository.get(playerId)
            data.titleCoinBalance = index.toDouble()
            repository.saveAsync(data)
        }

        assertEquals(0, store.saved.size)
        assertTrue(waitUntil { store.saved.size == 1 })
        assertEquals(99.0, store.saved.single().data.titleCoinBalance)
        repository.shutdown()
    }

    @Test
    fun strictSaveIsNotDelayedByNormalDebounce() {
        val playerId = UUID.randomUUID()
        val store = RecordingStore()
        val results = mutableListOf<SaveResult>()
        val repository = repository(store, normalSaveDebounceMillis = 10_000L, maxSaveDelayMillis = 10_000L)
        repository.preparePlayerAsync(playerId).join()

        val data = repository.get(playerId)
        data.ownedTags += "strict"
        repository.saveAsyncStrict(data) { results += it }

        assertTrue(waitUntil { store.saved.size == 1 && results.size == 1 })
        assertEquals(1, store.saved.size)
        assertEquals(setOf("strict"), store.saved.single().data.ownedTags.toSet())
        assertIs<SaveResult.Success>(results.single())
        repository.shutdown()
    }

    @Test
    fun flushAndStopSavesPendingNormalData() {
        val playerId = UUID.randomUUID()
        val store = RecordingStore()
        val repository = repository(store, normalSaveDebounceMillis = 10_000L, maxSaveDelayMillis = 10_000L)
        repository.preparePlayerAsync(playerId).join()

        val data = repository.get(playerId)
        data.ownedTags += "pending"
        repository.saveAsync(data)

        assertEquals(0, store.saved.size)
        repository.flushAndStop(timeoutSeconds = 1L)

        assertEquals(1, store.saved.size)
        assertEquals(setOf("pending"), store.saved.single().data.ownedTags.toSet())
    }

    @Test
    fun flushPlayerAsyncSavesPendingNormalDataImmediately() {
        val playerId = UUID.randomUUID()
        val store = RecordingStore()
        val repository = repository(store, normalSaveDebounceMillis = 10_000L, maxSaveDelayMillis = 10_000L)
        repository.preparePlayerAsync(playerId).join()

        val data = repository.get(playerId)
        data.ownedTags += "flush-player"
        repository.saveAsync(data)

        assertEquals(0, store.saved.size)
        repository.flushPlayerAsync(playerId)

        assertTrue(waitUntil { store.saved.size == 1 })
        assertEquals(setOf("flush-player"), store.saved.single().data.ownedTags.toSet())
        repository.shutdown()
    }

    @Test
    fun sameMainJsonDoesNotPublishRedisInvalidationAgain() {
        val playerId = UUID.randomUUID()
        val store = RecordingStore()
        val redis = RecordingRedisSync()
        val repository = repository(store, normalSaveDebounceMillis = 0L, maxSaveDelayMillis = 0L)
        repository.attachSync(redis, "test")
        repository.preparePlayerAsync(playerId).join()

        val data = repository.get(playerId)
        data.ownedTags += "vip"
        repository.saveAsync(data)
        assertTrue(waitUntil { store.saved.size == 1 })

        repository.saveAsync(repository.get(playerId))
        assertTrue(waitUntil { store.saved.size == 2 })

        assertEquals(listOf(true, false), store.mainDataChanged)
        assertEquals(listOf(false, false), store.ordersChanged)
        assertEquals(1, redis.messages.size)
        repository.shutdown()
    }

    @Test
    fun orderOnlyChangePublishesInvalidationWithoutMainJsonChange() {
        val playerId = UUID.randomUUID()
        val store = RecordingStore()
        val redis = RecordingRedisSync()
        val repository = repository(store, normalSaveDebounceMillis = 0L, maxSaveDelayMillis = 0L)
        repository.attachSync(redis, "test")
        repository.preparePlayerAsync(playerId).join()

        val data = repository.get(playerId)
        data.ownedTags += "vip"
        repository.saveAsync(data)
        assertTrue(waitUntil { store.saved.size == 1 })

        data.purchaseOrders["order-1"] = PurchaseOrderData(
            orderId = "order-1",
            productId = "vip",
            targetId = "vip",
            status = PurchaseOrderStatus.PENDING,
            currencyType = CurrencyType.POINTS,
            currencyAmount = 5.0,
        )
        repository.saveAsyncStrict(data) {}
        assertTrue(waitUntil { store.saved.size == 2 && redis.messages.size == 2 })

        assertEquals(listOf(true, false), store.mainDataChanged)
        assertEquals(listOf(false, true), store.ordersChanged)
        assertEquals(2L, redis.messages.last().version)
        repository.shutdown()
    }

    private fun repository(
        store: RecordingStore,
        normalSaveDebounceMillis: Long,
        maxSaveDelayMillis: Long,
    ): PlayerDataRepository {
        val scheduler = mockk<PlatformScheduler>()
        val plugin = mockk<JavaPlugin>(relaxed = true)
        every { plugin.name } returns "UpTagsRepositoryTest"
        every { plugin.logger } returns Logger.getLogger("UpTagsRepositoryTest")
        every { scheduler.runAsync(any()) } answers {
            Thread(firstArg<() -> Unit>()).apply {
                isDaemon = true
                start()
            }
            mockk<TaskHandle>(relaxed = true)
        }
        return PlayerDataRepository(
            plugin = plugin,
            scheduler = scheduler,
            store = store,
            normalSaveDebounceMillis = normalSaveDebounceMillis,
            maxSaveDelayMillis = maxSaveDelayMillis,
        )
    }

    private fun waitUntil(timeoutMillis: Long = 2_000L, condition: () -> Boolean): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMillis
        while (System.currentTimeMillis() < deadline) {
            if (condition()) {
                return true
            }
            Thread.sleep(10L)
        }
        return condition()
    }

    private class RecordingStore : PlayerDataStore {
        val saved = mutableListOf<PlayerDataSnapshot>()
        val mainDataChanged = mutableListOf<Boolean>()
        val ordersChanged = mutableListOf<Boolean>()
        private val versions = mutableMapOf<UUID, Long>()

        override fun load(uniqueId: UUID): PlayerDataSnapshot? = null

        override fun save(snapshot: PlayerDataSnapshot, expectedVersion: Long?): SaveResult {
            return record(snapshot, mainChanged = true, ordersChanged = true)
        }

        override fun save(
            snapshot: PlayerDataSnapshot,
            expectedVersion: Long?,
            serializedMainData: String,
            mainDataChanged: Boolean,
        ): SaveResult {
            return record(snapshot, mainChanged = mainDataChanged, ordersChanged = true)
        }

        override fun save(
            snapshot: PlayerDataSnapshot,
            expectedVersion: Long?,
            serializedMainData: String,
            mainDataChanged: Boolean,
            ordersChanged: Boolean,
        ): SaveResult {
            return record(snapshot, mainChanged = mainDataChanged, ordersChanged = ordersChanged)
        }

        private fun record(snapshot: PlayerDataSnapshot, mainChanged: Boolean, ordersChanged: Boolean): SaveResult {
            saved += snapshot.copy(data = snapshot.data.copyDeep())
            mainDataChanged += mainChanged
            this.ordersChanged += ordersChanged
            val current = versions.getOrDefault(snapshot.data.uniqueId, 0L)
            val version = if (mainChanged || ordersChanged) current + 1L else current
            versions[snapshot.data.uniqueId] = version
            return SaveResult.Success(version, snapshot.updatedAt)
        }
    }

    private class RecordingRedisSync : RedisSyncService {
        val messages = mutableListOf<PlayerSyncMessage>()

        override fun publish(message: PlayerSyncMessage) {
            messages += message
        }
    }
}
