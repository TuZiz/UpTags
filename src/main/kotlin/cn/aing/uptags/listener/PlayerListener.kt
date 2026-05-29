package cn.aing.uptags.listener

import cn.aing.uptags.repository.PlayerDataRepository
import cn.aing.uptags.compat.PlatformScheduler
import cn.aing.uptags.service.title.CustomTitleService
import cn.aing.uptags.service.effect.EffectService
import cn.aing.uptags.service.player.PlayerNameService
import cn.aing.uptags.service.tag.TagService
import cn.aing.uptags.config.MessageService
import cn.aing.uptags.service.shop.ShopService
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
    private val scheduler: PlatformScheduler,
    private val messageService: MessageService,
    private val shopService: ShopService,
) : Listener {
    @EventHandler
    fun onJoin(event: PlayerJoinEvent) {
        val player = event.player
        playerNameService.remember(player)
        repository.preparePlayerAsync(player.uniqueId).whenComplete { _, error ->
            if (error != null) {
                scheduler.runPlayer(player) {
                    if (player.isOnline) {
                        messageService.send(player, "data-loading-failed")
                    }
                }
                return@whenComplete
            }
            scheduler.runPlayer(player) {
                if (!player.isOnline) {
                    return@runPlayer
                }
                tagService.preparePlayer(player, true)
                customTitleService.preparePlayer(player)
                shopService.recoverPendingOrders(player)
                effectService.startPlayer(player)
            }
        }
    }

    @EventHandler
    fun onQuit(event: PlayerQuitEvent) {
        effectService.stopPlayer(event.player.uniqueId)
        customTitleService.cancelDraft(event.player, notify = false)
        repository.getCached(event.player.uniqueId)?.let { data ->
            repository.saveAsync(data)
            repository.flushPlayerAsync(event.player.uniqueId)
        }
    }
}
