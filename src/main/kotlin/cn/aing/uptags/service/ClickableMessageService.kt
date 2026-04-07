package cn.aing.uptags.service

import cn.aing.uptags.Support
import net.md_5.bungee.api.chat.ClickEvent
import net.md_5.bungee.api.chat.ComponentBuilder
import net.md_5.bungee.api.chat.TextComponent
import org.bukkit.entity.Player

class ClickableMessageService {
    fun sendCurrencyChoices(player: Player) {
        val components = ComponentBuilder("")
            .append(button("金币 888888", "money", "&#FDE047"))
            .append(TextComponent(" "))
            .append(button("称号币 100", "title_coin", "&#A78BFA"))
            .append(TextComponent(" "))
            .append(button("点券 30", "points", "&#60A5FA"))
            .create()
        player.spigot().sendMessage(*components)
    }

    fun sendPreviewControls(player: Player, preview: String?) {
        val header = TextComponent(Support.color("&#FDE047称号预览: "))
        val previewText = TextComponent(preview ?: Support.color("&#94A3B8(尚未生成预览)"))
        player.spigot().sendMessage(header, previewText)
        val components = ComponentBuilder("")
            .append(button("上一套", "prev", "&#60A5FA"))
            .append(TextComponent(" "))
            .append(button("下一套", "next", "&#60A5FA"))
            .append(TextComponent(" "))
            .append(button("重随机", "reroll", "&#A78BFA"))
            .append(TextComponent(" "))
            .append(button("确认颜色", "confirm", "&#A7F3D0"))
            .append(TextComponent(" "))
            .append(button("取消", "cancel", "&#F87171"))
            .create()
        player.spigot().sendMessage(*components)
    }

    private fun button(text: String, action: String, color: String): TextComponent {
        return TextComponent(Support.color("$color[$text] ")).apply {
            clickEvent = ClickEvent(ClickEvent.Action.RUN_COMMAND, "/tags custom preview $action")
        }
    }
}
