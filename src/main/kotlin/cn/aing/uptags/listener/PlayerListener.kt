package cn.aing.uptags.listener

import cn.aing.uptags.repository.PlayerDataRepository
import cn.aing.uptags.service.EffectService
import cn.aing.uptags.service.TagService
import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import org.bukkit.event.player.PlayerJoinEvent
import org.bukkit.event.player.PlayerQuitEvent

class PlayerListener(
    private val tagService: TagService,
    private val repository: PlayerDataRepository,
    private val effectService: EffectService,
) : Listener {
    @EventHandler
    fun onJoin(event: PlayerJoinEvent) {
        tagService.preparePlayer(event.player, true)
        effectService.startPlayer(event.player)
    }

    @EventHandler
    fun onQuit(event: PlayerQuitEvent) {
        effectService.stopPlayer(event.player.uniqueId)
        repository.saveAsync(repository.get(event.player.uniqueId))
    }
}
