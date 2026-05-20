package cn.aing.uptags.repository

import cn.aing.uptags.compat.PlatformScheduler
import cn.aing.uptags.model.runtime.PlayerTagData
import cn.aing.uptags.repository.store.PlayerDataStore
import cn.aing.uptags.service.sync.PlayerSyncMessage
import cn.aing.uptags.service.sync.RedisSyncService
import org.bukkit.plugin.java.JavaPlugin
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.TimeUnit

class PlayerDataRepository(
    private val plugin: JavaPlugin,
    private val scheduler: PlatformScheduler,
    private val store: PlayerDataStore,
) {
    private val cache = ConcurrentHashMap<UUID, PlayerCacheEntry>()
    private val dirty = ConcurrentHashMap.newKeySet<UUID>()
    private val pendingSaves = ConcurrentHashMap<UUID, PendingSave>()
    private val strictSaves = ConcurrentHashMap<UUID, ConcurrentLinkedQueue<PendingSave>>()
    private val savingNow = ConcurrentHashMap.newKeySet<UUID>()
    private val loading = ConcurrentHashMap<UUID, CompletableFuture<PlayerCacheEntry>>()
    private var redisSyncService: RedisSyncService? = null
    private var serverId: String = "local"

    init {
        store.initialize()
    }

    fun attachSync(redisSyncService: RedisSyncService, serverId: String) {
        this.redisSyncService = redisSyncService
        this.serverId = serverId
    }

    fun get(uniqueId: UUID): PlayerTagData = getCached(uniqueId) ?: createEmptyCached(uniqueId).data

    fun getCached(uniqueId: UUID): PlayerTagData? = cache[uniqueId]?.data

    fun entry(uniqueId: UUID): PlayerCacheEntry = cache[uniqueId] ?: createEmptyCached(uniqueId)

    fun version(uniqueId: UUID): Long = cache[uniqueId]?.version ?: 0L

    fun loadAsync(uniqueId: UUID): CompletableFuture<PlayerCacheEntry> {
        cache[uniqueId]?.takeUnless { it.stale }?.let { return CompletableFuture.completedFuture(it) }
        return loading.computeIfAbsent(uniqueId) {
            val future = CompletableFuture<PlayerCacheEntry>()
            scheduler.runAsync {
                try {
                    val entry = loadEntryFromStore(uniqueId)
                    cache[uniqueId] = entry
                    future.complete(entry)
                } catch (ex: Throwable) {
                    future.completeExceptionally(ex)
                } finally {
                    loading.remove(uniqueId)
                }
            }
            future
        }
    }

    fun preparePlayerAsync(uniqueId: UUID): CompletableFuture<PlayerTagData> {
        return loadAsync(uniqueId).thenApply { it.data }
    }

    fun markDirty(data: PlayerTagData) {
        dirty += data.uniqueId
    }

    fun markStale(uniqueId: UUID) {
        cache[uniqueId]?.stale = true
    }

    fun shouldAcceptRemoteVersion(uniqueId: UUID, remoteVersion: Long): Boolean {
        val localVersion = cache[uniqueId]?.version ?: 0L
        return remoteVersion > localVersion
    }

    fun replace(snapshot: PlayerDataSnapshot) {
        cache[snapshot.data.uniqueId] = PlayerCacheEntry(
            data = snapshot.data.copyDeep(),
            version = snapshot.version,
            stale = false,
            lastSyncAt = snapshot.updatedAt,
        )
        dirty.remove(snapshot.data.uniqueId)
    }

    fun refresh(uniqueId: UUID): Boolean {
        val entry = loadEntryFromStore(uniqueId)
        cache[uniqueId] = entry
        return true
    }

    fun refreshIfStale(uniqueId: UUID): Boolean {
        val entry = cache[uniqueId] ?: return refresh(uniqueId)
        if (!entry.stale) {
            return false
        }
        return refresh(uniqueId)
    }

    fun saveAsync(data: PlayerTagData) {
        saveAsync(data, retryOnFailure = true, callback = null)
    }

    fun saveAsync(data: PlayerTagData, callback: (SaveResult) -> Unit) {
        saveAsync(data, retryOnFailure = false, callback = callback)
    }

    private fun saveAsync(data: PlayerTagData, retryOnFailure: Boolean, callback: ((SaveResult) -> Unit)?) {
        markDirty(data)
        pendingSaves.compute(data.uniqueId) { _, existing ->
            val callbacks = existing?.callbacks ?: mutableListOf()
            if (callback != null) {
                callbacks += callback
            }
            PendingSave(
                data = data.copyDeep(),
                retryOnFailure = existing?.retryOnFailure == true || retryOnFailure,
                callbacks = callbacks,
            )
        }
        if (!savingNow.add(data.uniqueId)) {
            return
        }
        scheduleDrain(data.uniqueId)
    }

    fun saveAsyncStrict(data: PlayerTagData, callback: (SaveResult) -> Unit) {
        markDirty(data)
        strictSaves.computeIfAbsent(data.uniqueId) { ConcurrentLinkedQueue() }
            .add(
                PendingSave(
                    data = data.copyDeep(),
                    retryOnFailure = false,
                    callbacks = mutableListOf(callback),
                ),
            )
        if (!savingNow.add(data.uniqueId)) {
            return
        }
        scheduleDrain(data.uniqueId)
    }

    fun saveSync(data: PlayerTagData) {
        val currentEntry = entry(data.uniqueId)
        val expectedVersion = currentEntry.version
        val snapshot = PlayerDataSnapshot(
            data = data.copyDeep(),
            version = expectedVersion + 1,
            updatedAt = System.currentTimeMillis(),
        )
        when (val result = store.save(snapshot, expectedVersion)) {
            is SaveResult.Success -> {
                replace(snapshot.copy(version = result.version, updatedAt = result.updatedAt))
                publishInvalidation(data.uniqueId, result.version, result.updatedAt)
            }
            is SaveResult.Conflict -> result.latest?.let(::replace)
            is SaveResult.Failure -> plugin.logger.warning(result.message)
        }
        dirty.remove(data.uniqueId)
    }

    fun saveAllSync() {
        cache.values.forEach { entry -> saveSync(entry.data) }
    }

    fun invalidate(uniqueId: UUID) {
        cache.remove(uniqueId)
        dirty.remove(uniqueId)
        pendingSaves.remove(uniqueId)
        strictSaves.remove(uniqueId)
        savingNow.remove(uniqueId)
    }

    fun flushAndStop(timeoutSeconds: Long = 30L) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(timeoutSeconds.coerceAtLeast(1L))
        while ((pendingSaves.isNotEmpty() || strictSaves.isNotEmpty() || savingNow.isNotEmpty()) && System.nanoTime() < deadline) {
            Thread.sleep(25L)
        }
        saveAllSync()
        shutdown()
    }

    fun shutdown() {
        store.shutdown()
    }

    private fun drainSaveQueue(uniqueId: UUID) {
        while (true) {
            val request = nextSaveRequest(uniqueId) ?: break
            val nextData = request.data
            val currentEntry = entry(uniqueId)
            val expectedVersion = currentEntry.version
            val snapshot = PlayerDataSnapshot(
                data = nextData.copyDeep(),
                version = expectedVersion + 1,
                updatedAt = System.currentTimeMillis(),
            )
            when (val result = store.save(snapshot, expectedVersion)) {
                is SaveResult.Success -> {
                    cache.compute(uniqueId) { _, existing ->
                        val entry = existing ?: PlayerCacheEntry(snapshot.data.copyDeep(), result.version)
                        entry.data = snapshot.data.copyDeep()
                        entry.version = result.version
                        entry.stale = false
                        entry.lastSyncAt = result.updatedAt
                        entry
                    }
                    publishInvalidation(uniqueId, result.version, result.updatedAt)
                    dirty.remove(uniqueId)
                    completeCallbacks(request, result)
                }
                is SaveResult.Conflict -> {
                    result.latest?.let(::replace)
                    completeCallbacks(request, result)
                    plugin.logger.fine("玩家数据保存遇到版本冲突，已回源刷新: $uniqueId")
                }
                is SaveResult.Failure -> {
                    plugin.logger.warning(result.message)
                    completeCallbacks(request, result)
                    if (request.retryOnFailure) {
                        pendingSaves.putIfAbsent(uniqueId, request.withoutCallbacks())
                    }
                    break
                }
            }
        }
    }

    private fun scheduleDrain(uniqueId: UUID) {
        scheduler.runAsync {
            try {
                drainSaveQueue(uniqueId)
            } finally {
                savingNow.remove(uniqueId)
                if (hasPendingSave(uniqueId) && savingNow.add(uniqueId)) {
                    scheduleDrain(uniqueId)
                }
            }
        }
    }

    private fun nextSaveRequest(uniqueId: UUID): PendingSave? {
        val strictQueue = strictSaves[uniqueId]
        val strict = strictQueue?.poll()
        if (strictQueue != null && strictQueue.isEmpty()) {
            strictSaves.remove(uniqueId, strictQueue)
        }
        return strict ?: pendingSaves.remove(uniqueId)
    }

    private fun hasPendingSave(uniqueId: UUID): Boolean {
        return pendingSaves.containsKey(uniqueId) || strictSaves[uniqueId]?.isNotEmpty() == true
    }

    private fun completeCallbacks(request: PendingSave, result: SaveResult) {
        request.callbacks.forEach { callback ->
            runCatching { callback(result) }
                .onFailure { plugin.logger.warning("Player data save callback failed: ${it.message}") }
        }
        request.callbacks.clear()
    }

    private fun publishInvalidation(uniqueId: UUID, version: Long, updatedAt: Long) {
        redisSyncService?.publish(
            PlayerSyncMessage(
                uniqueId = uniqueId,
                version = version,
                serverId = serverId,
                updatedAt = updatedAt,
            ),
        )
    }

    private fun loadEntryFromStore(uniqueId: UUID): PlayerCacheEntry {
        val snapshot = store.load(uniqueId) ?: PlayerDataSnapshot(PlayerTagData(uniqueId), 0L, System.currentTimeMillis())
        return PlayerCacheEntry(
            data = snapshot.data.copyDeep(),
            version = snapshot.version,
            stale = false,
            lastSyncAt = snapshot.updatedAt,
        )
    }

    private fun createEmptyCached(uniqueId: UUID): PlayerCacheEntry {
        plugin.logger.warning("Player data for $uniqueId was requested before async preparation completed; using cache-only empty data.")
        return cache.computeIfAbsent(uniqueId) {
            PlayerCacheEntry(PlayerTagData(uniqueId), 0L, stale = true)
        }
    }

    private data class PendingSave(
        val data: PlayerTagData,
        val retryOnFailure: Boolean,
        val callbacks: MutableList<(SaveResult) -> Unit>,
    ) {
        fun withoutCallbacks(): PendingSave = copy(callbacks = mutableListOf())
    }
}
