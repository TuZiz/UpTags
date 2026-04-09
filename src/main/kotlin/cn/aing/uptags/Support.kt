package cn.aing.uptags

import cn.aing.uptags.util.Formatters
import cn.aing.uptags.util.ItemStacks
import cn.aing.uptags.util.Placeholders
import cn.aing.uptags.util.TextRenderer
import org.bukkit.Material
import org.bukkit.inventory.ItemStack
import org.bukkit.inventory.meta.ItemMeta

object Support {
    fun color(value: String?): String = TextRenderer.color(value)

    fun noItalic(value: String?): String = TextRenderer.noItalic(value)

    fun stripColor(value: String?): String = TextRenderer.stripColor(value)

    fun formatDouble(value: Double): String = Formatters.formatDouble(value)

    fun roman(value: Int): String = Formatters.roman(value)

    fun material(raw: String?, fallback: Material): Material = ItemStacks.material(raw, fallback)

    fun apply(source: String?, placeholders: Map<String, String>): String = Placeholders.apply(source, placeholders)

    fun createItem(
        materialName: String,
        name: String,
        lore: List<String>,
        placeholders: Map<String, String> = emptyMap(),
        glow: Boolean = false,
    ): ItemStack = ItemStacks.create(materialName, name, lore, placeholders, glow)

    fun colorLines(lines: List<String>): List<String> = TextRenderer.colorLines(lines)

    fun noItalicLines(lines: List<String>): List<String> = TextRenderer.noItalicLines(lines)

    fun renderPaletteText(text: String?, palette: List<String>): String = TextRenderer.renderPaletteText(text, palette)

    fun normalizeHex(value: String?): String? = TextRenderer.normalizeHex(value)

    fun joinDisplay(values: Collection<String>, delimiter: String = ", "): String = Formatters.joinDisplay(values, delimiter)

    fun boolText(value: Boolean): String = Formatters.boolText(value)

    fun itemMeta(block: ItemMeta.() -> Unit): (ItemMeta) -> ItemMeta = { meta ->
        meta.block()
        meta
    }
}
