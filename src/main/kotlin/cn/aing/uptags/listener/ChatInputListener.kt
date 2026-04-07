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
        val draft = customTitleService.activeDraft(event.player) ?: return
        event.isCancelled = true
        val result = customTitleService.submitText(event.player, event.message)
        if (!result.success) {
            result.messageKey?.let { key ->
                if (result.args != null) {
                    messageService.send(event.player, key, result.args)
                } else {
                    messageService.send(event.player, key)
                }
            }
            return
        }
        clickableMessageService.sendPreviewControls(event.player, customTitleService.previewText(event.player))
        messageService.send(event.player, "custom-title-preview-ready", draft.presetId)
    }
}
