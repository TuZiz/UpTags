package cn.aing.uptags.command

import cn.aing.uptags.UpTagsPlugin
import cn.aing.uptags.config.MessageService
import cn.aing.uptags.gui.MenuService
import cn.aing.uptags.service.AdminActionResult
import cn.aing.uptags.service.ClickableMessageService
import cn.aing.uptags.service.CustomTitleService
import cn.aing.uptags.service.PlayerNameService
import cn.aing.uptags.service.ScrollService
import cn.aing.uptags.service.ShopService
import cn.aing.uptags.service.TagService
import org.bukkit.Bukkit
import org.bukkit.OfflinePlayer
import org.bukkit.command.CommandSender
import org.bukkit.entity.Player
import java.util.Locale

internal class TagsCommandContext(
    val plugin: UpTagsPlugin,
    val tagService: TagService,
    val scrollService: ScrollService,
    val shopService: ShopService,
    val customTitleService: CustomTitleService,
    val clickableMessageService: ClickableMessageService,
    val menuService: MenuService,
    val messageService: MessageService,
    val playerNameService: PlayerNameService,
) {
    fun requireUse(sender: CommandSender): Boolean = requirePermission(sender, AdminAccess.USE, usePermission = true)

    fun requirePermission(sender: CommandSender, permission: String, vararg inherited: String, usePermission: Boolean = false): Boolean {
        val allowed = if (usePermission) {
            AdminAccess.hasUse(sender)
        } else {
            AdminAccess.has(sender, permission, *inherited)
        }
        if (!allowed) {
            messageService.send(sender, "no-permission")
        }
        return allowed
    }

    fun sendAdminResult(sender: CommandSender, result: AdminActionResult) {
        messageService.send(sender, result.messageKey, *result.args.toTypedArray())
    }

    fun targetLabel(target: OfflinePlayer): String = playerNameService.label(target)

    fun onlinePlayerNames(): List<String> = Bukkit.getOnlinePlayers().map(Player::getName)

    fun titleIdsForTarget(input: String): List<String> {
        val target = resolveOfflinePlayer(input) ?: return plugin.config.tags.keys.toList()
        return (plugin.config.tags.keys + tagService.data(target.uniqueId).customTitles.keys).distinct()
    }

    fun customTitleIdsForTarget(input: String): List<String> {
        val target = resolveOfflinePlayer(input) ?: return emptyList()
        return tagService.data(target.uniqueId).customTitles.keys.toList()
    }

    fun filter(values: List<String>, input: String): List<String> {
        val normalizedInput = input.lowercase(Locale.ROOT)
        return values.filter { it.lowercase(Locale.ROOT).startsWith(normalizedInput) }
    }

    fun resolveOfflinePlayer(input: String): OfflinePlayer? = playerNameService.resolve(input)
}
