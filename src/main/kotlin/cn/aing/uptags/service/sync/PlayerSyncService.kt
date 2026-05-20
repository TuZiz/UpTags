package cn.aing.uptags.service.sync

import cn.aing.uptags.compat.PlatformScheduler
import cn.aing.uptags.repository.PlayerDataRepository
import java.util.UUID

class PlayerSyncService(
    private val repository: PlayerDataRepository,
    private val scheduler: PlatformScheduler,
) {
    fun markStale(uniqueId: UUID) {
        repository.markStale(uniqueId)
    }

    fun handleRemoteInvalidation(message: PlayerSyncMessage) {
        if (!repository.shouldAcceptRemoteVersion(message.uniqueId, message.version)) {
            return
        }
        repository.markStale(message.uniqueId)
        scheduler.runAsync {
            if (repository.shouldAcceptRemoteVersion(message.uniqueId, message.version)) {
                repository.refreshIfStale(message.uniqueId)
            }
        }
    }
}
