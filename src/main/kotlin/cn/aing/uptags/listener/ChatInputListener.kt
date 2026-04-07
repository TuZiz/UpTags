package cn.aing.uptags.listener

import cn.aing.uptags.config.MessageService
import cn.aing.uptags.service.ClickableMessageService
import cn.aing.uptags.service.CustomTitleService
import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import org.bukkit.event.player.AsyncPlayerChatEvent

class ChatInputListener(
    private val customTitleService: CustomTitleService,
    private val clickableMessageService: ClickableMessageService,
    private val messageService: MessageService,
) : Listener {
    @EventHandler(ignoreCancelled = true)
    fun onChat(event: AsyncPlayerChatEvent) {
        customTitleService.activeDraft(event.player) ?: return
        event.isCancelled = true
        val result = customTitleService.handleInput(event.player, event.message)
        if (!result.success) {
            result.messageKey?.let { key ->
                when (val args = result.args) {
                    null -> messageService.send(event.player, key)
                    is Array<*> -> messageService.send(event.player, key, *args)
                    else -> messageService.send(event.player, key, args)
                }
            }
            return
        }
        result.messageKey?.let { key ->
            when (val args = result.args) {
                null -> messageService.send(event.player, key)
                is Array<*> -> messageService.send(event.player, key, *args)
                else -> messageService.send(event.player, key, args)
            }
        }
        clickableMessageService.sendPreviewControls(event.player, customTitleService.previewText(event.player))
        messageService.send(event.player, "custom-title-preview-ready")
    }
}
