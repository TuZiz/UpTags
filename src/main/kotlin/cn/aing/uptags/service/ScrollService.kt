package cn.aing.uptags.service

import cn.aing.uptags.Support
import cn.aing.uptags.config.ConfigRegistry
import cn.aing.uptags.config.MessageService
import cn.aing.uptags.model.config.ScrollDefinition
import cn.aing.uptags.model.config.TagDefinition
import cn.aing.uptags.model.runtime.ScrollKind
import cn.aing.uptags.model.runtime.ScrollSelectionContext
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

    fun createScroll(scrollKey: String, amount: Int): ItemStack {
        val definition = config.scrolls[scrollKey] ?: return ItemStack(Material.PAPER)
        val item = ItemStack(Support.material(definition.material, if (definition.kind == ScrollKind.BUFF) Material.ENCHANTED_BOOK else Material.NETHER_STAR), amount.coerceAtLeast(1))
        val meta = item.itemMeta ?: return item
        val targetName = displayName(definition.kind, definition.targetId)
        meta.setDisplayName(Support.noItalic(Support.apply(definition.name, mapOf("target_name" to targetName, "target_id" to definition.targetId, "scroll_key" to definition.key))))
        val lore = if (definition.lore.isEmpty()) defaultLore(definition.kind, targetName, definition.targetId) else definition.lore
        meta.lore = Support.noItalicLines(replaceLore(lore, definition, targetName))
        meta.persistentDataContainer.set(scrollKeyKey, PersistentDataType.STRING, definition.key)
        meta.persistentDataContainer.set(scrollKindKey, PersistentDataType.STRING, definition.kind.name)
        meta.persistentDataContainer.set(scrollTargetKey, PersistentDataType.STRING, definition.targetId)
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
        if (scrollKey.isNullOrBlank() || kind == null || targetId.isNullOrBlank()) {
            return null
        }
        return ScrollSelectionContext(scrollKey, kind, targetId, hand)
    }

    fun isValidScrollKey(scrollKey: String): Boolean {
        val definition = config.scrolls[scrollKey] ?: return false
        return when (definition.kind) {
            ScrollKind.BUFF -> config.buffs.containsKey(definition.targetId)
            ScrollKind.PARTICLE -> config.particles.containsKey(definition.targetId)
        }
    }

    fun eligibleTags(player: Player, context: ScrollSelectionContext): List<TagDefinition> = tagService.visibleTags(player).filter { tag ->
        tagService.isOwned(player, tag.id) && when (context.kind) {
            ScrollKind.BUFF -> tagService.canUpgradeBuff(tag.id, context.targetId, player)
            ScrollKind.PARTICLE -> tagService.canUnlockParticle(tag.id, context.targetId, player)
        }
    }

    fun apply(player: Player, context: ScrollSelectionContext, tagId: String): Boolean {
        val held = heldItem(player, context.hand)
        val latest = parse(held, context.hand)
        if (latest == null || latest.kind != context.kind || latest.targetId != context.targetId) {
            messageService.send(player, "scroll-item-missing")
            return false
        }
        val applied = when (context.kind) {
            ScrollKind.BUFF -> tagService.grantBuffUpgrade(player, tagId, context.targetId)
            ScrollKind.PARTICLE -> tagService.grantParticle(player, tagId, context.targetId)
        }
        if (!applied) {
            messageService.send(player, "scroll-no-effect")
            return false
        }
        consume(player, context.hand)
        val targetName = displayName(context.kind, context.targetId)
        messageService.send(player, if (context.kind == ScrollKind.BUFF) "scroll-applied-buff" else "scroll-applied-particle", targetName, tagService.tagName(tagId), context.scrollKey)
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
        "&8成功后会消耗 1 张升级卷",
    )

    private fun replaceLore(source: List<String>, definition: ScrollDefinition, targetName: String): List<String> = source.map { line ->
        Support.apply(line, mapOf(
            "target_name" to targetName,
            "target_id" to definition.targetId,
            "scroll_key" to definition.key,
            "scroll_type" to if (definition.kind == ScrollKind.BUFF) "Buff" else "粒子",
        ))
    }

    private fun heldItem(player: Player, hand: EquipmentSlot): ItemStack? = if (hand == EquipmentSlot.OFF_HAND) player.inventory.itemInOffHand else player.inventory.itemInMainHand

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
