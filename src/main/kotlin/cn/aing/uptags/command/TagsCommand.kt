package cn.aing.uptags.command

import cn.aing.uptags.UpTagsPlugin
import cn.aing.uptags.config.MessageService
import cn.aing.uptags.gui.MenuService
import cn.aing.uptags.model.runtime.ScrollKind
import cn.aing.uptags.service.ClickableMessageService
import cn.aing.uptags.service.CustomTitleService
import cn.aing.uptags.service.ScrollService
import cn.aing.uptags.service.ShopService
import cn.aing.uptags.service.TagService
import org.bukkit.Bukkit
import org.bukkit.OfflinePlayer
import org.bukkit.command.Command
import org.bukkit.command.CommandExecutor
import org.bukkit.command.CommandSender
import org.bukkit.command.TabCompleter
import org.bukkit.entity.Player
import java.util.Locale
import java.util.UUID

class TagsCommand(
    private val plugin: UpTagsPlugin,
    private val tagService: TagService,
    private val scrollService: ScrollService,
    private val shopService: ShopService,
    private val customTitleService: CustomTitleService,
    private val clickableMessageService: ClickableMessageService,
    private val menuService: MenuService,
    private val messageService: MessageService,
) : CommandExecutor, TabCompleter {
    private companion object {
        const val BUFF_ALL_KEY = "buff_all"
        const val PARTICLE_ALL_KEY = "particle_all"
    }

    override fun onCommand(sender: CommandSender, command: Command, label: String, args: Array<out String>): Boolean {
        if (args.isEmpty()) {
            val player = sender as? Player ?: run {
                messageService.send(sender, "player-only")
                return true
            }
            menuService.openWarehouse(player, 0)
            return true
        }
        return when (args[0].lowercase(Locale.ROOT)) {
            "reload" -> handleReload(sender)
            "equip" -> handleEquip(sender, args)
            "unequip" -> handleUnequip(sender)
            "upgrade" -> handleUpgrade(sender, args)
            "shop" -> handleShop(sender)
            "custom" -> handleCustom(sender, args)
            "create" -> handleQuickCreate(sender, args)
            "admin" -> handleAdmin(sender, args)
            else -> {
                messageService.send(sender, "help")
                true
            }
        }
    }

    private fun handleReload(sender: CommandSender): Boolean {
        if (!sender.hasPermission("uptags.reload")) {
            messageService.send(sender, "no-permission")
            return true
        }
        plugin.reloadPlugin()
        messageService.send(sender, "reloaded")
        return true
    }

    private fun handleEquip(sender: CommandSender, args: Array<out String>): Boolean {
        val player = sender as? Player ?: run {
            messageService.send(sender, "player-only")
            return true
        }
        if (args.size < 2) {
            messageService.send(sender, "help")
            return true
        }
        tagService.equipTag(player, args[1])
        return true
    }

    private fun handleUnequip(sender: CommandSender): Boolean {
        val player = sender as? Player ?: run {
            messageService.send(sender, "player-only")
            return true
        }
        tagService.unequipTag(player)
        return true
    }

    private fun handleUpgrade(sender: CommandSender, args: Array<out String>): Boolean {
        val player = sender as? Player ?: run {
            messageService.send(sender, "player-only")
            return true
        }
        val input = args.getOrNull(1) ?: tagService.currentTagId(player)
        val definition = tagService.resolveTag(input) ?: run {
            messageService.send(sender, "tag-not-found", input)
            return true
        }
        menuService.openUpgrade(player, definition.id, 0)
        return true
    }

    private fun handleShop(sender: CommandSender): Boolean {
        val player = sender as? Player ?: run {
            messageService.send(sender, "player-only")
            return true
        }
        menuService.openShop(player, 0)
        return true
    }

    private fun handleCustom(sender: CommandSender, args: Array<out String>): Boolean {
        val player = sender as? Player ?: run {
            messageService.send(sender, "player-only")
            return true
        }
        if (args.size < 3 || !args[1].equals("preview", true)) {
            messageService.send(sender, "custom-title-preview-help")
            return true
        }
        when (args[2].lowercase(Locale.ROOT)) {
            "prev" -> clickableMessageService.sendPreviewControls(player, customTitleService.cycleScheme(player, -1))
            "next" -> clickableMessageService.sendPreviewControls(player, customTitleService.cycleScheme(player, 1))
            "reroll" -> clickableMessageService.sendPreviewControls(player, customTitleService.rerollSchemes(player))
            "confirm" -> {
                val preview = customTitleService.confirm(player)
                if (preview != null) {
                    messageService.send(player, "custom-title-confirmed", preview)
                } else {
                    messageService.send(player, "custom-title-no-session")
                }
            }
            "cancel" -> customTitleService.cancelDraft(player)
            else -> messageService.send(sender, "custom-title-preview-help")
        }
        return true
    }

    private fun handleQuickCreate(sender: CommandSender, args: Array<out String>): Boolean {
        if (!sender.hasPermission("uptags.admin")) {
            messageService.send(sender, "no-permission")
            return true
        }
        if (args.size < 2) {
            messageService.send(sender, "quick-create-usage")
            return true
        }
        val tagId = args[1]
        val permission = args.getOrNull(2)?.takeIf { it.isNotBlank() } ?: "uptags.tag.$tagId"
        val defaultGroup = plugin.config.firstUpgradeGroup()
        val buffGroup = args.getOrNull(3)?.takeIf { it.isNotBlank() } ?: defaultGroup
        val particleGroup = args.getOrNull(4)?.takeIf { it.isNotBlank() } ?: buffGroup ?: defaultGroup

        if (buffGroup == null || particleGroup == null) {
            messageService.send(sender, "quick-create-no-group")
            return true
        }

        val created = tagService.createTagQuick(
            tagId = tagId,
            permission = permission,
            buffGroup = buffGroup,
            particleGroup = particleGroup,
        )
        messageService.send(
            sender,
            if (created) "quick-create-success" else "quick-create-failed",
            tagId,
            permission,
            buffGroup,
            particleGroup,
        )
        return true
    }

    private fun handleAdmin(sender: CommandSender, args: Array<out String>): Boolean {
        if (!sender.hasPermission("uptags.admin")) {
            messageService.send(sender, "no-permission")
            return true
        }
        if (args.size < 2) {
            messageService.send(sender, "admin-help")
            return true
        }
        return when (args[1].lowercase(Locale.ROOT)) {
            "give" -> {
                if (args.size < 4) {
                    messageService.send(sender, "admin-help")
                    return true
                }
                val target = resolveOfflinePlayer(args[2]) ?: run {
                    messageService.send(sender, "invalid-target")
                    return true
                }
                if (tagService.giveTag(target, args[3])) messageService.send(sender, "tag-given", tagService.tagName(args[3])) else messageService.send(sender, "tag-already-owned", tagService.tagName(args[3]))
                true
            }
            "take" -> {
                if (args.size < 4) {
                    messageService.send(sender, "admin-help")
                    return true
                }
                val target = resolveOfflinePlayer(args[2]) ?: run {
                    messageService.send(sender, "invalid-target")
                    return true
                }
                if (tagService.takeTag(target, args[3])) messageService.send(sender, "tag-taken", tagService.tagName(args[3])) else messageService.send(sender, "tag-not-found", args[3])
                true
            }
            "scroll" -> handleAdminScroll(sender, args)
            "tag" -> handleAdminTag(sender, args)
            else -> {
                messageService.send(sender, "admin-help")
                true
            }
        }
    }

    private fun handleAdminScroll(sender: CommandSender, args: Array<out String>): Boolean {
        if (args.size < 5 || !args[2].equals("give", true)) {
            messageService.send(sender, "scroll-help")
            return true
        }
        val target = Bukkit.getPlayerExact(args[3]) ?: run {
            messageService.send(sender, "invalid-target")
            return true
        }
        val scrollKey = args[4]
        val amount = args.getOrNull(5)?.toIntOrNull()?.coerceAtLeast(1) ?: 1

        when {
            scrollKey.equals(BUFF_ALL_KEY, true) -> return giveScrollBundle(sender, target, ScrollKind.BUFF, amount)
            scrollKey.equals(PARTICLE_ALL_KEY, true) -> return giveScrollBundle(sender, target, ScrollKind.PARTICLE, amount)
        }

        if (!scrollService.isValidScrollKey(scrollKey)) {
            messageService.send(sender, "scroll-invalid-definition", "scroll", scrollKey)
            return true
        }
        target.inventory.addItem(scrollService.createScroll(scrollKey, amount))
        val definition = plugin.config.scrolls[scrollKey] ?: return true
        messageService.send(sender, "scroll-given", target.name, if (definition.kind == ScrollKind.BUFF) "Buff" else "粒子", scrollService.displayName(definition.kind, definition.targetId), amount)
        return true
    }

    private fun giveScrollBundle(sender: CommandSender, target: Player, kind: ScrollKind, amount: Int): Boolean {
        val definitions = plugin.config.scrolls.values.filter { it.kind == kind }
        if (definitions.isEmpty()) {
            messageService.send(sender, "scroll-invalid-definition", kind.name, if (kind == ScrollKind.BUFF) BUFF_ALL_KEY else PARTICLE_ALL_KEY)
            return true
        }
        definitions.forEach { definition ->
            target.inventory.addItem(scrollService.createScroll(definition.key, amount))
        }
        val targetName = if (kind == ScrollKind.BUFF) "全 Buff 包" else "全粒子包"
        messageService.send(sender, "scroll-given", target.name, if (kind == ScrollKind.BUFF) "Buff" else "粒子", targetName, definitions.size * amount)
        return true
    }

    private fun handleAdminTag(sender: CommandSender, args: Array<out String>): Boolean {
        if (args.size < 4) {
            messageService.send(sender, "admin-help")
            return true
        }
        return when (args[2].lowercase(Locale.ROOT)) {
            "create" -> {
                if (tagService.createTag(args[3])) messageService.send(sender, "tag-created", args[3]) else messageService.send(sender, "tag-updated")
                true
            }
            "delete" -> {
                if (tagService.deleteTag(args[3])) messageService.send(sender, "tag-deleted", args[3]) else messageService.send(sender, "tag-not-found", args[3])
                true
            }
            "setdisplay" -> {
                if (args.size < 5) return true
                val display = args.copyOfRange(4, args.size).joinToString(" ")
                if (tagService.updateTagDisplay(args[3], display)) messageService.send(sender, "tag-updated") else messageService.send(sender, "tag-not-found", args[3])
                true
            }
            "setrarity" -> {
                if (args.size < 5) return true
                if (tagService.updateTagRarity(args[3], args[4])) messageService.send(sender, "tag-updated") else messageService.send(sender, "tag-not-found", args[3])
                true
            }
            "setgroups" -> {
                if (args.size < 5) return true
                val groups = args[4].split(',')
                if (tagService.updateTagGroups(args[3], groups)) messageService.send(sender, "tag-updated") else messageService.send(sender, "tag-not-found", args[3])
                true
            }
            "setdefault" -> {
                if (args.size < 5) return true
                val value = args[4].toBooleanStrictOrNull() ?: false
                if (tagService.updateTagDefaultUnlocked(args[3], value)) messageService.send(sender, "tag-updated") else messageService.send(sender, "tag-not-found", args[3])
                true
            }
            else -> {
                messageService.send(sender, "admin-help")
                true
            }
        }
    }

    override fun onTabComplete(sender: CommandSender, command: Command, alias: String, args: Array<out String>): List<String> {
        return when (args.size) {
            1 -> filter(listOf("reload", "equip", "unequip", "upgrade", "shop", "custom", "create", "admin"), args[0])
            2 -> when {
                args[0].equals("admin", true) -> filter(listOf("give", "take", "scroll", "tag"), args[1])
                args[0].equals("custom", true) -> filter(listOf("preview"), args[1])
                args[0].equals("equip", true) || args[0].equals("upgrade", true) -> filter(plugin.config.tags.keys.toList(), args[1])
                else -> emptyList()
            }
            3 -> when {
                args[0].equals("create", true) -> filter(listOf("uptags.tag.${args[1]}"), args[2])
                args[0].equals("custom", true) && args[1].equals("preview", true) -> filter(listOf("prev", "next", "reroll", "confirm", "cancel"), args[2])
                args[0].equals("admin", true) && (args[1].equals("give", true) || args[1].equals("take", true)) -> filter(Bukkit.getOnlinePlayers().map(Player::getName), args[2])
                args[0].equals("admin", true) && args[1].equals("scroll", true) -> filter(listOf("give"), args[2])
                args[0].equals("admin", true) && args[1].equals("tag", true) -> filter(listOf("create", "delete", "setdisplay", "setrarity", "setgroups", "setdefault"), args[2])
                else -> emptyList()
            }
            4 -> when {
                args[0].equals("create", true) -> filter(plugin.config.allUpgradeGroups(), args[3])
                args[0].equals("admin", true) && (args[1].equals("give", true) || args[1].equals("take", true)) -> filter(plugin.config.tags.keys.toList(), args[3])
                args[0].equals("admin", true) && args[1].equals("scroll", true) && args[2].equals("give", true) -> filter(Bukkit.getOnlinePlayers().map(Player::getName), args[3])
                args[0].equals("admin", true) && args[1].equals("tag", true) && args[2].equals("delete", true) -> filter(plugin.config.tags.keys.toList(), args[3])
                args[0].equals("admin", true) && args[1].equals("tag", true) && args[2].startsWith("set") -> filter(plugin.config.tags.keys.toList(), args[3])
                else -> emptyList()
            }
            5 -> when {
                args[0].equals("create", true) -> filter(plugin.config.allUpgradeGroups(), args[4])
                args[0].equals("admin", true) && args[1].equals("scroll", true) && args[2].equals("give", true) -> filter(plugin.config.scrolls.keys.toList() + listOf(BUFF_ALL_KEY, PARTICLE_ALL_KEY), args[4])
                args[0].equals("admin", true) && args[1].equals("tag", true) && args[2].equals("setrarity", true) -> filter(plugin.config.allRarities(), args[4])
                args[0].equals("admin", true) && args[1].equals("tag", true) && args[2].equals("setdefault", true) -> filter(listOf("true", "false"), args[4])
                else -> emptyList()
            }
            6 -> if (args[0].equals("admin", true) && args[1].equals("scroll", true) && args[2].equals("give", true)) filter(listOf("1", "2", "4", "8", "16"), args[5]) else emptyList()
            else -> emptyList()
        }
    }

    private fun filter(values: List<String>, input: String): List<String> = values.filter { it.lowercase(Locale.ROOT).startsWith(input.lowercase(Locale.ROOT)) }

    private fun resolveOfflinePlayer(input: String): OfflinePlayer? {
        Bukkit.getPlayerExact(input)?.let { return it }
        return runCatching { UUID.fromString(input) }
            .map { Bukkit.getOfflinePlayer(it) }
            .getOrNull()
            ?: Bukkit.getOfflinePlayers().firstOrNull { it.name.equals(input, ignoreCase = true) }
    }
}
