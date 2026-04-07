package cn.aing.uptags.service.sync

import cn.aing.uptags.compat.PlatformScheduler
import cn.aing.uptags.repository.PlayerDataRepository
import org.bukkit.Bukkit
import java.util.UUID

class PlayerSyncService(
    private val repository: PlayerDataRepository,
    private val scheduler: PlatformScheduler,
) {
    fun markStale(uniqueId: UUID) {
        repository.markStale(uniqueId)
    }

    fun handleRemoteInvalidation(message: PlayerSyncMessage) {
        repository.markStale(message.uniqueId)
        val player = Bukkit.getPlayer(message.uniqueId) ?: return
        scheduler.runAsync {
            repository.refreshIfStale(message.uniqueId)
            scheduler.runPlayer(player) {}
        }
    }
}
