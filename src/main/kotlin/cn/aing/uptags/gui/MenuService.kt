package cn.aing.uptags.gui

import cn.aing.uptags.Support
import cn.aing.uptags.config.ConfigRegistry
import cn.aing.uptags.config.MessageService
import cn.aing.uptags.model.config.GuiLayout
import cn.aing.uptags.model.config.GuiTemplate
import cn.aing.uptags.model.config.TagDefinition
import cn.aing.uptags.model.config.CurrencyType
import cn.aing.uptags.model.runtime.ScrollKind
import cn.aing.uptags.model.runtime.ScrollSelectionContext
import cn.aing.uptags.service.ScrollService
import cn.aing.uptags.service.TagService
import org.bukkit.Bukkit
import org.bukkit.Material
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import org.bukkit.event.inventory.InventoryClickEvent
import org.bukkit.inventory.Inventory
import org.bukkit.inventory.InventoryHolder
import org.bukkit.inventory.ItemStack
import org.bukkit.plugin.java.JavaPlugin
import java.util.LinkedHashMap

class MenuService(
    private val plugin: JavaPlugin,
    private val config: ConfigRegistry,
    private val tagService: TagService,
    private val scrollService: ScrollService,
    private val messageService: MessageService,
) : Listener {
    fun openWarehouse(player: Player, page: Int) {
        val layout = config.warehouseLayout
        val tags = tagService.visibleTags(player)
        val session = createSession(MenuType.WAREHOUSE, layout, page, null, null)
        fillStatic(player, layout, session, page, tags.size)
        val slots = layout.entrySlots()
        val start = page * slots.size
        for (offset in slots.indices) {
            val index = start + offset
            if (index >= tags.size) continue
            val tag = tags[index]
            val owned = tagService.isOwned(player, tag.id)
            val template = layout.templates[if (owned) "tag-owned" else "tag-locked"]
            val placeholders = linkedMapOf(
                "tag_display" to tag.display,
                "tag_description" to tag.description.joinToString("\n"),
                "tag_rarity" to tagService.rarityDisplay(tag.rarity),
                "tag_buff_count" to tagService.tagBuffCount(player, tag.id).toString(),
                "tag_particle_count" to tagService.tagParticleCount(player, tag.id).toString(),
                "tag_buffs" to tagService.tagBuffsDisplay(player, tag.id),
                "tag_particles" to tagService.tagParticlesDisplay(player, tag.id),
            )
            val slot = slots[offset]
            session.inventory.setItem(slot, templateItem(template, placeholders, tagService.currentTagId(player) == tag.id))
            if (owned) {
                session.actions[slot] = { click ->
                    if (click.isLeftClick) {
                        tagService.equipTag(player, tag.id)
                        openWarehouse(player, page)
                    } else if (click.isRightClick) {
                        openUpgrade(player, tag.id, 0)
                    }
                }
            }
        }
        player.openInventory(session.inventory)
    }

    fun openUpgrade(player: Player, tagId: String, page: Int) {
        val tag = config.tags[tagId] ?: run {
            messageService.send(player, "tag-not-found", tagId)
            return
        }
        val layout = config.upgradeLayout
        val entries = buildUpgradeEntries(player, tag)
        val session = createSession(MenuType.UPGRADE, layout, page, tagId, null)
        fillStatic(player, layout, session, page, entries.size)
        val slots = layout.entrySlots()
        val start = page * slots.size
        for (offset in slots.indices) {
            val index = start + offset
            if (index >= entries.size) continue
            val entry = entries[index]
            val slot = slots[offset]
            session.inventory.setItem(slot, entry.item)
            session.actions[slot] = { click ->
                if (entry.kind == EntryKind.BUFF) {
                    if (click.isLeftClick) tagService.upgradeBuff(player, tag.id, entry.id) else if (click.isRightClick) tagService.toggleBuff(player, tag.id, entry.id)
                } else {
                    if (click.isLeftClick) tagService.buyParticle(player, tag.id, entry.id) else if (click.isRightClick) tagService.selectParticle(player, tag.id, entry.id)
                }
                openUpgrade(player, tag.id, page)
            }
        }
        player.openInventory(session.inventory)
    }

    fun openScrollSelection(player: Player, context: ScrollSelectionContext, page: Int) {
        val layout = config.warehouseLayout
        val tags = scrollService.eligibleTags(player, context)
        if (tags.isEmpty()) {
            messageService.send(player, "scroll-no-eligible-tags")
            return
        }
        val session = createSession(MenuType.SCROLL_SELECT, layout, page, null, context, "&0升级卷 - 选择称号")
        fillStatic(player, layout, session, page, tags.size)
        val slots = layout.entrySlots()
        val start = page * slots.size
        for (offset in slots.indices) {
            val index = start + offset
            if (index >= tags.size) continue
            val tag = tags[index]
            val slot = slots[offset]
            val targetName = scrollService.displayName(context.kind, context.targetId)
            session.inventory.setItem(slot, Support.createItem("NAME_TAG", "&f${tag.display}", listOf(
                "&7称号名称: &f${Support.stripColor(tag.display)}",
                "&7升级卷类型: &f${if (context.kind == ScrollKind.BUFF) "Buff 升级卷" else "粒子解锁卷"}",
                "&7目标内容: &f$targetName",
                "&e左键对这个称号使用升级卷",
            )))
            session.actions[slot] = {
                if (scrollService.apply(player, context, tag.id)) {
                    player.closeInventory()
                }
            }
        }
        player.openInventory(session.inventory)
    }

    @EventHandler
    fun onInventoryClick(event: InventoryClickEvent) {
        val player = event.whoClicked as? Player ?: return
        val holder = event.view.topInventory.holder as? MenuHolder ?: return
        val session = holder.session
        if (event.clickedInventory == null || event.clickedInventory != event.view.topInventory) {
            return
        }
        event.isCancelled = true
        session.actions[event.slot]?.invoke(event)
    }

    private fun buildUpgradeEntries(player: Player, tag: TagDefinition): List<UpgradeEntry> {
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
                "entry_currency" to if (buff.cost.type == CurrencyType.POINTS) "点券" else "金币",
                "entry_action" to if (maxed) "左键已无可升级项" else if (buffId in tagService.allowedBuffIds(tag)) "左键购买或用升级卷提升下一阶" else "该词条来自升级卷，仍可继续升级",
                "entry_right_action" to "右键切换启用 / 停用",
            )
            entries += UpgradeEntry(buffId, EntryKind.BUFF, Support.createItem(template.material, template.name, lore, placeholders, enabled))
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
                "entry_currency" to if (particle.cost.type == CurrencyType.POINTS) "点券" else "金币",
                "entry_action" to if (owned) "左键已解锁" else if (particleId in tagService.allowedParticleIds(tag)) "左键购买或用升级卷解锁" else "该粒子可通过升级卷直接解锁",
                "entry_right_action" to "右键设为当前粒子 / 取消",
            )
            entries += UpgradeEntry(particleId, EntryKind.PARTICLE, Support.createItem(template.material, template.name, template.lore, placeholders, selected))
        }
        return entries
    }

    private fun createSession(type: MenuType, layout: GuiLayout, page: Int, tagId: String?, scrollContext: ScrollSelectionContext?, title: String = layout.title): MenuSession {
        val holder = MenuHolder()
        val inventory = Bukkit.createInventory(holder, layout.size(), Support.color(title))
        val session = MenuSession(type, inventory, page, tagId, scrollContext)
        holder.session = session
        return session
    }

    private fun fillStatic(player: Player, layout: GuiLayout, session: MenuSession, page: Int, entryCount: Int) {
        val pageSize = layout.entrySlots().size.coerceAtLeast(1)
        val maxPage = maxOf(0, kotlin.math.ceil(entryCount / pageSize.toDouble()).toInt() - 1)
        layout.plain.forEachIndexed { row, line ->
            line.forEachIndexed { column, token ->
                if (token == '@') return@forEachIndexed
                val slot = row * 9 + column
                val key = layout.keys[token] ?: return@forEachIndexed
                val function = key.iconFunction
                var template = key.base
                when {
                    function.equals("last", true) -> {
                        template = if (page > 0 && key.has != null) key.has else key.normal
                        if (page > 0) session.actions[slot] = { changePage(player, session, page - 1) }
                    }
                    function.equals("next", true) -> {
                        template = if (page < maxPage && key.has != null) key.has else key.normal
                        if (page < maxPage) session.actions[slot] = { changePage(player, session, page + 1) }
                    }
                    function.equals("back", true) -> session.actions[slot] = { goBack(player, session) }
                }
                if (template != null) {
                    session.inventory.setItem(slot, Support.createItem(template.material, template.name, template.lore))
                }
            }
        }
    }

    private fun templateItem(template: GuiTemplate?, placeholders: Map<String, String>, glow: Boolean): ItemStack {
        if (template == null) return ItemStack(Material.PAPER)
        return Support.createItem(template.material, template.name, template.lore, placeholders, glow)
    }

    private fun changePage(player: Player, session: MenuSession, page: Int) {
        when (session.type) {
            MenuType.WAREHOUSE -> openWarehouse(player, page)
            MenuType.UPGRADE -> openUpgrade(player, session.tagId ?: return, page)
            MenuType.SCROLL_SELECT -> openScrollSelection(player, session.scrollContext ?: return, page)
        }
    }

    private fun goBack(player: Player, session: MenuSession) {
        when (session.type) {
            MenuType.UPGRADE, MenuType.SCROLL_SELECT -> openWarehouse(player, 0)
            MenuType.WAREHOUSE -> player.closeInventory()
        }
    }

    private enum class MenuType {
        WAREHOUSE,
        UPGRADE,
        SCROLL_SELECT,
    }

    private enum class EntryKind {
        BUFF,
        PARTICLE,
    }

    private data class UpgradeEntry(val id: String, val kind: EntryKind, val item: ItemStack)

    private class MenuHolder : InventoryHolder {
        lateinit var session: MenuSession
        override fun getInventory(): Inventory = session.inventory
    }

    private class MenuSession(
        val type: MenuType,
        val inventory: Inventory,
        val page: Int,
        val tagId: String?,
        val scrollContext: ScrollSelectionContext?,
    ) {
        val actions = LinkedHashMap<Int, (InventoryClickEvent) -> Unit>()
    }
}
