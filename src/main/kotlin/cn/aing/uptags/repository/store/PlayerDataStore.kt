package cn.aing.uptags.repository.store

import cn.aing.uptags.repository.PlayerDataSnapshot
import cn.aing.uptags.repository.SaveResult
import java.util.UUID

interface PlayerDataStore {
    fun initialize() {}

    fun load(uniqueId: UUID): PlayerDataSnapshot?

    fun save(snapshot: PlayerDataSnapshot, expectedVersion: Long?): SaveResult

    fun loadVersions(uniqueIds: Collection<UUID>): Map<UUID, Long> = emptyMap()

    fun shutdown() {}
}
