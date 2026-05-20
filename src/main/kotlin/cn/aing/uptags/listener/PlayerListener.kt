package cn.aing.uptags.listener

import cn.aing.uptags.repository.PlayerDataRepository
import cn.aing.uptags.service.title.CustomTitleService
import cn.aing.uptags.service.effect.EffectService
import cn.aing.uptags.service.player.PlayerNameService
import cn.aing.uptags.service.tag.TagService
import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import org.bukkit.event.player.PlayerJoinEvent
import org.bukkit.event.player.PlayerQuitEvent

class PlayerListener(
    private val tagService: TagService,
    private val customTitleService: CustomTitleService,
    private val repository: PlayerDataRepository,
    private val effectService: EffectService,
    private val playerNameService: PlayerNameService,
) : Listener {
    @EventHandler
    fun onJoin(event: PlayerJoinEvent) {
        playerNameService.remember(event.player)
        tagService.preparePlayer(event.player, true)
        customTitleService.preparePlayer(event.player)
        effectService.startPlayer(event.player)
    }

    @EventHandler
    fun onQuit(event: PlayerQuitEvent) {
        effectService.stopPlayer(event.player.uniqueId)
        customTitleService.cancelDraft(event.player, notify = false)
        repository.saveAsync(repository.get(event.player.uniqueId))
    }
}
