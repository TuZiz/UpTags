package cn.aing.uptags.gui

import cn.aing.uptags.Support
import cn.aing.uptags.command.AdminAccess
import cn.aing.uptags.config.ConfigRegistry
import cn.aing.uptags.config.MessageService
import cn.aing.uptags.model.config.CurrencyType
import cn.aing.uptags.model.config.GuiLayout
import cn.aing.uptags.model.config.TagDefinition
import cn.aing.uptags.model.runtime.ScrollSelectionContext
import cn.aing.uptags.service.AdminActionResult
import cn.aing.uptags.service.TagService
import org.bukkit.entity.Player
import java.util.UUID

internal class EffectMenuService(
    private val config: ConfigRegistry,
    private val tagService: TagService,
    private val messageService: MessageService,
    private val entryFactory: MenuEntryFactory,
    private val normalizedPage: (GuiLayout, Int, Int) -> Int,
    private val createSession: (
        MenuType,
        GuiLayout,
        Int,
        String?,
        ScrollSelectionContext?,
        String,
        UUID?,
        String?,
    ) -> MenuSession,
    private val fillStatic: (Player, GuiLayout, MenuSession, Int, Int) -> Unit,
) {
    fun openUpgrade(player: Player, tagId: String, page: Int) {
        val tag = upgradeViewTag(player, tagId) ?: run {
            messageService.send(player, "tag-not-found", tagId)
            return
        }
        val layout = config.upgradeLayout
        val entries = entryFactory.buildUpgradeEntries(player, tag)
        val currentPage = normalizedPage(layout, page, entries.size)
        val session = createSession(MenuType.UPGRADE, layout, currentPage, tagId, null, layout.title, null, null)
        fillStatic(player, layout, session, currentPage, entries.size)
        val slots = layout.entrySlots()
        val start = currentPage * slots.size
        for (offset in slots.indices) {
            val index = start + offset
            if (index >= entries.size) continue
            val entry = entries[index]
            val slot = slots[offset]
            session.inventory.setItem(slot, entry.item)
            session.actions[slot] = { click ->
                when (entry.kind) {
                    EntryKind.BUFF -> {
                        if (click.isLeftClick) {
                            tagService.upgradeBuff(player, tagId, entry.id)
                        } else if (click.isRightClick) {
                            tagService.toggleBuff(player, tagId, entry.id)
                        }
                        openUpgrade(player, tagId, currentPage)
                    }
                    EntryKind.PARTICLE -> {
                        if (click.isLeftClick) {
                            tagService.buyParticle(player, tagId, entry.id)
                        } else if (click.isRightClick) {
                            tagService.selectParticle(player, tagId, entry.id)
                        }
                        openUpgrade(player, tagId, currentPage)
                    }
                }
            }
        }
        player.openInventory(session.inventory)
    }

    fun openAdminUpgrade(admin: Player, targetId: UUID, targetName: String, tagId: String, page: Int) {
        val tag = upgradeViewTag(targetId, tagId) ?: run {
            messageService.send(admin, "tag-not-found", tagId)
            return
        }
        val layout = config.upgradeLayout
        val entries = entryFactory.buildAdminUpgradeEntries(targetId, targetName, tag)
        val currentPage = normalizedPage(layout, page, entries.size)
        val session = createSession(
            MenuType.UPGRADE,
            layout,
            currentPage,
            tagId,
            null,
            "&#60A5FA$targetName &#93C5FD称号强化",
            targetId,
            targetName,
        )
        fillStatic(admin, layout, session, currentPage, entries.size)
        val slots = layout.entrySlots()
        val start = currentPage * slots.size
        for (offset in slots.indices) {
            val index = start + offset
            if (index >= entries.size) continue
            val entry = entries[index]
            val slot = slots[offset]
            session.inventory.setItem(slot, entry.item)
            session.actions[slot] = action@{ click ->
                when (entry.kind) {
                    EntryKind.BUFF -> {
                        val currentLevel = tagService.buffLevel(targetId, tagId, entry.id)
                        val buff = config.buffs[entry.id] ?: return@action
                        if (click.isLeftClick) {
                            if (!requireAdminAction(admin, AdminAccess.BUFF_SET, AdminAccess.BUFF_ALL)) return@action
                            val nextLevel = (currentLevel + 1).coerceAtMost(buff.maxLevel)
                            dispatchAdminResult(admin, tagService.adminSetBuffLevel(targetId, tagId, entry.id, nextLevel))
                        } else if (click.isRightClick) {
                            val enabled = tagService.isBuffEnabled(targetId, tagId, entry.id)
                            val permission = if (enabled) AdminAccess.BUFF_DISABLE else AdminAccess.BUFF_ENABLE
                            if (!requireAdminAction(admin, permission, AdminAccess.BUFF_ALL)) return@action
                            dispatchAdminResult(admin, tagService.adminSetBuffEnabled(targetId, tagId, entry.id, !enabled))
                        }
                        openAdminUpgrade(admin, targetId, targetName, tagId, currentPage)
                    }
                    EntryKind.PARTICLE -> {
                        val owned = tagService.isParticleOwned(targetId, tagId, entry.id)
                        val selected = tagService.isParticleSelected(targetId, tagId, entry.id)
                        if (click.isLeftClick) {
                            if (!requireAdminAction(admin, AdminAccess.PARTICLE_GIVE, AdminAccess.PARTICLE_ALL)) return@action
                            val result = if (owned) {
                                AdminActionResult(true, "admin-operation-success", listOf("particle ${entry.id} already owned"))
                            } else {
                                tagService.adminGiveParticle(targetId, tagId, entry.id)
                            }
                            dispatchAdminResult(admin, result)
                        } else if (click.isRightClick) {
                            val permission = if (selected) AdminAccess.PARTICLE_CLEAR else AdminAccess.PARTICLE_SELECT
                            if (!requireAdminAction(admin, permission, AdminAccess.PARTICLE_ALL)) return@action
                            val result = if (selected) {
                                tagService.adminClearParticle(targetId, tagId)
                            } else {
                                tagService.adminSelectParticle(targetId, tagId, entry.id)
                            }
                            dispatchAdminResult(admin, result)
                        }
                        openAdminUpgrade(admin, targetId, targetName, tagId, currentPage)
                    }
                }
            }
        }
        admin.openInventory(session.inventory)
    }

    fun openDetach(player: Player, tagId: String, page: Int) {
        if (!config.detach.enabled) {
            messageService.send(player, "detach-disabled")
            return
        }
        val tag = upgradeViewTag(player, tagId) ?: run {
            messageService.send(player, "tag-not-found", tagId)
            return
        }
        val entries = entryFactory.buildDetachEntries(player, tag)
        if (entries.isEmpty()) {
            messageService.send(player, "detach-no-items")
            return
        }
        val layout = config.detachLayout
        val currentPage = normalizedPage(layout, page, entries.size)
        val session = createSession(MenuType.DETACH, layout, currentPage, tagId, null, layout.title, null, null)
        fillStatic(player, layout, session, currentPage, entries.size)
        val slots = layout.entrySlots()
        val start = currentPage * slots.size
        for (offset in slots.indices) {
            val index = start + offset
            if (index >= entries.size) continue
            val entry = entries[index]
            val slot = slots[offset]
            session.inventory.setItem(slot, entry.item)
            session.actions[slot] = { click ->
                when (entry.kind) {
                    EntryKind.BUFF -> {
                        if (click.isLeftClick) {
                            tagService.detachBuff(player, tagId, entry.id, CurrencyType.MONEY)
                        } else if (click.isRightClick) {
                            tagService.detachBuff(player, tagId, entry.id, CurrencyType.POINTS)
                        }
                    }
                    EntryKind.PARTICLE -> {
                        if (click.isLeftClick) {
                            tagService.detachParticle(player, tagId, entry.id, CurrencyType.MONEY)
                        } else if (click.isRightClick) {
                            tagService.detachParticle(player, tagId, entry.id, CurrencyType.POINTS)
                        }
                    }
                }
                if (entryFactory.buildDetachEntries(player, tag).isEmpty()) {
                    openUpgrade(player, tagId, 0)
                } else {
                    openDetach(player, tagId, currentPage)
                }
            }
        }
        player.openInventory(session.inventory)
    }

    fun openAdminDetach(admin: Player, targetId: UUID, targetName: String, tagId: String, page: Int) {
        val tag = upgradeViewTag(targetId, tagId) ?: run {
            messageService.send(admin, "tag-not-found", tagId)
            return
        }
        val entries = entryFactory.buildAdminDetachEntries(targetId, targetName, tag)
        if (entries.isEmpty()) {
            messageService.send(admin, "detach-no-items")
            openAdminUpgrade(admin, targetId, targetName, tagId, 0)
            return
        }
        val layout = config.detachLayout
        val currentPage = normalizedPage(layout, page, entries.size)
        val session = createSession(
            MenuType.DETACH,
            layout,
            currentPage,
            tagId,
            null,
            "&#60A5FA$targetName &#FDE68A效果拆卸",
            targetId,
            targetName,
        )
        fillStatic(admin, layout, session, currentPage, entries.size)
        val slots = layout.entrySlots()
        val start = currentPage * slots.size
        for (offset in slots.indices) {
            val index = start + offset
            if (index >= entries.size) continue
            val entry = entries[index]
            val slot = slots[offset]
            session.inventory.setItem(slot, entry.item)
            session.actions[slot] = action@{
                val result = when (entry.kind) {
                    EntryKind.BUFF -> {
                        if (!requireAdminAction(admin, AdminAccess.BUFF_DETACH, AdminAccess.BUFF_ALL)) return@action
                        tagService.adminDetachBuff(targetId, tagId, entry.id, admin)
                    }
                    EntryKind.PARTICLE -> {
                        if (!requireAdminAction(admin, AdminAccess.PARTICLE_DETACH, AdminAccess.PARTICLE_ALL)) return@action
                        tagService.adminDetachParticle(targetId, tagId, entry.id, admin)
                    }
                }
                dispatchAdminResult(admin, result)
                if (entryFactory.buildAdminDetachEntries(targetId, targetName, tag).isEmpty()) {
                    openAdminUpgrade(admin, targetId, targetName, tagId, 0)
                } else {
                    openAdminDetach(admin, targetId, targetName, tagId, currentPage)
                }
            }
        }
        admin.openInventory(session.inventory)
    }

    fun detachButtonPlaceholders(player: Player, session: MenuSession): Map<String, String> {
        val tagId = session.tagId
        val adminTargetId = session.adminTargetId
        if (adminTargetId != null) {
            val adminTargetName = session.adminTargetName ?: adminTargetId.toString()
            val tag = tagId?.let { upgradeViewTag(adminTargetId, it) }
            if (tag == null) {
                return mapOf(
                    "target_name" to adminTargetName,
                    "detach_buff_count" to "0",
                    "detach_particle_count" to "0",
                    "detach_total_count" to "0",
                    "detach_entry_hint" to "当前没有可拆项",
                )
            }
            val entries = entryFactory.buildAdminDetachEntries(adminTargetId, adminTargetName, tag)
            val buffCount = entries.count { it.kind == EntryKind.BUFF }
            val particleCount = entries.count { it.kind == EntryKind.PARTICLE }
            val totalCount = entries.size
            val hint = if (totalCount > 0) {
                "左键进入管理员拆卸中心，卷轴返还给你"
            } else {
                "当前没有可拆项"
            }
            return mapOf(
                "target_name" to adminTargetName,
                "detach_buff_count" to buffCount.toString(),
                "detach_particle_count" to particleCount.toString(),
                "detach_total_count" to totalCount.toString(),
                "detach_entry_hint" to hint,
            )
        }
        val tag = tagId?.let { upgradeViewTag(player, it) }
        if (tag == null) {
            return mapOf(
                "detach_buff_count" to "0",
                "detach_particle_count" to "0",
                "detach_total_count" to "0",
                "detach_entry_hint" to "当前没有可拆项",
            )
        }
        val entries = entryFactory.buildDetachEntries(player, tag)
        val buffCount = entries.count { it.kind == EntryKind.BUFF }
        val particleCount = entries.count { it.kind == EntryKind.PARTICLE }
        val totalCount = entries.size
        val hint = when {
            !config.detach.enabled -> "当前未开启拆卸功能"
            totalCount > 0 -> "左键进入拆卸中心"
            else -> "当前没有可拆项"
        }
        return mapOf(
            "detach_buff_count" to buffCount.toString(),
            "detach_particle_count" to particleCount.toString(),
            "detach_total_count" to totalCount.toString(),
            "detach_entry_hint" to hint,
        )
    }

    private fun upgradeViewTag(player: Player, tagId: String): TagDefinition? {
        config.tags[tagId]?.let { return it }
        val custom = tagService.data(player).customTitles[tagId] ?: return null
        return customTagDefinition(custom.id, tagService.renderCustomTitle(custom), custom.groupId)
    }

    private fun upgradeViewTag(targetId: UUID, tagId: String): TagDefinition? {
        config.tags[tagId]?.let { return it }
        val custom = tagService.data(targetId).customTitles[tagId] ?: return null
        return customTagDefinition(custom.id, tagService.renderCustomTitle(custom), custom.groupId)
    }

    private fun customTagDefinition(id: String, display: String, groupId: String?): TagDefinition {
        return TagDefinition(
            id = id,
            display = display,
            description = listOf("&#E2E8F0玩家自定义称号"),
            rarity = "CUSTOM",
            defaultUnlocked = true,
            upgradeGroups = groupId?.let { mutableListOf(it) } ?: mutableListOf(),
            permission = null,
        )
    }

    private fun requireAdminAction(player: Player, permission: String, vararg inherited: String): Boolean {
        if (AdminAccess.has(player, permission, *inherited)) {
            return true
        }
        messageService.send(player, "no-permission")
        return false
    }

    private fun dispatchAdminResult(player: Player, result: AdminActionResult) {
        messageService.send(player, result.messageKey, *result.args.toTypedArray())
    }
}
