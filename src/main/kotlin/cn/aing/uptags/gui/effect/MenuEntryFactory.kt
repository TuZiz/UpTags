package cn.aing.uptags.gui.effect

import cn.aing.uptags.Support
import cn.aing.uptags.config.ConfigRegistry
import cn.aing.uptags.model.config.CurrencyType
import cn.aing.uptags.model.config.TagDefinition
import cn.aing.uptags.service.tag.TagService
import org.bukkit.entity.Player
import java.util.LinkedHashSet
import java.util.UUID

internal class MenuEntryFactory(
    private val config: ConfigRegistry,
    private val tagService: TagService,
    private val currencyName: (CurrencyType) -> String,
) {
    fun buildUpgradeEntries(player: Player, tag: TagDefinition): List<UpgradeEntry> {
        val entries = ArrayList<UpgradeEntry>()
        val progress = tagService.data(player).tagProgress[tag.id]
        val visibleBuffIds = LinkedHashSet(tagService.allowedBuffIds(tag))
        progress?.buffLevels?.keys?.forEach { visibleBuffIds += it }
        for (buffId in visibleBuffIds) {
            val buff = config.buffs[buffId] ?: continue
            val level = tagService.buffLevel(player, tag.id, buffId)
            val enabled = tagService.isBuffEnabled(player, tag.id, buffId)
            val maxed = level >= buff.maxLevel
            val price = buff.cost.priceForLevel(minOf(buff.maxLevel, level + 1))
            val template = config.upgradeLayout.templates["buff"] ?: continue
            val lore = if (maxed && template.loreMaxed.isNotEmpty()) template.loreMaxed else template.lore
            val placeholders = mapOf(
                "entry_display" to buff.display,
                "entry_current" to level.toString(),
                "entry_max" to buff.maxLevel.toString(),
                "entry_status" to if (maxed) "已满级" else if (buffId in tagService.allowedBuffIds(tag)) "可升级" else "卷轴附加",
                "entry_equip_state" to if (enabled) "已启用" else "未启用",
                "entry_buffs" to Support.color(buff.display) + " " + Support.roman(maxOf(1, if (level == 0) 1 else level)),
                "entry_points" to Support.formatDouble(price),
                "entry_currency" to currencyName(buff.cost.type),
                "entry_action" to if (maxed) "左键已无可升级项" else if (buffId in tagService.allowedBuffIds(tag)) "左键购买或用升级卷提升下一级" else "这条词条来自升级卷，仍可继续升级",
                "entry_right_action" to "右键切换启用 / 停用",
                "entry_detach_action" to "点击下方拆卸中心按钮前往拆卸",
            )
            entries += UpgradeEntry(
                buffId,
                EntryKind.BUFF,
                Support.createItem(template.material, template.name, lore, placeholders, enabled),
            )
        }
        val visibleParticleIds = LinkedHashSet(tagService.allowedParticleIds(tag))
        progress?.ownedParticles?.forEach { visibleParticleIds += it }
        for (particleId in visibleParticleIds) {
            val particle = config.particles[particleId] ?: continue
            val owned = tagService.isParticleOwned(player, tag.id, particleId)
            val selected = tagService.isParticleSelected(player, tag.id, particleId)
            val template = config.upgradeLayout.templates["particle"] ?: continue
            val placeholders = mapOf(
                "entry_display" to particle.display,
                "entry_status" to if (owned) "已拥有" else if (particleId in tagService.allowedParticleIds(tag)) "未拥有" else "卷轴专属",
                "entry_equip_state" to if (selected) "已选中" else "未选中",
                "entry_points" to Support.formatDouble(particle.cost.priceForLevel(1)),
                "entry_currency" to currencyName(particle.cost.type),
                "entry_action" to if (owned) "左键已解锁" else if (particleId in tagService.allowedParticleIds(tag)) "左键购买或用升级卷解锁" else "该粒子可通过升级卷直接解锁",
                "entry_right_action" to "右键设为当前粒子 / 取消",
                "entry_detach_action" to "点击下方拆卸中心按钮前往拆卸",
            )
            entries += UpgradeEntry(
                particleId,
                EntryKind.PARTICLE,
                Support.createItem(
                    template.material,
                    template.name,
                    template.lore,
                    placeholders,
                    selected,
                ),
            )
        }
        return entries
    }

    fun buildAdminUpgradeEntries(targetId: UUID, targetName: String, tag: TagDefinition): List<UpgradeEntry> {
        val entries = ArrayList<UpgradeEntry>()
        val progress = tagService.data(targetId).tagProgress[tag.id]
        val allowedBuffIds = tagService.allowedBuffIds(targetId, tag.id).toSet()
        val visibleBuffIds = LinkedHashSet(allowedBuffIds)
        progress?.buffLevels?.keys?.forEach { visibleBuffIds += it }
        for (buffId in visibleBuffIds) {
            val buff = config.buffs[buffId] ?: continue
            val level = tagService.buffLevel(targetId, tag.id, buffId)
            val enabled = tagService.isBuffEnabled(targetId, tag.id, buffId)
            val maxed = level >= buff.maxLevel
            val template = config.upgradeLayout.templates["admin-buff"] ?: config.upgradeLayout.templates["buff"] ?: continue
            val lore = if (maxed && template.loreMaxed.isNotEmpty()) template.loreMaxed else template.lore
            val placeholders = mapOf(
                "target_name" to targetName,
                "entry_display" to buff.display,
                "entry_current" to level.toString(),
                "entry_max" to buff.maxLevel.toString(),
                "entry_status" to if (maxed) "已满级" else if (buffId in allowedBuffIds) "可管理" else "卷轴附加",
                "entry_equip_state" to if (enabled) "已启用" else "未启用",
                "entry_buffs" to Support.color(buff.display) + " " + Support.roman(maxOf(1, if (level == 0) 1 else level)),
                "entry_points" to "0",
                "entry_currency" to "管理员操作",
                "entry_action" to if (maxed) "左键保持满级" else "左键提升到 Lv.${level + 1}",
                "entry_right_action" to if (level <= 0) "右键需先设置等级" else if (enabled) "右键停用 Buff" else "右键启用 Buff",
                "entry_detach_action" to "点击下方拆卸中心按钮前往拆卸",
            )
            entries += UpgradeEntry(
                buffId,
                EntryKind.BUFF,
                Support.createItem(template.material, template.name, lore, placeholders, enabled),
            )
        }
        val allowedParticleIds = tagService.allowedParticleIds(targetId, tag.id).toSet()
        val visibleParticleIds = LinkedHashSet(allowedParticleIds)
        progress?.ownedParticles?.forEach { visibleParticleIds += it }
        for (particleId in visibleParticleIds) {
            val particle = config.particles[particleId] ?: continue
            val owned = tagService.isParticleOwned(targetId, tag.id, particleId)
            val selected = tagService.isParticleSelected(targetId, tag.id, particleId)
            val template = config.upgradeLayout.templates["admin-particle"] ?: config.upgradeLayout.templates["particle"] ?: continue
            val placeholders = mapOf(
                "target_name" to targetName,
                "entry_display" to particle.display,
                "entry_status" to if (owned) "已拥有" else if (particleId in allowedParticleIds) "未拥有" else "卷轴专属",
                "entry_equip_state" to if (selected) "已选中" else "未选中",
                "entry_points" to "0",
                "entry_currency" to "管理员操作",
                "entry_action" to if (owned) "左键保持已解锁" else "左键授予粒子",
                "entry_right_action" to if (selected) "右键清空选中粒子" else if (owned) "右键设为当前粒子" else "右键需先授予粒子",
                "entry_detach_action" to "点击下方拆卸中心按钮前往拆卸",
            )
            entries += UpgradeEntry(
                particleId,
                EntryKind.PARTICLE,
                Support.createItem(template.material, template.name, template.lore, placeholders, selected),
            )
        }
        return entries
    }

    fun buildDetachEntries(player: Player, tag: TagDefinition): List<UpgradeEntry> {
        val entries = ArrayList<UpgradeEntry>()
        val progress = tagService.data(player).tagProgress[tag.id]
        val visibleBuffIds = LinkedHashSet(tagService.allowedBuffIds(tag))
        progress?.buffLevels?.keys?.forEach { visibleBuffIds += it }
        for (buffId in visibleBuffIds) {
            val buff = config.buffs[buffId] ?: continue
            val level = tagService.buffLevel(player, tag.id, buffId)
            if (level <= 0) continue
            val enabled = tagService.isBuffEnabled(player, tag.id, buffId)
            val template = config.detachLayout.templates["buff"] ?: continue
            val placeholders = mapOf(
                "entry_display" to buff.display,
                "entry_current" to level.toString(),
                "entry_max" to buff.maxLevel.toString(),
                "entry_status" to if (enabled) "已启用" else "已拥有",
                "entry_equip_state" to if (enabled) "当前生效" else "已拆下待机",
                "entry_buffs" to Support.color(buff.display) + " " + Support.roman(level),
                "entry_left_action" to "左键金币拆下",
                "entry_right_action" to "右键点券拆下",
                "entry_detach_money" to Support.formatDouble(config.detach.buff.money),
                "entry_detach_points" to Support.formatDouble(config.detach.buff.points),
            )
            entries += UpgradeEntry(
                buffId,
                EntryKind.BUFF,
                Support.createItem(template.material, template.name, template.lore, placeholders, enabled),
            )
        }
        val visibleParticleIds = LinkedHashSet(tagService.allowedParticleIds(tag))
        progress?.ownedParticles?.forEach { visibleParticleIds += it }
        for (particleId in visibleParticleIds) {
            val particle = config.particles[particleId] ?: continue
            val owned = tagService.isParticleOwned(player, tag.id, particleId)
            if (!owned) continue
            val selected = tagService.isParticleSelected(player, tag.id, particleId)
            val template = config.detachLayout.templates["particle"] ?: continue
            val placeholders = mapOf(
                "entry_display" to particle.display,
                "entry_status" to if (selected) "已拥有 | 当前选中" else "已拥有",
                "entry_equip_state" to if (selected) "当前选中" else "未选中",
                "entry_left_action" to "左键金币拆下",
                "entry_right_action" to "右键点券拆下",
                "entry_detach_money" to Support.formatDouble(config.detach.particle.money),
                "entry_detach_points" to Support.formatDouble(config.detach.particle.points),
            )
            entries += UpgradeEntry(
                particleId,
                EntryKind.PARTICLE,
                Support.createItem(
                    template.material,
                    template.name,
                    template.lore,
                    placeholders,
                    selected,
                ),
            )
        }
        return entries
    }

    fun buildAdminDetachEntries(targetId: UUID, targetName: String, tag: TagDefinition): List<UpgradeEntry> {
        val entries = ArrayList<UpgradeEntry>()
        val progress = tagService.data(targetId).tagProgress[tag.id]
        val visibleBuffIds = LinkedHashSet(tagService.allowedBuffIds(targetId, tag.id))
        progress?.buffLevels?.keys?.forEach { visibleBuffIds += it }
        for (buffId in visibleBuffIds) {
            val buff = config.buffs[buffId] ?: continue
            val level = tagService.buffLevel(targetId, tag.id, buffId)
            if (level <= 0) continue
            val enabled = tagService.isBuffEnabled(targetId, tag.id, buffId)
            val template = config.detachLayout.templates["admin-buff"] ?: config.detachLayout.templates["buff"] ?: continue
            val placeholders = mapOf(
                "entry_display" to buff.display,
                "target_name" to targetName,
                "entry_current" to level.toString(),
                "entry_max" to buff.maxLevel.toString(),
                "entry_status" to if (enabled) "已启用" else "已拥有",
                "entry_equip_state" to if (enabled) "当前生效" else "已拆下待机",
                "entry_buffs" to Support.color(buff.display) + " " + Support.roman(level),
                "entry_left_action" to "左键拆卸并返还卷轴给管理员",
                "entry_right_action" to "右键同样执行管理员拆卸",
                "entry_detach_money" to "0",
                "entry_detach_points" to "0",
            )
            entries += UpgradeEntry(
                buffId,
                EntryKind.BUFF,
                Support.createItem(template.material, template.name, template.lore, placeholders, enabled),
            )
        }
        val visibleParticleIds = LinkedHashSet(tagService.allowedParticleIds(targetId, tag.id))
        progress?.ownedParticles?.forEach { visibleParticleIds += it }
        for (particleId in visibleParticleIds) {
            val particle = config.particles[particleId] ?: continue
            val owned = tagService.isParticleOwned(targetId, tag.id, particleId)
            if (!owned) continue
            val selected = tagService.isParticleSelected(targetId, tag.id, particleId)
            val template = config.detachLayout.templates["admin-particle"] ?: config.detachLayout.templates["particle"] ?: continue
            val placeholders = mapOf(
                "entry_display" to particle.display,
                "target_name" to targetName,
                "entry_status" to if (selected) "已拥有 | 当前选中" else "已拥有",
                "entry_equip_state" to if (selected) "当前选中" else "未选中",
                "entry_left_action" to "左键拆卸并返还卷轴给管理员",
                "entry_right_action" to "右键同样执行管理员拆卸",
                "entry_detach_money" to "0",
                "entry_detach_points" to "0",
            )
            entries += UpgradeEntry(
                particleId,
                EntryKind.PARTICLE,
                Support.createItem(template.material, template.name, template.lore, placeholders, selected),
            )
        }
        return entries
    }
}
