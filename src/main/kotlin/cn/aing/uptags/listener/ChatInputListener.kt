package cn.aing.uptags.listener

import cn.aing.uptags.config.MessageService
import cn.aing.uptags.service.ClickableMessageService
import cn.aing.uptags.service.CustomTitleService
import org.bukkit.Bukkit
import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import org.bukkit.event.player.AsyncPlayerChatEvent
import org.bukkit.plugin.java.JavaPlugin

class ChatInputListener(
    private val plugin: JavaPlugin,
    private val customTitleService: CustomTitleService,
    private val clickableMessageService: ClickableMessageService,
    private val messageService: MessageService,
) : Listener {
    @EventHandler(ignoreCancelled = true)
    fun onChat(event: AsyncPlayerChatEvent) {
        val draft = customTitleService.activeDraft(event.player) ?: return
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
        // 名字阶段（INPUT_NAME）通过后会把 stage 切到 PREVIEW
        if (draft.stage.name == "PREVIEW") {
            // 从异步聊天线程切回主线程再发送可点击预览
            Bukkit.getScheduler().runTask(plugin, Runnable {
                messageService.send(event.player, "custom-title-preview-ready")
                clickableMessageService.sendPreviewControls(
                    event.player,
                    customTitleService.previewMessage(event.player),
                    customTitleService.previewPalette(event.player),
                    customTitleService.currentPaletteLibrary(event.player),
                    customTitleService.availablePaletteLibraries(event.player),
                    customTitleService.manualColorsAllowed(event.player),
                )
            })
            return
        }
        // 其他阶段按原逻辑提示（目前用不到）
        result.messageKey?.let { key ->
            when (val args = result.args) {
                null -> messageService.send(event.player, key)
                is Array<*> -> messageService.send(event.player, key, *args)
                else -> messageService.send(event.player, key, args)
            }
        }
    }
}
