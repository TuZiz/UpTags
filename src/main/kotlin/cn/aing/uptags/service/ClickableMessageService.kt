package cn.aing.uptags.service

import cn.aing.uptags.Support
import org.bukkit.entity.Player
import net.md_5.bungee.api.chat.ClickEvent
import net.md_5.bungee.api.chat.ComponentBuilder
import net.md_5.bungee.api.chat.TextComponent

class ClickableMessageService {
    fun sendPreviewControls(player: Player, preview: String?) {
        val header = TextComponent(Support.color("&#FDE68A称号预览: "))
        val previewText = TextComponent(preview ?: Support.color("&#94A3B8(尚未生成预览)"))
        player.spigot().sendMessage(header, previewText)

        val components = ComponentBuilder("")
            .append(button("上一套", "/tags custom preview prev", "&#7DD3FC"))
            .append(TextComponent(" "))
            .append(button("下一套", "/tags custom preview next", "&#7DD3FC"))
            .append(TextComponent(" "))
            .append(button("重随机", "/tags custom preview reroll", "&#FF8FD8"))
            .append(TextComponent(" "))
            .append(button("确认", "/tags custom preview confirm", "&#86EFAC"))
            .append(TextComponent(" "))
            .append(button("取消", "/tags custom preview cancel", "&#FF7B7B"))
            .create()
        player.spigot().sendMessage(*components)
    }

    private fun button(text: String, command: String, color: String): TextComponent {
        return TextComponent(Support.color("$color[$text]")).apply {
            clickEvent = ClickEvent(ClickEvent.Action.RUN_COMMAND, command)
        }
    }
}
