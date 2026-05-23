package cn.aing.uptags.command.core

import cn.aing.uptags.command.admin.AdminAccess

import cn.aing.uptags.command.core.TagsCommandConstants.BUFF_ALL_KEY
import cn.aing.uptags.command.core.TagsCommandConstants.PARTICLE_ALL_KEY
import org.bukkit.Bukkit
import org.bukkit.command.Command
import org.bukkit.command.CommandSender
import org.bukkit.command.TabCompleter
import org.bukkit.entity.Player

internal class TagsTabCompleter(private val context: TagsCommandContext) : TabCompleter {
    override fun onTabComplete(sender: CommandSender, command: Command, alias: String, args: Array<out String>): List<String> {
        return when (args.size) {
            1 -> context.filter(topLevelCompletions(sender), args[0])
            2 -> secondArgument(sender, args)
            3 -> thirdArgument(sender, args)
            4 -> fourthArgument(sender, args)
            5 -> fifthArgument(sender, args)
            6 -> sixthArgument(args)
            7 -> if (args[0].equals("admin", true) && args[1].equals("buff", true) && args[2].equals("set", true)) {
                context.filter(listOf("0", "1", "2", "3", "4", "5"), args[6])
            } else {
                emptyList()
            }
            else -> emptyList()
        }
    }

    private fun secondArgument(sender: CommandSender, args: Array<out String>): List<String> {
        return when {
            args[0].equals("admin", true) -> context.filter(adminSubcommands(sender), args[1])
            args[0].equals("custom", true) && AdminAccess.hasUse(sender) -> context.filter(listOf("preview"), args[1])
            AdminAccess.hasUse(sender) && (args[0].equals("equip", true) || args[0].equals("upgrade", true)) -> {
                val values = if (sender is Player) {
                    context.plugin.config.tags.keys.toList() + context.tagService.data(sender).customTitles.keys
                } else {
                    context.plugin.config.tags.keys.toList()
                }
                context.filter(values.distinct(), args[1])
            }
            else -> emptyList()
        }
    }

    private fun thirdArgument(sender: CommandSender, args: Array<out String>): List<String> {
        return when {
            args[0].equals("create", true) && AdminAccess.has(sender, AdminAccess.CREATE) -> context.filter(listOf("uptags.tag.${args[1]}"), args[2])
            args[0].equals("custom", true) && args[1].equals("preview", true) && AdminAccess.hasUse(sender) -> context.filter(customPreviewActions, args[2])
            args[0].equals("admin", true) && args[1] in targetAtThirdAdminCommands -> context.filter(context.onlinePlayerNames(), args[2])
            args[0].equals("admin", true) && args[1].equals("coin", true) -> context.filter(adminCoinActions(sender), args[2])
            args[0].equals("admin", true) && args[1].equals("buff", true) -> context.filter(adminBuffActions(sender), args[2])
            args[0].equals("admin", true) && args[1].equals("particle", true) -> context.filter(adminParticleActions(sender), args[2])
            args[0].equals("admin", true) && args[1].equals("custom", true) -> context.filter(adminCustomActions(sender), args[2])
            args[0].equals("admin", true) && args[1].equals("scroll", true) && AdminAccess.has(sender, AdminAccess.SCROLL_GIVE) -> context.filter(listOf("give"), args[2])
            args[0].equals("admin", true) && args[1].equals("tag", true) -> context.filter(adminTagActions(sender), args[2])
            args[0].equals("admin", true) && args[1].equals("product", true) -> context.filter(adminProductActions(sender), args[2])
            else -> emptyList()
        }
    }

    private fun fourthArgument(sender: CommandSender, args: Array<out String>): List<String> {
        return when {
            args[0].equals("create", true) && AdminAccess.has(sender, AdminAccess.CREATE) -> context.filter(context.plugin.config.allUpgradeGroups(), args[3])
            args[0].equals("admin", true) && (args[1].equals("give", true) || args[1].equals("take", true)) -> context.filter(context.plugin.config.tags.keys.toList(), args[3])
            args[0].equals("admin", true) && args[1].equals("equip", true) -> context.filter(context.titleIdsForTarget(args[2]), args[3])
            args[0].equals("admin", true) && args[1].equals("coin", true) -> context.filter(context.onlinePlayerNames(), args[3])
            args[0].equals("admin", true) && args[1] in targetAtFourthAdminCommands -> context.filter(context.onlinePlayerNames(), args[3])
            args[0].equals("admin", true) && args[1].equals("scroll", true) && args[2].equals("give", true) -> context.filter(Bukkit.getOnlinePlayers().map(Player::getName), args[3])
            args[0].equals("admin", true) && args[1].equals("tag", true) && args[2].equals("delete", true) -> context.filter(context.plugin.config.tags.keys.toList(), args[3])
            args[0].equals("admin", true) && args[1].equals("tag", true) && args[2].startsWith("set") -> context.filter(context.plugin.config.tags.keys.toList(), args[3])
            args[0].equals("admin", true) && args[1].equals("product", true) && args[2].equals("create", true) -> context.filter(context.plugin.config.tags.keys.toList(), args[3])
            else -> emptyList()
        }
    }

    private fun fifthArgument(sender: CommandSender, args: Array<out String>): List<String> {
        return when {
            args[0].equals("create", true) && AdminAccess.has(sender, AdminAccess.CREATE) -> context.filter(context.plugin.config.allUpgradeGroups(), args[4])
            args[0].equals("admin", true) && args[1].equals("coin", true) -> context.filter(listOf("1", "10", "100", "1000"), args[4])
            args[0].equals("admin", true) && (args[1].equals("buff", true) || args[1].equals("particle", true)) -> context.filter(context.titleIdsForTarget(args[3]), args[4])
            args[0].equals("admin", true) && args[1].equals("custom", true) && (args[2].equals("equip", true) || args[2].equals("delete", true)) -> context.filter(context.customTitleIdsForTarget(args[3]), args[4])
            args[0].equals("admin", true) && args[1].equals("custom", true) && (args[2].equals("refund", true) || args[2].equals("complete", true)) -> context.filter(context.customOrderIdsForTarget(args[3]), args[4])
            args[0].equals("admin", true) && args[1].equals("scroll", true) && args[2].equals("give", true) -> context.filter(context.plugin.config.scrolls.keys.toList() + listOf(BUFF_ALL_KEY, PARTICLE_ALL_KEY), args[4])
            args[0].equals("admin", true) && args[1].equals("tag", true) && args[2].equals("setrarity", true) -> context.filter(context.plugin.config.allRarities(), args[4])
            args[0].equals("admin", true) && args[1].equals("tag", true) && args[2].equals("setdefault", true) -> context.filter(listOf("true", "false"), args[4])
            else -> emptyList()
        }
    }

    private fun sixthArgument(args: Array<out String>): List<String> {
        return when {
            args[0].equals("admin", true) && args[1].equals("scroll", true) && args[2].equals("give", true) -> context.filter(listOf("1", "2", "4", "8", "16"), args[5])
            args[0].equals("admin", true) && args[1].equals("buff", true) -> context.filter(context.plugin.config.buffs.keys.toList(), args[5])
            args[0].equals("admin", true) && args[1].equals("particle", true) && !args[2].equals("clear", true) -> context.filter(context.plugin.config.particles.keys.toList(), args[5])
            else -> emptyList()
        }
    }

    private fun topLevelCompletions(sender: CommandSender): List<String> {
        val values = mutableListOf("help")
        if (AdminAccess.has(sender, AdminAccess.RELOAD)) values += "reload"
        if (AdminAccess.hasUse(sender)) values += listOf("equip", "unequip", "upgrade", "shop", "collection", "custom")
        if (AdminAccess.has(sender, AdminAccess.CREATE)) values += "create"
        if (AdminAccess.hasAnyAdmin(sender)) values += "admin"
        return values
    }

    private fun adminSubcommands(sender: CommandSender): List<String> {
        val values = mutableListOf<String>()
        if (AdminAccess.has(sender, AdminAccess.GIVE)) values += "give"
        if (AdminAccess.has(sender, AdminAccess.TAKE)) values += "take"
        if (AdminAccess.has(sender, AdminAccess.MANAGE) || AdminAccess.has(sender, AdminAccess.INFO)) values += "manage"
        if (AdminAccess.has(sender, AdminAccess.INFO)) values += "info"
        if (AdminAccess.has(sender, AdminAccess.EQUIP)) values += "equip"
        if (AdminAccess.has(sender, AdminAccess.UNEQUIP)) values += "unequip"
        if (AdminAccess.has(sender, AdminAccess.COIN_ALL) || adminCoinActions(sender).isNotEmpty()) values += "coin"
        if (AdminAccess.has(sender, AdminAccess.BUFF_ALL) || adminBuffActions(sender).isNotEmpty()) values += "buff"
        if (AdminAccess.has(sender, AdminAccess.PARTICLE_ALL) || adminParticleActions(sender).isNotEmpty()) values += "particle"
        if (AdminAccess.has(sender, AdminAccess.CUSTOM_ALL) || adminCustomActions(sender).isNotEmpty()) values += "custom"
        if (AdminAccess.has(sender, AdminAccess.SCROLL_GIVE)) values += "scroll"
        if (AdminAccess.has(sender, AdminAccess.TAG_ALL) || adminTagActions(sender).isNotEmpty()) values += "tag"
        if (AdminAccess.has(sender, AdminAccess.PRODUCT_ALL) || adminProductActions(sender).isNotEmpty()) values += "product"
        if (AdminAccess.has(sender, AdminAccess.CREATE_WIZARD)) values += "createwizard"
        if (AdminAccess.has(sender, AdminAccess.VALIDATE)) values += "validate"
        return values
    }

    private fun adminCoinActions(sender: CommandSender): List<String> = listOfNotNull(
        "give".takeIf { AdminAccess.has(sender, AdminAccess.COIN_ADD, AdminAccess.COIN_ALL) },
        "take".takeIf { AdminAccess.has(sender, AdminAccess.COIN_TAKE, AdminAccess.COIN_ALL) },
        "set".takeIf { AdminAccess.has(sender, AdminAccess.COIN_SET, AdminAccess.COIN_ALL) },
    )

    private fun adminBuffActions(sender: CommandSender): List<String> = listOfNotNull(
        "set".takeIf { AdminAccess.has(sender, AdminAccess.BUFF_SET, AdminAccess.BUFF_ALL) },
        "enable".takeIf { AdminAccess.has(sender, AdminAccess.BUFF_ENABLE, AdminAccess.BUFF_ALL) },
        "disable".takeIf { AdminAccess.has(sender, AdminAccess.BUFF_DISABLE, AdminAccess.BUFF_ALL) },
        "detach".takeIf { AdminAccess.has(sender, AdminAccess.BUFF_DETACH, AdminAccess.BUFF_ALL) },
    )

    private fun adminParticleActions(sender: CommandSender): List<String> = listOfNotNull(
        "give".takeIf { AdminAccess.has(sender, AdminAccess.PARTICLE_GIVE, AdminAccess.PARTICLE_ALL) },
        "take".takeIf { AdminAccess.has(sender, AdminAccess.PARTICLE_TAKE, AdminAccess.PARTICLE_ALL) },
        "select".takeIf { AdminAccess.has(sender, AdminAccess.PARTICLE_SELECT, AdminAccess.PARTICLE_ALL) },
        "clear".takeIf { AdminAccess.has(sender, AdminAccess.PARTICLE_CLEAR, AdminAccess.PARTICLE_ALL) },
        "detach".takeIf { AdminAccess.has(sender, AdminAccess.PARTICLE_DETACH, AdminAccess.PARTICLE_ALL) },
    )

    private fun adminCustomActions(sender: CommandSender): List<String> = listOfNotNull(
        "list".takeIf { AdminAccess.has(sender, AdminAccess.CUSTOM_LIST, AdminAccess.CUSTOM_ALL) },
        "equip".takeIf { AdminAccess.has(sender, AdminAccess.CUSTOM_EQUIP, AdminAccess.CUSTOM_ALL) },
        "delete".takeIf { AdminAccess.has(sender, AdminAccess.CUSTOM_DELETE, AdminAccess.CUSTOM_ALL) },
        "orders".takeIf { AdminAccess.has(sender, AdminAccess.CUSTOM_ORDERS, AdminAccess.CUSTOM_ALL) },
        "refund".takeIf { AdminAccess.has(sender, AdminAccess.CUSTOM_REFUND, AdminAccess.CUSTOM_ALL) },
        "complete".takeIf { AdminAccess.has(sender, AdminAccess.CUSTOM_COMPLETE, AdminAccess.CUSTOM_ALL) },
    )

    private fun adminTagActions(sender: CommandSender): List<String> = listOfNotNull(
        "create".takeIf { AdminAccess.has(sender, AdminAccess.TAG_CREATE, AdminAccess.TAG_ALL) },
        "delete".takeIf { AdminAccess.has(sender, AdminAccess.TAG_DELETE, AdminAccess.TAG_ALL) },
        "setdisplay".takeIf { AdminAccess.has(sender, AdminAccess.TAG_SET_DISPLAY, AdminAccess.TAG_ALL, AdminAccess.TAG_EDIT) },
        "setrarity".takeIf { AdminAccess.has(sender, AdminAccess.TAG_SET_RARITY, AdminAccess.TAG_ALL, AdminAccess.TAG_EDIT) },
        "setgroups".takeIf { AdminAccess.has(sender, AdminAccess.TAG_SET_GROUPS, AdminAccess.TAG_ALL, AdminAccess.TAG_EDIT) },
        "setdefault".takeIf { AdminAccess.has(sender, AdminAccess.TAG_SET_DEFAULT, AdminAccess.TAG_ALL, AdminAccess.TAG_EDIT) },
    )

    private fun adminProductActions(sender: CommandSender): List<String> = listOfNotNull(
        "create".takeIf { AdminAccess.has(sender, AdminAccess.PRODUCT_CREATE, AdminAccess.PRODUCT_ALL) },
    )

    private companion object {
        val targetAtThirdAdminCommands = setOf("give", "take", "manage", "gui", "warehouse", "info", "equip", "unequip")
        val targetAtFourthAdminCommands = setOf("buff", "particle", "custom")
        val customPreviewActions = listOf(
            "money",
            "title_coin",
            "points",
            "manual",
            "manual_remove",
            "manual_clear",
            "manual_page_prev",
            "manual_page_next",
            "manual_done",
            "manual_back",
            "auto",
            "prev",
            "next",
            "confirm",
            "cancel",
        )
    }
}
