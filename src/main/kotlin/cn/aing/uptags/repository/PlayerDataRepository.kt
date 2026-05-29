package cn.aing.uptags.repository

import cn.aing.uptags.compat.PlatformScheduler
import cn.aing.uptags.model.runtime.PlayerTagData
import cn.aing.uptags.repository.store.PlayerDataCodec
import cn.aing.uptags.repository.store.PlayerDataStore
import cn.aing.uptags.service.sync.PlayerSyncMessage
import cn.aing.uptags.service.sync.RedisSyncService
import org.bukkit.plugin.java.JavaPlugin
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import org.bukkit.entity.Player

class PlayerDataRepository(
    private val plugin: JavaPlugin,
    private val scheduler: PlatformScheduler,
    private val store: PlayerDataStore,
    normalSaveDebounceMillis: Long = 0L,
    maxSaveDelayMillis: Long = normalSaveDebounceMillis,
) {
    private val cache = ConcurrentHashMap<UUID, PlayerCacheEntry>()
    private val dirty = ConcurrentHashMap.newKeySet<UUID>()
    private val pendingSaves = ConcurrentHashMap<UUID, PendingSave>()
    private val strictSaves = ConcurrentHashMap<UUID, ConcurrentLinkedQueue<PendingSave>>()
    private val savingNow = ConcurrentHashMap.newKeySet<UUID>()
    private val forceNormalDrains = ConcurrentHashMap.newKeySet<UUID>()
    private val normalSaveTimers = ConcurrentHashMap<UUID, ScheduledFuture<*>>()
    private val lastSavedMainJsonHashes = ConcurrentHashMap<UUID, String>()
    private val lastSavedOrdersHashes = ConcurrentHashMap<UUID, OrdersHashes>()
    private val loading = ConcurrentHashMap<UUID, CompletableFuture<PlayerCacheEntry>>()
    private val saveSequence = AtomicLong()
    private val normalSaveDebounceMillis = normalSaveDebounceMillis.coerceAtLeast(0L)
    private val maxSaveDelayMillis = maxSaveDelayMillis.coerceAtLeast(0L)
    private val normalSaveTimer = Executors.newSingleThreadScheduledExecutor { runnable ->
        Thread(runnable, "${plugin.name.ifBlank { "UpTags" }}-normal-save-timer").apply { isDaemon = true }
    }
    private var redisSyncService: RedisSyncService? = null
    private var serverId: String = "local"

    init {
        store.initialize()
    }

    fun attachSync(redisSyncService: RedisSyncService, serverId: String) {
        this.redisSyncService = redisSyncService
        this.serverId = serverId
    }

    fun get(uniqueId: UUID): PlayerTagData = getCached(uniqueId) ?: throw PlayerDataNotLoadedException(uniqueId)

    fun getCached(uniqueId: UUID): PlayerTagData? = cache[uniqueId]?.data

    fun isLoaded(uniqueId: UUID): Boolean = cache[uniqueId]?.stale == false

    fun requireLoaded(player: Player): PlayerTagData? = getCached(player.uniqueId)?.takeIf { isLoaded(player.uniqueId) }

    fun entry(uniqueId: UUID): PlayerCacheEntry = cache[uniqueId] ?: throw PlayerDataNotLoadedException(uniqueId)

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
        val mainJson = PlayerDataCodec.serialize(snapshot.data, includeOrders = false)
        lastSavedMainJsonHashes[snapshot.data.uniqueId] = hashJson(mainJson)
        lastSavedOrdersHashes[snapshot.data.uniqueId] = ordersHashes(snapshot.data)
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
        val now = System.currentTimeMillis()
        val sequence = saveSequence.incrementAndGet()
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
                firstQueuedAt = existing?.firstQueuedAt ?: now,
                latestQueuedAt = now,
                sequence = sequence,
            )
        }
        scheduleNormalSave(data.uniqueId)
    }

    fun saveAsyncStrict(data: PlayerTagData, callback: (SaveResult) -> Unit) {
        val now = System.currentTimeMillis()
        markDirty(data)
        strictSaves.computeIfAbsent(data.uniqueId) { ConcurrentLinkedQueue() }
            .add(
                PendingSave(
                    data = data.copyDeep(),
                    retryOnFailure = false,
                    callbacks = mutableListOf(callback),
                    firstQueuedAt = now,
                    latestQueuedAt = now,
                    sequence = saveSequence.incrementAndGet(),
                ),
            )
        requestDrain(data.uniqueId, forceNormal = false)
    }

    fun saveSync(data: PlayerTagData) {
        when (val result = saveToStore(data.uniqueId, data.copyDeep())) {
            is SaveResult.Success -> {
                if (!hasPendingSave(data.uniqueId)) {
                    dirty.remove(data.uniqueId)
                }
            }
            is SaveResult.Conflict -> result.latest?.let {
                replace(it)
                if (!hasPendingSave(data.uniqueId)) {
                    dirty.remove(data.uniqueId)
                }
            }
            is SaveResult.Failure -> plugin.logger.warning(result.message)
        }
    }

    fun saveAllSync() {
        (pendingSaves.keys + strictSaves.keys).toSet().forEach { uniqueId ->
            cancelNormalTimer(uniqueId)
            forceNormalDrains.remove(uniqueId)
            drainSaveQueue(uniqueId, forceNormal = true)
        }
        dirty.toList().forEach { uniqueId ->
            cache[uniqueId]?.let { entry -> saveSync(entry.data) }
        }
    }

    fun invalidate(uniqueId: UUID) {
        cancelNormalTimer(uniqueId)
        cache.remove(uniqueId)
        dirty.remove(uniqueId)
        pendingSaves.remove(uniqueId)
        strictSaves.remove(uniqueId)
        savingNow.remove(uniqueId)
        forceNormalDrains.remove(uniqueId)
        lastSavedMainJsonHashes.remove(uniqueId)
        lastSavedOrdersHashes.remove(uniqueId)
    }

    fun flushPlayerAsync(uniqueId: UUID) {
        cancelNormalTimer(uniqueId)
        requestDrain(uniqueId, forceNormal = true)
    }

    fun flushAndStop(timeoutSeconds: Long = 30L) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(timeoutSeconds.coerceAtLeast(1L))
        (pendingSaves.keys + strictSaves.keys).toSet().forEach(::flushPlayerAsync)
        while ((pendingSaves.isNotEmpty() || strictSaves.isNotEmpty() || savingNow.isNotEmpty() || forceNormalDrains.isNotEmpty()) && System.nanoTime() < deadline) {
            Thread.sleep(25L)
        }
        saveAllSync()
        shutdown()
    }

    fun shutdown() {
        normalSaveTimers.keys.toList().forEach(::cancelNormalTimer)
        normalSaveTimer.shutdownNow()
        store.shutdown()
    }

    private fun drainSaveQueue(uniqueId: UUID, forceNormal: Boolean) {
        var allowNormalSave = forceNormal || forceNormalDrains.remove(uniqueId)
        while (true) {
            val request = nextSaveRequest(uniqueId, allowNormalSave) ?: break
            val nextData = request.data
            when (val result = saveToStore(uniqueId, nextData)) {
                is SaveResult.Success -> {
                    if (!request.normal) {
                        discardNormalSavesBefore(uniqueId, request.sequence, result)
                    }
                    if (!hasPendingSave(uniqueId)) {
                        dirty.remove(uniqueId)
                    }
                    completeCallbacks(request, result)
                }
                is SaveResult.Conflict -> {
                    result.latest?.let(::replace)
                    if (!hasPendingSave(uniqueId)) {
                        dirty.remove(uniqueId)
                    }
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
            allowNormalSave = false
        }
    }

    private fun requestDrain(uniqueId: UUID, forceNormal: Boolean) {
        if (forceNormal) {
            forceNormalDrains += uniqueId
        }
        if (!savingNow.add(uniqueId)) {
            return
        }
        scheduleDrain(uniqueId)
    }

    private fun scheduleDrain(uniqueId: UUID) {
        scheduler.runAsync {
            try {
                drainSaveQueue(uniqueId, forceNormal = false)
            } finally {
                savingNow.remove(uniqueId)
                when {
                    hasImmediateSave(uniqueId) && savingNow.add(uniqueId) -> scheduleDrain(uniqueId)
                    pendingSaves.containsKey(uniqueId) -> scheduleNormalSave(uniqueId)
                }
            }
        }
    }

    private fun scheduleNormalSave(uniqueId: UUID) {
        val pending = pendingSaves[uniqueId] ?: return
        if (normalSaveDebounceMillis <= 0L) {
            requestDrain(uniqueId, forceNormal = true)
            return
        }
        val now = System.currentTimeMillis()
        val debounceDueAt = pending.latestQueuedAt + normalSaveDebounceMillis
        val maxDueAt = if (maxSaveDelayMillis > 0L) pending.firstQueuedAt + maxSaveDelayMillis else debounceDueAt
        val delayMillis = (minOf(debounceDueAt, maxDueAt) - now).coerceAtLeast(0L)
        cancelNormalTimer(uniqueId)
        normalSaveTimers[uniqueId] = normalSaveTimer.schedule(
            {
                normalSaveTimers.remove(uniqueId)
                requestDrain(uniqueId, forceNormal = true)
            },
            delayMillis,
            TimeUnit.MILLISECONDS,
        )
    }

    private fun nextSaveRequest(uniqueId: UUID, forceNormal: Boolean): PendingSave? {
        val strictQueue = strictSaves[uniqueId]
        val strict = strictQueue?.poll()
        if (strictQueue != null && strictQueue.isEmpty()) {
            strictSaves.remove(uniqueId, strictQueue)
        }
        if (strict != null) {
            return strict
        }
        val pending = pendingSaves[uniqueId] ?: return null
        if (!forceNormal && !isNormalSaveDue(pending)) {
            return null
        }
        cancelNormalTimer(uniqueId)
        return pendingSaves.remove(uniqueId)?.copy(normal = true)
    }

    private fun hasPendingSave(uniqueId: UUID): Boolean {
        return pendingSaves.containsKey(uniqueId) || strictSaves[uniqueId]?.isNotEmpty() == true
    }

    private fun hasImmediateSave(uniqueId: UUID): Boolean {
        return forceNormalDrains.contains(uniqueId) ||
            strictSaves[uniqueId]?.isNotEmpty() == true ||
            pendingSaves[uniqueId]?.let(::isNormalSaveDue) == true
    }

    private fun isNormalSaveDue(request: PendingSave): Boolean {
        if (normalSaveDebounceMillis <= 0L) {
            return true
        }
        val now = System.currentTimeMillis()
        val debounceDue = now - request.latestQueuedAt >= normalSaveDebounceMillis
        val maxDelayDue = maxSaveDelayMillis > 0L && now - request.firstQueuedAt >= maxSaveDelayMillis
        return debounceDue || maxDelayDue
    }

    private fun cancelNormalTimer(uniqueId: UUID) {
        normalSaveTimers.remove(uniqueId)?.cancel(false)
    }

    private fun discardNormalSavesBefore(uniqueId: UUID, sequence: Long, result: SaveResult) {
        var discarded: PendingSave? = null
        pendingSaves.compute(uniqueId) { _, pending ->
            if (pending != null && pending.sequence < sequence) {
                discarded = pending
                null
            } else {
                pending
            }
        }
        val superseded = discarded
        if (superseded != null) {
            cancelNormalTimer(uniqueId)
            completeCallbacks(superseded.copy(normal = true), result)
        }
    }

    private fun saveToStore(uniqueId: UUID, data: PlayerTagData): SaveResult {
        val currentEntry = entry(uniqueId)
        val expectedVersion = currentEntry.version
        val snapshot = PlayerDataSnapshot(
            data = data.copyDeep(),
            version = expectedVersion + 1,
            updatedAt = System.currentTimeMillis(),
        )
        val mainJson = PlayerDataCodec.serialize(snapshot.data, includeOrders = false)
        val mainHash = hashJson(mainJson)
        val ordersHash = ordersHashes(snapshot.data)
        val mainDataChanged = lastSavedMainJsonHashes[uniqueId] != mainHash
        val ordersChanged = lastSavedOrdersHashes[uniqueId] != ordersHash
        val result = store.save(snapshot, expectedVersion, mainJson, mainDataChanged, ordersChanged)
        when (result) {
            is SaveResult.Success -> {
                cache.compute(uniqueId) { _, existing ->
                    val entry = existing ?: PlayerCacheEntry(snapshot.data.copyDeep(), result.version)
                    entry.data = snapshot.data.copyDeep()
                    entry.version = result.version
                    entry.stale = false
                    entry.lastSyncAt = result.updatedAt
                    entry
                }
                lastSavedMainJsonHashes[uniqueId] = mainHash
                lastSavedOrdersHashes[uniqueId] = ordersHash
                if (mainDataChanged || ordersChanged) {
                    publishInvalidation(uniqueId, result.version, result.updatedAt)
                }
            }
            is SaveResult.Conflict -> result.latest?.let(::replace)
            is SaveResult.Failure -> {}
        }
        return result
    }

    private fun ordersHashes(data: PlayerTagData): OrdersHashes {
        val purchaseRaw = buildStableString {
            data.purchaseOrders.toSortedMap().forEach { (orderId, order) ->
                value(orderId)
                value(order.productId)
                value(order.targetId)
                value(order.status.name)
                value(order.currencyType.name)
                value(order.currencyAmount.toString())
                values(order.submittedItems)
                values(order.compensatedItems)
                value(order.createdAt.toString())
                value(order.updatedAt.toString())
                value(order.failureReason.orEmpty())
            }
        }
        val customTitleRaw = buildStableString {
            data.customTitleOrders.toSortedMap().forEach { (orderId, order) ->
                value(orderId)
                value(order.titleId)
                value(order.rawText)
                value(order.presetId)
                value(order.groupId.orEmpty())
                value(order.currencyType.name)
                value(order.currencyAmount.toString())
                value(order.status.name)
                value(order.createdAt.toString())
                value(order.updatedAt.toString())
                value(order.failureReason.orEmpty())
                value(order.previousEquippedTagId.orEmpty())
                value(order.previousEquippedCustomTitleId.orEmpty())
            }
        }
        return OrdersHashes(
            purchaseOrders = hashJson(purchaseRaw),
            customTitleOrders = hashJson(customTitleRaw),
        )
    }

    private fun buildStableString(block: StableHashBuilder.() -> Unit): String {
        return StableHashBuilder().apply(block).build()
    }

    private fun hashJson(json: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(json.toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { "%02x".format(it.toInt() and 0xff) }
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
        val loaded = store.load(uniqueId)
        val snapshot = loaded ?: PlayerDataSnapshot(PlayerTagData(uniqueId), 0L, System.currentTimeMillis())
        if (loaded != null) {
            val mainJson = PlayerDataCodec.serialize(snapshot.data, includeOrders = false)
            lastSavedMainJsonHashes[uniqueId] = hashJson(mainJson)
            lastSavedOrdersHashes[uniqueId] = ordersHashes(snapshot.data)
        } else {
            lastSavedMainJsonHashes.remove(uniqueId)
            lastSavedOrdersHashes[uniqueId] = ordersHashes(snapshot.data)
        }
        return PlayerCacheEntry(
            data = snapshot.data.copyDeep(),
            version = snapshot.version,
            stale = false,
            lastSyncAt = snapshot.updatedAt,
        )
    }

    private data class OrdersHashes(
        val purchaseOrders: String,
        val customTitleOrders: String,
    )

    private class StableHashBuilder {
        private val builder = StringBuilder()

        fun value(value: String) {
            builder.append(value.length).append(':').append(value)
        }

        fun values(values: List<String>) {
            builder.append(values.size).append('[')
            values.forEach(::value)
            builder.append(']')
        }

        fun build(): String = builder.toString()
    }

    private data class PendingSave(
        val data: PlayerTagData,
        val retryOnFailure: Boolean,
        val callbacks: MutableList<(SaveResult) -> Unit>,
        val firstQueuedAt: Long,
        val latestQueuedAt: Long,
        val sequence: Long,
        val normal: Boolean = false,
    ) {
        fun withoutCallbacks(): PendingSave = copy(callbacks = mutableListOf(), normal = true)
    }
}

class PlayerDataNotLoadedException(uniqueId: UUID) : IllegalStateException("Player data is not loaded: $uniqueId")
