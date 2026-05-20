package cn.aing.uptags.listener

import cn.aing.uptags.compat.PlatformScheduler
import cn.aing.uptags.config.MessageService
import cn.aing.uptags.service.message.ClickableMessageService
import cn.aing.uptags.service.title.CustomTitleService
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import org.bukkit.event.player.AsyncPlayerChatEvent

class ChatInputListener(
    private val scheduler: PlatformScheduler,
    private val customTitleService: CustomTitleService,
    private val clickableMessageService: ClickableMessageService,
    private val messageService: MessageService,
) : Listener {
    @EventHandler(ignoreCancelled = true)
    fun onChat(event: AsyncPlayerChatEvent) {
        val player = event.player
        if (!customTitleService.hasActiveDraft(player.uniqueId)) {
            return
        }
        val message = event.message
        event.isCancelled = true
        scheduler.runPlayer(player) {
            handlePlayerInput(player, message)
        }
    }

    private fun handlePlayerInput(player: Player, message: String) {
        val result = customTitleService.handleInput(player, message)
        if (!result.success) {
            sendResultMessage(player, result.messageKey, result.args)
            return
        }
        if (customTitleService.activeDraft(player)?.stage?.name == "PREVIEW") {
            messageService.send(player, "custom-title-preview-ready")
            clickableMessageService.sendPreviewControls(
                player,
                customTitleService.previewMessage(player),
                customTitleService.previewPalette(player),
                customTitleService.currentPaletteLibrary(player),
                customTitleService.availablePaletteLibraries(player),
                customTitleService.manualColorsAllowed(player),
            )
            return
        }
        sendResultMessage(player, result.messageKey, result.args)
    }

    private fun sendResultMessage(player: Player, key: String?, payload: Any?) {
        key ?: return
        when (payload) {
            null -> messageService.send(player, key)
            is Array<*> -> messageService.send(player, key, *payload)
            else -> messageService.send(player, key, payload)
        }
    }
}
