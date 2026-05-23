package cn.aing.uptags.command.admin

import cn.aing.uptags.command.core.TagsCommandContext

import cn.aing.uptags.Support
import cn.aing.uptags.command.core.TagsCommandConstants.BUFF_ALL_KEY
import cn.aing.uptags.command.core.TagsCommandConstants.PARTICLE_ALL_KEY
import cn.aing.uptags.model.runtime.ScrollKind
import cn.aing.uptags.service.tag.AdminActionResult
import org.bukkit.Bukkit
import org.bukkit.command.CommandSender
import org.bukkit.entity.Player
import java.util.Locale

internal class TagsAdminCommand(private val context: TagsCommandContext) {
    private val createWizard = AdminCreateWizard(context)

    fun handle(sender: CommandSender, args: Array<out String>): Boolean {
        if (!AdminAccess.hasAnyAdmin(sender)) {
            context.messageService.send(sender, "no-permission")
            return true
        }
        if (args.size < 2) {
            context.messageService.send(sender, "admin-help")
            return true
        }
        return when (args[1].lowercase(Locale.ROOT)) {
            "give" -> handleGive(sender, args)
            "take" -> handleTake(sender, args)
            "manage", "gui", "warehouse" -> handleManage(sender, args)
            "info" -> handleInfo(sender, args)
            "equip" -> handleEquip(sender, args)
            "unequip" -> handleUnequip(sender, args)
            "coin" -> handleCoin(sender, args)
            "buff" -> handleBuff(sender, args)
            "particle" -> handleParticle(sender, args)
            "custom" -> handleCustom(sender, args)
            "scroll" -> handleScroll(sender, args)
            "tag" -> handleTag(sender, args)
            "product" -> handleProduct(sender, args)
            "createwizard" -> handleCreateWizard(sender)
            "validate" -> handleValidate(sender)
            else -> {
                context.messageService.send(sender, "admin-help")
                true
            }
        }
    }

    private fun handleProduct(sender: CommandSender, args: Array<out String>): Boolean {
        if (!context.requirePermission(sender, AdminAccess.PRODUCT_CREATE, AdminAccess.PRODUCT_ALL)) {
            return true
        }
        if (args.size < 4 || !args[2].equals("create", true)) {
            sender.sendMessage(Support.color("&#FDE047用法: /tags admin product create <tagId>"))
            return true
        }
        val tagId = args[3]
        if (!context.plugin.config.tags.containsKey(tagId)) {
            context.messageService.send(sender, "tag-not-found", tagId)
            return true
        }
        if (context.plugin.config.createShopProductForTag(tagId)) {
            sender.sendMessage(Support.color("&#A7F3D0已为称号 '$tagId' 创建轻量商店商品，写入 shop.yml。"))
        } else {
            sender.sendMessage(Support.color("&#F87171创建商品失败，请查看控制台日志。"))
        }
        return true
    }

    private fun handleCreateWizard(sender: CommandSender): Boolean {
        val player = sender as? Player ?: run {
            context.messageService.send(sender, "player-only")
            return true
        }
        if (!context.requirePermission(sender, AdminAccess.CREATE_WIZARD, AdminAccess.TAG_CREATE, AdminAccess.TAG_ALL)) {
            return true
        }
        createWizard.start(player)
        return true
    }

    private fun handleValidate(sender: CommandSender): Boolean {
        if (!context.requirePermission(sender, AdminAccess.VALIDATE)) {
            return true
        }
        val issues = context.plugin.config.configurationIssues()
        if (issues.isEmpty()) {
            sender.sendMessage(Support.color("&#A7F3D0配置校验通过，未发现问题。"))
            return true
        }
        sender.sendMessage(Support.color("&#FDE047发现 ${issues.size} 个配置问题:"))
        issues.forEachIndexed { index, issue ->
            sender.sendMessage(Support.color("&#F87171${index + 1}. [${issue.severity}] ${issue.source} ${issue.path}"))
            sender.sendMessage(Support.color("&#94A3B8   ${issue.message}"))
        }
        return true
    }

    private fun handleGive(sender: CommandSender, args: Array<out String>): Boolean {
        if (!context.requirePermission(sender, AdminAccess.GIVE)) {
            return true
        }
        if (args.size < 4) {
            context.messageService.send(sender, "admin-help")
            return true
        }
        val target = context.resolveOfflinePlayer(args[2]) ?: run {
            context.messageService.send(sender, "invalid-target")
            return true
        }
        if (context.tagService.giveTag(target, args[3])) {
            context.messageService.send(sender, "tag-given", context.tagService.tagName(args[3]))
        } else {
            context.messageService.send(sender, "tag-already-owned", context.tagService.tagName(args[3]))
        }
        return true
    }

    private fun handleTake(sender: CommandSender, args: Array<out String>): Boolean {
        if (!context.requirePermission(sender, AdminAccess.TAKE)) {
            return true
        }
        if (args.size < 4) {
            context.messageService.send(sender, "admin-help")
            return true
        }
        val target = context.resolveOfflinePlayer(args[2]) ?: run {
            context.messageService.send(sender, "invalid-target")
            return true
        }
        if (context.tagService.takeTag(target, args[3])) {
            context.messageService.send(sender, "tag-taken", context.tagService.tagName(args[3]))
        } else {
            context.messageService.send(sender, "tag-not-found", args[3])
        }
        return true
    }

    private fun handleManage(sender: CommandSender, args: Array<out String>): Boolean {
        val admin = sender as? Player ?: run {
            context.messageService.send(sender, "player-only")
            return true
        }
        if (!context.requirePermission(sender, AdminAccess.MANAGE, AdminAccess.INFO)) {
            return true
        }
        if (args.size < 3) {
            context.messageService.send(sender, "admin-help")
            return true
        }
        val target = context.resolveOfflinePlayer(args[2]) ?: run {
            context.messageService.send(sender, "invalid-target")
            return true
        }
        context.menuService.openAdminWarehouse(admin, target, 0)
        return true
    }

    private fun handleInfo(sender: CommandSender, args: Array<out String>): Boolean {
        if (!context.requirePermission(sender, AdminAccess.INFO)) {
            return true
        }
        if (args.size < 3) {
            context.messageService.send(sender, "admin-help")
            return true
        }
        val target = context.resolveOfflinePlayer(args[2]) ?: run {
            context.messageService.send(sender, "invalid-target")
            return true
        }
        val data = context.tagService.data(target.uniqueId)
        val equipped = data.equippedCustomTitleId ?: data.equippedTagId ?: "none"
        val ownedTags = data.ownedTags.sorted().ifEmpty { listOf("none") }.joinToString(", ")
        val customTitles = data.customTitles.keys.sorted().ifEmpty { listOf("none") }.joinToString(", ")
        val buffs = data.tagProgress.flatMap { (titleId, progress) ->
            progress.buffLevels.entries
                .filter { it.value > 0 }
                .map { (buffId, level) ->
                    val active = if (buffId in progress.activeBuffs) "on" else "off"
                    "$titleId:$buffId=$level/$active"
                }
        }.ifEmpty { listOf("none") }.joinToString(", ")
        val particles = data.tagProgress.flatMap { (titleId, progress) ->
            progress.ownedParticles.map { particleId ->
                val selected = if (progress.selectedParticleId == particleId) "*" else ""
                "$titleId:$particleId$selected"
            }
        }.ifEmpty { listOf("none") }.joinToString(", ")
        context.messageService.send(sender, "admin-info-header", context.targetLabel(target))
        context.messageService.send(sender, "admin-info-equipped", equipped)
        context.messageService.send(sender, "admin-info-owned", ownedTags)
        context.messageService.send(sender, "admin-info-custom", customTitles)
        context.messageService.send(sender, "admin-info-coins", Support.formatDouble(data.titleCoinBalance))
        context.messageService.send(sender, "admin-info-buffs", buffs)
        context.messageService.send(sender, "admin-info-particles", particles)
        return true
    }

    private fun handleEquip(sender: CommandSender, args: Array<out String>): Boolean {
        if (!context.requirePermission(sender, AdminAccess.EQUIP)) {
            return true
        }
        if (args.size < 4) {
            context.messageService.send(sender, "admin-help")
            return true
        }
        val target = context.resolveOfflinePlayer(args[2]) ?: run {
            context.messageService.send(sender, "invalid-target")
            return true
        }
        context.sendAdminResult(sender, context.tagService.adminEquipTitle(target.uniqueId, args[3]))
        return true
    }

    private fun handleUnequip(sender: CommandSender, args: Array<out String>): Boolean {
        if (!context.requirePermission(sender, AdminAccess.UNEQUIP)) {
            return true
        }
        if (args.size < 3) {
            context.messageService.send(sender, "admin-help")
            return true
        }
        val target = context.resolveOfflinePlayer(args[2]) ?: run {
            context.messageService.send(sender, "invalid-target")
            return true
        }
        context.sendAdminResult(sender, context.tagService.adminUnequipTitle(target.uniqueId))
        return true
    }

    private fun handleCoin(sender: CommandSender, args: Array<out String>): Boolean {
        if (args.size < 5) {
            context.messageService.send(sender, "admin-coin-help")
            return true
        }
        val action = args[2].lowercase(Locale.ROOT)
        val permission = when (action) {
            "give" -> AdminAccess.COIN_ADD
            "take" -> AdminAccess.COIN_TAKE
            "set" -> AdminAccess.COIN_SET
            else -> {
                context.messageService.send(sender, "admin-coin-help")
                return true
            }
        }
        if (!context.requirePermission(sender, permission, AdminAccess.COIN_ALL)) {
            return true
        }
        val target = context.resolveOfflinePlayer(args[3]) ?: run {
            context.messageService.send(sender, "invalid-target")
            return true
        }
        val amount = args[4].toDoubleOrNull()?.takeIf { it >= 0.0 } ?: run {
            context.messageService.send(sender, "admin-invalid-amount")
            return true
        }
        val balance = when (action) {
            "give" -> context.customTitleService.addTitleCoins(target.uniqueId, amount)
            "take" -> context.customTitleService.takeTitleCoins(target.uniqueId, amount)
            "set" -> context.customTitleService.setTitleCoins(target.uniqueId, amount)
            else -> null
        }
        if (balance == null) {
            context.messageService.send(sender, "admin-coin-insufficient", context.targetLabel(target))
        } else {
            context.messageService.send(sender, "admin-coin-success", context.targetLabel(target), Support.formatDouble(balance))
        }
        return true
    }

    private fun handleBuff(sender: CommandSender, args: Array<out String>): Boolean {
        if (args.size < 6) {
            context.messageService.send(sender, "admin-buff-help")
            return true
        }
        val action = args[2].lowercase(Locale.ROOT)
        val permission = when (action) {
            "set" -> AdminAccess.BUFF_SET
            "enable" -> AdminAccess.BUFF_ENABLE
            "disable" -> AdminAccess.BUFF_DISABLE
            "detach" -> AdminAccess.BUFF_DETACH
            else -> {
                context.messageService.send(sender, "admin-buff-help")
                return true
            }
        }
        if (!context.requirePermission(sender, permission, AdminAccess.BUFF_ALL)) {
            return true
        }
        val target = context.resolveOfflinePlayer(args[3]) ?: run {
            context.messageService.send(sender, "invalid-target")
            return true
        }
        val result = when (action) {
            "set" -> {
                val level = args.getOrNull(6)?.toIntOrNull() ?: 1
                context.tagService.adminSetBuffLevel(target.uniqueId, args[4], args[5], level)
            }
            "enable" -> context.tagService.adminSetBuffEnabled(target.uniqueId, args[4], args[5], enabled = true)
            "disable" -> context.tagService.adminSetBuffEnabled(target.uniqueId, args[4], args[5], enabled = false)
            "detach" -> {
                val player = sender as? Player ?: run {
                    context.messageService.send(sender, "admin-detach-player-only")
                    return true
                }
                context.tagService.adminDetachBuff(target.uniqueId, args[4], args[5], player)
            }
            else -> AdminActionResult(false, "admin-buff-help")
        }
        context.sendAdminResult(sender, result)
        return true
    }

    private fun handleParticle(sender: CommandSender, args: Array<out String>): Boolean {
        if (args.size < 5) {
            context.messageService.send(sender, "admin-particle-help")
            return true
        }
        val action = args[2].lowercase(Locale.ROOT)
        val permission = when (action) {
            "give" -> AdminAccess.PARTICLE_GIVE
            "take" -> AdminAccess.PARTICLE_TAKE
            "select" -> AdminAccess.PARTICLE_SELECT
            "clear" -> AdminAccess.PARTICLE_CLEAR
            "detach" -> AdminAccess.PARTICLE_DETACH
            else -> {
                context.messageService.send(sender, "admin-particle-help")
                return true
            }
        }
        if (!context.requirePermission(sender, permission, AdminAccess.PARTICLE_ALL)) {
            return true
        }
        val target = context.resolveOfflinePlayer(args[3]) ?: run {
            context.messageService.send(sender, "invalid-target")
            return true
        }
        if (action != "clear" && args.size < 6) {
            context.messageService.send(sender, "admin-particle-help")
            return true
        }
        val result = when (action) {
            "give" -> context.tagService.adminGiveParticle(target.uniqueId, args[4], args[5])
            "take" -> context.tagService.adminTakeParticle(target.uniqueId, args[4], args[5])
            "select" -> context.tagService.adminSelectParticle(target.uniqueId, args[4], args[5])
            "clear" -> context.tagService.adminClearParticle(target.uniqueId, args[4])
            "detach" -> {
                val player = sender as? Player ?: run {
                    context.messageService.send(sender, "admin-detach-player-only")
                    return true
                }
                context.tagService.adminDetachParticle(target.uniqueId, args[4], args[5], player)
            }
            else -> AdminActionResult(false, "admin-particle-help")
        }
        context.sendAdminResult(sender, result)
        return true
    }

    private fun handleCustom(sender: CommandSender, args: Array<out String>): Boolean {
        if (args.size < 4) {
            context.messageService.send(sender, "admin-custom-help")
            return true
        }
        val action = args[2].lowercase(Locale.ROOT)
        val permission = when (action) {
            "list" -> AdminAccess.CUSTOM_LIST
            "equip" -> AdminAccess.CUSTOM_EQUIP
            "delete" -> AdminAccess.CUSTOM_DELETE
            else -> {
                context.messageService.send(sender, "admin-custom-help")
                return true
            }
        }
        if (!context.requirePermission(sender, permission, AdminAccess.CUSTOM_ALL)) {
            return true
        }
        val target = context.resolveOfflinePlayer(args[3]) ?: run {
            context.messageService.send(sender, "invalid-target")
            return true
        }
        when (action) {
            "list" -> {
                val values = context.tagService.data(target.uniqueId).customTitles.values
                    .sortedBy { it.createdAt }
                    .map { "${it.id}:${Support.stripColor(context.tagService.renderCustomTitle(it))}" }
                    .ifEmpty { listOf("none") }
                    .joinToString(", ")
                context.messageService.send(sender, "admin-custom-list", context.targetLabel(target), values)
            }
            "equip" -> {
                if (args.size < 5) {
                    context.messageService.send(sender, "admin-custom-help")
                    return true
                }
                context.sendAdminResult(sender, context.tagService.adminEquipCustomTitle(target.uniqueId, args[4]))
            }
            "delete" -> {
                if (args.size < 5) {
                    context.messageService.send(sender, "admin-custom-help")
                    return true
                }
                context.sendAdminResult(sender, context.tagService.adminDeleteCustomTitle(target.uniqueId, args[4]))
            }
        }
        return true
    }

    private fun handleScroll(sender: CommandSender, args: Array<out String>): Boolean {
        if (!context.requirePermission(sender, AdminAccess.SCROLL_GIVE)) {
            return true
        }
        if (args.size < 5 || !args[2].equals("give", true)) {
            context.messageService.send(sender, "scroll-help")
            return true
        }
        val target = Bukkit.getPlayerExact(args[3]) ?: run {
            context.messageService.send(sender, "invalid-target")
            return true
        }
        val scrollKey = args[4]
        val amount = args.getOrNull(5)?.toIntOrNull()?.coerceAtLeast(1) ?: 1

        when {
            scrollKey.equals(BUFF_ALL_KEY, true) -> return giveScrollBundle(sender, target, ScrollKind.BUFF, amount)
            scrollKey.equals(PARTICLE_ALL_KEY, true) -> return giveScrollBundle(sender, target, ScrollKind.PARTICLE, amount)
        }

        if (!context.scrollService.isValidScrollKey(scrollKey)) {
            context.messageService.send(sender, "scroll-invalid-definition", "scroll", scrollKey)
            return true
        }
        target.inventory.addItem(context.scrollService.createScroll(scrollKey, amount))
        val definition = context.plugin.config.scrolls[scrollKey] ?: return true
        context.messageService.send(
            sender,
            "scroll-given",
            target.name,
            if (definition.kind == ScrollKind.BUFF) "Buff" else "粒子",
            context.scrollService.displayName(definition.kind, definition.targetId),
            amount,
        )
        return true
    }

    private fun giveScrollBundle(sender: CommandSender, target: Player, kind: ScrollKind, amount: Int): Boolean {
        val definitions = context.plugin.config.scrolls.values.filter { it.kind == kind }
        if (definitions.isEmpty()) {
            context.messageService.send(sender, "scroll-invalid-definition", kind.name, if (kind == ScrollKind.BUFF) BUFF_ALL_KEY else PARTICLE_ALL_KEY)
            return true
        }
        definitions.forEach { definition ->
            target.inventory.addItem(context.scrollService.createScroll(definition.key, amount))
        }
        val targetName = if (kind == ScrollKind.BUFF) "全 Buff 包" else "全粒子包"
        context.messageService.send(sender, "scroll-given", target.name, if (kind == ScrollKind.BUFF) "Buff" else "粒子", targetName, definitions.size * amount)
        return true
    }

    private fun handleTag(sender: CommandSender, args: Array<out String>): Boolean {
        if (args.size < 4) {
            context.messageService.send(sender, "admin-help")
            return true
        }
        return when (args[2].lowercase(Locale.ROOT)) {
            "create" -> {
                if (!context.requirePermission(sender, AdminAccess.TAG_CREATE, AdminAccess.TAG_ALL)) {
                    return true
                }
                if (context.tagService.createTag(args[3])) {
                    context.messageService.send(sender, "tag-created", args[3])
                } else {
                    context.messageService.send(sender, "tag-updated")
                }
                true
            }
            "delete" -> {
                if (!context.requirePermission(sender, AdminAccess.TAG_DELETE, AdminAccess.TAG_ALL)) {
                    return true
                }
                if (context.tagService.deleteTag(args[3])) {
                    context.messageService.send(sender, "tag-deleted", args[3])
                } else {
                    context.messageService.send(sender, "tag-not-found", args[3])
                }
                true
            }
            "setdisplay" -> {
                if (!context.requirePermission(sender, AdminAccess.TAG_SET_DISPLAY, AdminAccess.TAG_ALL, AdminAccess.TAG_EDIT)) {
                    return true
                }
                if (args.size < 5) return true
                val display = args.copyOfRange(4, args.size).joinToString(" ")
                if (context.tagService.updateTagDisplay(args[3], display)) {
                    context.messageService.send(sender, "tag-updated")
                } else {
                    context.messageService.send(sender, "tag-not-found", args[3])
                }
                true
            }
            "setrarity" -> {
                if (!context.requirePermission(sender, AdminAccess.TAG_SET_RARITY, AdminAccess.TAG_ALL, AdminAccess.TAG_EDIT)) {
                    return true
                }
                if (args.size < 5) return true
                if (context.tagService.updateTagRarity(args[3], args[4])) {
                    context.messageService.send(sender, "tag-updated")
                } else {
                    context.messageService.send(sender, "tag-not-found", args[3])
                }
                true
            }
            "setgroups" -> {
                if (!context.requirePermission(sender, AdminAccess.TAG_SET_GROUPS, AdminAccess.TAG_ALL, AdminAccess.TAG_EDIT)) {
                    return true
                }
                if (args.size < 5) return true
                val groups = args[4].split(',')
                if (context.tagService.updateTagGroups(args[3], groups)) {
                    context.messageService.send(sender, "tag-updated")
                } else {
                    context.messageService.send(sender, "tag-not-found", args[3])
                }
                true
            }
            "setdefault" -> {
                if (!context.requirePermission(sender, AdminAccess.TAG_SET_DEFAULT, AdminAccess.TAG_ALL, AdminAccess.TAG_EDIT)) {
                    return true
                }
                if (args.size < 5) return true
                val value = args[4].toBooleanStrictOrNull() ?: false
                if (context.tagService.updateTagDefaultUnlocked(args[3], value)) {
                    context.messageService.send(sender, "tag-updated")
                } else {
                    context.messageService.send(sender, "tag-not-found", args[3])
                }
                true
            }
            else -> {
                context.messageService.send(sender, "admin-help")
                true
            }
        }
    }
}
