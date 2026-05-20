package cn.aing.uptags.model.config

import cn.aing.uptags.model.runtime.ScrollKind
import org.bukkit.potion.PotionEffectType

enum class CurrencyType {
    POINTS,
    MONEY,
    TITLE_COIN;

    companion object {
        fun from(raw: String?): CurrencyType {
            if (raw.isNullOrBlank()) {
                return POINTS
            }
            return entries.firstOrNull { it.name.equals(raw.trim(), ignoreCase = true) } ?: POINTS
        }
    }
}

enum class ShopProductType {
    TAG,
    CUSTOM;

    companion object {
        fun from(raw: String?): ShopProductType {
            if (raw.isNullOrBlank()) {
                return TAG
            }
            return entries.firstOrNull { it.name.equals(raw.trim(), ignoreCase = true) } ?: TAG
        }
    }
}

enum class ShopProductMode {
    BUY,
    CHALLENGE_CLAIM,
    ITEM_EXCHANGE,
    SEASONAL,
    PRESTIGE;

    companion object {
        fun from(raw: String?): ShopProductMode {
            if (raw.isNullOrBlank()) {
                return BUY
            }
            return entries.firstOrNull { it.name.equals(raw.trim(), ignoreCase = true) } ?: BUY
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
    val particleFrequencyTicks: Long = effectTickInterval,
    val particleCountMultiplier: Int = 1,
    val particleViewDistance: Double = 32.0,
    val disabledBuffWorlds: Set<String> = emptySet(),
    val disabledBuffPermission: String? = null,
    val disableBuffsInPvp: Boolean = false,
)

data class DetachSettings(
    val enabled: Boolean,
    val buff: DetachCostSettings,
    val particle: DetachCostSettings,
)

data class DetachCostSettings(
    val money: Double,
    val points: Double,
) {
    fun amount(type: CurrencyType): Double? = when (type) {
        CurrencyType.MONEY -> money
        CurrencyType.POINTS -> points
        CurrencyType.TITLE_COIN -> null
    }
}

data class TagDefinition(
    val id: String,
    var display: String,
    var description: List<String>,
    var rarity: String,
    var defaultUnlocked: Boolean,
    var upgradeGroups: MutableList<String>,
    var permission: String? = null,
    var shop: TagShopDefinition? = null,
)

data class TagShopDefinition(
    val enabled: Boolean = true,
    val permission: String? = null,
    val conditions: List<String> = emptyList(),
    val cost: CostDefinition = CostDefinition(),
    val submitItems: List<SubmitItemDefinition> = emptyList(),
    val icon: ItemTemplate? = null,
)

data class SubmitItemDefinition(
    val material: String,
    val amount: Int,
    val name: String? = null,
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

data class ShopProductDefinition(
    val id: String,
    val type: ShopProductType,
    val targetId: String,
    val mode: ShopProductMode = ShopProductMode.BUY,
    val enabled: Boolean,
    val permission: String?,
    val conditions: List<String>,
    val cost: CostDefinition,
    val submitItems: List<SubmitItemDefinition> = emptyList(),
    val icon: ItemTemplate,
)

data class CustomTitleSettings(
    val defaultTitleCoinBalance: Double,
    val sessionTimeoutSeconds: Long,
    val currencyCosts: Map<CurrencyType, Double>,
    val presets: Map<String, CustomTitlePreset>,
)

data class CustomTitlePreset(
    val id: String,
    val minLength: Int,
    val maxLength: Int,
    val maxSchemes: Int,
    val colorsPerScheme: Int,
    val allowManualColors: Boolean,
    val allowSpaces: Boolean,
    val allowedPattern: String?,
    val blockedWords: Set<String>,
    val blockedPatterns: List<String>,
    val palettes: List<List<String>>,
    val randomColorPool: List<String>,
    val previewTemplate: String,
    val equipAfterConfirm: Boolean,
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
