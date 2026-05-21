package cn.aing.uptags.util

import org.bukkit.Material
import org.bukkit.enchantments.Enchantment
import org.bukkit.inventory.ItemFlag
import org.bukkit.inventory.ItemStack

object ItemStacks {
    const val HIDDEN_LORE_LINE: String = "<uptags:hide-lore-line>"

    fun material(raw: String?, fallback: Material): Material =
        if (raw.isNullOrBlank()) fallback else Material.matchMaterial(raw) ?: fallback

    fun create(
        materialName: String,
        name: String,
        lore: List<String>,
        placeholders: Map<String, String> = emptyMap(),
        glow: Boolean = false,
    ): ItemStack {
        val stack = ItemStack(material(materialName, Material.PAPER))
        val meta = stack.itemMeta ?: return stack
        meta.setDisplayName(TextRenderer.noItalic(Placeholders.apply(name, placeholders)))
        meta.lore = lore.flatMap { line ->
            Placeholders.apply(line, placeholders)
                .split("\n")
                .filterNot { HIDDEN_LORE_LINE in it }
                .map(TextRenderer::noItalic)
        }
        meta.addItemFlags(ItemFlag.HIDE_ATTRIBUTES)
        if (glow) {
            meta.addEnchant(Enchantment.UNBREAKING, 1, true)
            meta.addItemFlags(ItemFlag.HIDE_ENCHANTS)
        }
        stack.itemMeta = meta
        return stack
    }
}
