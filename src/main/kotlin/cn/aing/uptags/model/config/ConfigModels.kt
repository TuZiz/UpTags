package cn.aing.uptags.model.config

import cn.aing.uptags.model.runtime.ScrollKind
import org.bukkit.potion.PotionEffectType

enum class CurrencyType {
    POINTS,
    MONEY;

    companion object {
        fun from(raw: String?): CurrencyType {
            if (raw.isNullOrBlank()) {
                return POINTS
            }
            return entries.firstOrNull { it.name.equals(raw.trim(), ignoreCase = true) } ?: POINTS
        }
    }
}

data class CostDefinition(
    val type: CurrencyType = CurrencyType.POINTS,
    val amount: Double = 0.0,
    val levelAmounts: Map<Int, Double> = emptyMap(),
    val conditions: List<String> = emptyList(),
) {
    fun priceForLevel(level: Int): Double = levelAmounts[level] ?: amount
}

data class PluginSettings(
    val effectTickInterval: Long,
    val forceDefaultTag: Boolean,
    val forcedTagId: String,
)

data class TagDefinition(
    val id: String,
    var display: String,
    var description: List<String>,
    var rarity: String,
    var defaultUnlocked: Boolean,
    var upgradeGroups: MutableList<String>,
    var permission: String? = null,
)

data class BuffDefinition(
    val id: String,
    val type: PotionEffectType,
    val display: String,
    val maxLevel: Int,
    val duration: Int,
    val cost: CostDefinition,
)

data class ParticleDefinition(
    val id: String,
    val display: String,
    val pattern: String,
    val cost: CostDefinition,
)

data class UpgradeGroupDefinition(
    val id: String,
    val name: String,
    val display: String,
    val buffs: Set<String>,
    val particles: Set<String>,
)

data class ScrollDefinition(
    val key: String,
    val kind: ScrollKind,
    val targetId: String,
    val material: String,
    val name: String,
    val lore: List<String>,
    val glow: Boolean,
)

data class ItemTemplate(
    val material: String,
    val name: String,
    val lore: List<String>,
)

data class GuiKey(
    val iconFunction: String?,
    val base: ItemTemplate?,
    val has: ItemTemplate?,
    val normal: ItemTemplate?,
)

data class GuiTemplate(
    val material: String,
    val name: String,
    val lore: List<String>,
    val loreMaxed: List<String>,
)

data class GuiLayout(
    val title: String,
    val plain: List<String>,
    val keys: Map<Char, GuiKey>,
    val templates: Map<String, GuiTemplate>,
) {
    fun size(): Int = plain.size * 9

    fun entrySlots(): List<Int> {
        val slots = ArrayList<Int>()
        plain.forEachIndexed { row, line ->
            line.forEachIndexed { column, token ->
                if (token == '@') {
                    slots += row * 9 + column
                }
            }
        }
        return slots
    }
}
