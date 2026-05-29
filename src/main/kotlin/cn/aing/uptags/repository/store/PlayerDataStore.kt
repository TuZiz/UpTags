package cn.aing.uptags.repository.store

import cn.aing.uptags.repository.PlayerDataSnapshot
import cn.aing.uptags.repository.SaveResult
import java.util.UUID

interface PlayerDataStore {
    fun initialize() {}

    fun load(uniqueId: UUID): PlayerDataSnapshot?

    fun loadOrders(uniqueId: UUID): PlayerOrdersSnapshot = PlayerOrdersSnapshot()

    fun save(snapshot: PlayerDataSnapshot, expectedVersion: Long?): SaveResult

    fun save(
        snapshot: PlayerDataSnapshot,
        expectedVersion: Long?,
        serializedMainData: String,
        mainDataChanged: Boolean,
    ): SaveResult = save(snapshot, expectedVersion)

    fun save(
        snapshot: PlayerDataSnapshot,
        expectedVersion: Long?,
        serializedMainData: String,
        mainDataChanged: Boolean,
        ordersChanged: Boolean,
    ): SaveResult = save(snapshot, expectedVersion, serializedMainData, mainDataChanged)

    fun loadAll(): List<PlayerDataSnapshot> = emptyList()

    fun loadVersions(uniqueIds: Collection<UUID>): Map<UUID, Long> = emptyMap()

    fun shutdown() {}
}
