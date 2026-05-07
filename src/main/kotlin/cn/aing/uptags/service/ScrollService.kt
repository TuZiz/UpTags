package cn.aing.uptags.service

import cn.aing.uptags.Support
import cn.aing.uptags.config.ConfigRegistry
import cn.aing.uptags.config.MessageService
import cn.aing.uptags.model.config.ScrollDefinition
import cn.aing.uptags.model.runtime.ScrollKind
import cn.aing.uptags.model.runtime.ScrollSelectionContext
import cn.aing.uptags.model.runtime.TitleEntry
import org.bukkit.Material
import org.bukkit.NamespacedKey
import org.bukkit.entity.Player
import org.bukkit.inventory.EquipmentSlot
import org.bukkit.inventory.ItemStack
import org.bukkit.persistence.PersistentDataType
import org.bukkit.plugin.java.JavaPlugin

class ScrollService(
    private val plugin: JavaPlugin,
    private val config: ConfigRegistry,
    private val tagService: TagService,
    private val messageService: MessageService,
) {
    private val scrollKeyKey = NamespacedKey(plugin, "scroll_key")
    private val scrollKindKey = NamespacedKey(plugin, "scroll_kind")
    private val scrollTargetKey = NamespacedKey(plugin, "scroll_target")
    private val scrollLevelKey = NamespacedKey(plugin, "scroll_level")

    fun createScroll(scrollKey: String, amount: Int, level: Int = 1): ItemStack {
        val definition = config.scrolls[scrollKey] ?: return ItemStack(Material.PAPER)
        val scrollLevel = level.coerceAtLeast(1)
        val fallbackMaterial = if (definition.kind == ScrollKind.BUFF) Material.ENCHANTED_BOOK else Material.NETHER_STAR
        val item = ItemStack(Support.material(definition.material, fallbackMaterial), amount.coerceAtLeast(1))
        val meta = item.itemMeta ?: return item
        val targetName = displayName(definition.kind, definition.targetId)
        meta.setDisplayName(Support.noItalic(Support.apply(definition.name, scrollPlaceholders(definition, targetName, scrollLevel))))
        val lore = if (definition.lore.isEmpty()) defaultLore(definition.kind, targetName, definition.targetId) else definition.lore
        meta.lore = Support.noItalicLines(replaceLore(lore, definition, targetName, scrollLevel))
        meta.persistentDataContainer.set(scrollKeyKey, PersistentDataType.STRING, definition.key)
        meta.persistentDataContainer.set(scrollKindKey, PersistentDataType.STRING, definition.kind.name)
        meta.persistentDataContainer.set(scrollTargetKey, PersistentDataType.STRING, definition.targetId)
        meta.persistentDataContainer.set(scrollLevelKey, PersistentDataType.INTEGER, scrollLevel)
        item.itemMeta = meta
        return item
    }

    fun parse(item: ItemStack?, hand: EquipmentSlot): ScrollSelectionContext? {
        if (item == null || item.type.isAir) return null
        val meta = item.itemMeta ?: return null
        val container = meta.persistentDataContainer
        val scrollKey = container.get(scrollKeyKey, PersistentDataType.STRING)
        val kind = ScrollKind.from(container.get(scrollKindKey, PersistentDataType.STRING))
        val targetId = container.get(scrollTargetKey, PersistentDataType.STRING)
        val level = container.get(scrollLevelKey, PersistentDataType.INTEGER)?.coerceAtLeast(1) ?: 1
        if (scrollKey.isNullOrBlank() || kind == null || targetId.isNullOrBlank()) {
            return null
        }
        return ScrollSelectionContext(scrollKey, kind, targetId, hand, level)
    }

    fun isValidScrollKey(scrollKey: String): Boolean {
        val definition = config.scrolls[scrollKey] ?: return false
        return when (definition.kind) {
            ScrollKind.BUFF -> config.buffs.containsKey(definition.targetId)
            ScrollKind.PARTICLE -> config.particles.containsKey(definition.targetId)
        }
    }

    fun eligibleTitles(player: Player, context: ScrollSelectionContext): List<TitleEntry> = tagService.visibleTitles(player).filter { title ->
        title.owned && when (context.kind) {
            ScrollKind.BUFF -> tagService.canUpgradeBuff(title.id, context.targetId, player, context.level)
            ScrollKind.PARTICLE -> tagService.canUnlockParticle(title.id, context.targetId, player)
        }
    }

    fun apply(player: Player, context: ScrollSelectionContext, tagId: String): Boolean {
        val held = heldItem(player, context.hand)
        val latest = parse(held, context.hand)
        if (latest == null ||
            latest.scrollKey != context.scrollKey ||
            latest.kind != context.kind ||
            latest.targetId != context.targetId ||
            latest.level != context.level
        ) {
            messageService.send(player, "scroll-item-missing")
            return false
        }
        val applied = when (context.kind) {
            ScrollKind.BUFF -> tagService.grantBuffUpgrade(player, tagId, context.targetId, context.level)
            ScrollKind.PARTICLE -> tagService.grantParticle(player, tagId, context.targetId)
        }
        if (!applied) {
            messageService.send(player, "scroll-no-effect")
            return false
        }
        consume(player, context.hand)
        val targetName = displayName(context.kind, context.targetId)
        messageService.send(
            player,
            if (context.kind == ScrollKind.BUFF) "scroll-applied-buff" else "scroll-applied-particle",
            targetName,
            tagService.titleName(player, tagId),
            context.scrollKey,
        )
        return true
    }

    fun displayName(kind: ScrollKind, targetId: String): String = when (kind) {
        ScrollKind.BUFF -> config.buffs[targetId]?.let { Support.stripColor(it.display) } ?: targetId
        ScrollKind.PARTICLE -> config.particles[targetId]?.let { Support.stripColor(it.display) } ?: targetId
    }

    private fun defaultLore(kind: ScrollKind, targetName: String, targetId: String): List<String> = listOf(
        "&7目标类型: &f${if (kind == ScrollKind.BUFF) "Buff" else "粒子"}",
        "&7目标内容: &f$targetName &8($targetId)",
        "&7右键后选择要生效的称号",
        "&8成功后会消耗 1 张卷轴",
    )

    private fun replaceLore(source: List<String>, definition: ScrollDefinition, targetName: String): List<String> = source.map { line ->
        Support.apply(
            line,
            mapOf(
                "target_name" to targetName,
                "target_id" to definition.targetId,
                "scroll_key" to definition.key,
                "scroll_type" to if (definition.kind == ScrollKind.BUFF) "Buff" else "粒子",
            ),
        )
    }

    private fun replaceLore(source: List<String>, definition: ScrollDefinition, targetName: String, level: Int): List<String> = source.map { line ->
        Support.apply(line, scrollPlaceholders(definition, targetName, level))
    }.let { rendered ->
        if (source.any { it.contains("scroll_level", ignoreCase = true) }) {
            rendered
        } else {
            rendered + Support.apply("&7卷轴等级: &f%scroll_level%", scrollPlaceholders(definition, targetName, level))
        }
    }

    private fun scrollPlaceholders(definition: ScrollDefinition, targetName: String, level: Int): Map<String, String> = mapOf(
        "target_name" to targetName,
        "target_id" to definition.targetId,
        "scroll_key" to definition.key,
        "scroll_type" to if (definition.kind == ScrollKind.BUFF) "Buff" else "粒子",
        "scroll_level" to level.coerceAtLeast(1).toString(),
    )

    private fun heldItem(player: Player, hand: EquipmentSlot): ItemStack? = if (hand == EquipmentSlot.OFF_HAND) {
        player.inventory.itemInOffHand
    } else {
        player.inventory.itemInMainHand
    }

    private fun consume(player: Player, hand: EquipmentSlot) {
        val inventory = player.inventory
        val item = heldItem(player, hand) ?: return
        if (item.type.isAir) return
        if (item.amount <= 1) {
            if (hand == EquipmentSlot.OFF_HAND) inventory.setItemInOffHand(null) else inventory.setItemInMainHand(null)
        } else {
            item.amount = item.amount - 1
            if (hand == EquipmentSlot.OFF_HAND) inventory.setItemInOffHand(item) else inventory.setItemInMainHand(item)
        }
    }
}
