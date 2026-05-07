package cn.aing.uptags.command

import cn.aing.uptags.UpTagsPlugin
import cn.aing.uptags.config.MessageService
import cn.aing.uptags.gui.MenuService
import cn.aing.uptags.service.ClickableMessageService
import cn.aing.uptags.service.CustomTitleService
import cn.aing.uptags.service.PlayerNameService
import cn.aing.uptags.service.ScrollService
import cn.aing.uptags.service.ShopService
import cn.aing.uptags.service.TagService
import org.bukkit.command.Command
import org.bukkit.command.CommandExecutor
import org.bukkit.command.CommandSender
import org.bukkit.command.TabCompleter
import org.bukkit.entity.Player
import java.util.Locale

class TagsCommand(
    plugin: UpTagsPlugin,
    tagService: TagService,
    scrollService: ScrollService,
    shopService: ShopService,
    customTitleService: CustomTitleService,
    clickableMessageService: ClickableMessageService,
    menuService: MenuService,
    messageService: MessageService,
    playerNameService: PlayerNameService,
) : CommandExecutor, TabCompleter {
    private val context = TagsCommandContext(
        plugin = plugin,
        tagService = tagService,
        scrollService = scrollService,
        shopService = shopService,
        customTitleService = customTitleService,
        clickableMessageService = clickableMessageService,
        menuService = menuService,
        messageService = messageService,
        playerNameService = playerNameService,
    )
    private val customCommand = TagsCustomCommand(context)
    private val adminCommand = TagsAdminCommand(context)
    private val tabCompleter = TagsTabCompleter(context)

    override fun onCommand(sender: CommandSender, command: Command, label: String, args: Array<out String>): Boolean {
        if (args.isEmpty()) {
            return openWarehouse(sender)
        }
        return when (args[0].lowercase(Locale.ROOT)) {
            "help", "?" -> {
                TagsHelpRenderer.send(sender)
                true
            }
            "reload" -> handleReload(sender)
            "equip" -> handleEquip(sender, args)
            "unequip" -> handleUnequip(sender)
            "upgrade" -> handleUpgrade(sender, args)
            "shop" -> handleShop(sender)
            "custom" -> customCommand.handle(sender, args)
            "create" -> handleQuickCreate(sender, args)
            "admin" -> adminCommand.handle(sender, args)
            else -> {
                if (!context.requireUse(sender)) {
                    return true
                }
                TagsHelpRenderer.send(sender)
                true
            }
        }
    }

    override fun onTabComplete(sender: CommandSender, command: Command, alias: String, args: Array<out String>): List<String> {
        return tabCompleter.onTabComplete(sender, command, alias, args)
    }

    private fun openWarehouse(sender: CommandSender): Boolean {
        val player = sender as? Player ?: run {
            context.messageService.send(sender, "player-only")
            return true
        }
        if (!context.requireUse(sender)) {
            return true
        }
        context.menuService.openWarehouse(player, 0)
        return true
    }

    private fun handleReload(sender: CommandSender): Boolean {
        if (!context.requirePermission(sender, AdminAccess.RELOAD)) {
            return true
        }
        context.plugin.reloadPlugin()
        context.messageService.send(sender, "reloaded")
        return true
    }

    private fun handleEquip(sender: CommandSender, args: Array<out String>): Boolean {
        val player = sender as? Player ?: run {
            context.messageService.send(sender, "player-only")
            return true
        }
        if (!context.requireUse(sender)) {
            return true
        }
        if (args.size < 2) {
            TagsHelpRenderer.send(sender)
            return true
        }
        val titleId = context.tagService.resolveTitleId(player, args[1]) ?: run {
            context.messageService.send(sender, "tag-not-found", args[1])
            return true
        }
        if (context.tagService.data(player).customTitles.containsKey(titleId)) {
            context.tagService.equipCustomTitle(player, titleId)
        } else {
            context.tagService.equipTag(player, titleId)
        }
        return true
    }

    private fun handleUnequip(sender: CommandSender): Boolean {
        val player = sender as? Player ?: run {
            context.messageService.send(sender, "player-only")
            return true
        }
        if (!context.requireUse(sender)) {
            return true
        }
        context.tagService.unequipTag(player)
        return true
    }

    private fun handleUpgrade(sender: CommandSender, args: Array<out String>): Boolean {
        val player = sender as? Player ?: run {
            context.messageService.send(sender, "player-only")
            return true
        }
        if (!context.requireUse(sender)) {
            return true
        }
        val input = args.getOrNull(1) ?: context.tagService.currentTagId(player)
        val titleId = context.tagService.resolveTitleId(player, input) ?: run {
            context.messageService.send(sender, "tag-not-found", input)
            return true
        }
        context.menuService.openUpgrade(player, titleId, 0)
        return true
    }

    private fun handleShop(sender: CommandSender): Boolean {
        val player = sender as? Player ?: run {
            context.messageService.send(sender, "player-only")
            return true
        }
        if (!context.requireUse(sender)) {
            return true
        }
        context.menuService.openShop(player, 0)
        return true
    }

    private fun handleQuickCreate(sender: CommandSender, args: Array<out String>): Boolean {
        if (!context.requirePermission(sender, AdminAccess.CREATE)) {
            return true
        }
        if (args.size < 2) {
            context.messageService.send(sender, "quick-create-usage")
            return true
        }
        val tagId = args[1]
        val permission = args.getOrNull(2)?.takeIf { it.isNotBlank() } ?: "uptags.tag.$tagId"
        val defaultGroup = context.plugin.config.firstUpgradeGroup()
        val buffGroup = args.getOrNull(3)?.takeIf { it.isNotBlank() } ?: defaultGroup
        val particleGroup = args.getOrNull(4)?.takeIf { it.isNotBlank() } ?: buffGroup ?: defaultGroup

        if (buffGroup == null || particleGroup == null) {
            context.messageService.send(sender, "quick-create-no-group")
            return true
        }

        val created = context.tagService.createTagQuick(
            tagId = tagId,
            permission = permission,
            buffGroup = buffGroup,
            particleGroup = particleGroup,
        )
        context.messageService.send(
            sender,
            if (created) "quick-create-success" else "quick-create-failed",
            tagId,
            permission,
            buffGroup,
            particleGroup,
        )
        return true
    }
}
