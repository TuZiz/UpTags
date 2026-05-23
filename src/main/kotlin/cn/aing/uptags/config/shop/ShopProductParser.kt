package cn.aing.uptags.config.shop

import cn.aing.uptags.model.config.ConfigIssue
import cn.aing.uptags.model.config.ConfigIssueSeverity
import cn.aing.uptags.model.config.CostDefinition
import cn.aing.uptags.model.config.CurrencyType
import cn.aing.uptags.model.config.ItemTemplate
import cn.aing.uptags.model.config.ShopProductDefinition
import cn.aing.uptags.model.config.ShopProductMode
import cn.aing.uptags.model.config.ShopProductType
import cn.aing.uptags.model.config.SubmitItemDefinition
import org.bukkit.Material
import org.bukkit.configuration.ConfigurationSection
import org.bukkit.configuration.file.YamlConfiguration
import java.util.LinkedHashMap
import java.util.LinkedHashSet
import java.util.Locale

data class ShopProductParseResult(
    val products: Map<String, ShopProductDefinition>,
    val explicitProductIds: Set<String>,
)

class ShopProductParser(
    private val resolver: TagProductResolver,
    private val tagExists: (String) -> Boolean,
    private val categoryExists: (String) -> Boolean,
    private val issueSink: (ConfigIssue) -> Unit,
) {
    fun parse(yaml: YamlConfiguration): ShopProductParseResult {
        val products = LinkedHashMap<String, ShopProductDefinition>()
        val explicitIds = LinkedHashSet<String>()
        val root = yaml.getConfigurationSection("products") ?: return ShopProductParseResult(products, explicitIds)
        root.getKeys(false).forEach { productId ->
            val section = root.getConfigurationSection(productId) ?: return@forEach
            parseProduct(productId, section)?.let { product ->
                products[product.id] = product
                explicitIds += product.id
            }
        }
        return ShopProductParseResult(products, explicitIds)
    }

    private fun parseProduct(productId: String, section: ConfigurationSection): ShopProductDefinition? {
        val type = parseType(section.getString("type"), "products.$productId.type")
        val targetId = section.getString("target-id", productId)?.trim()?.takeIf { it.isNotBlank() } ?: productId
        if (type == ShopProductType.TAG && !tagExists(targetId)) {
            warn("shop.yml", "products.$productId.target-id", "商品 '$productId' 指向的称号 '$targetId' 不存在，商品仍会加载但无法正常购买。")
        }
        val category = section.getString("category")?.trim()?.takeIf { it.isNotBlank() }
        if (category != null && !categoryExists(category)) {
            warn("shop.yml", "products.$productId.category", "商品 '$productId' 使用了不存在的分类 '$category'，已保留该值但分类文案会走兜底。")
        }
        return ShopProductDefinition(
            id = productId,
            type = type,
            targetId = targetId,
            mode = parseMode(section.getString("mode"), "products.$productId.mode"),
            category = category,
            enabled = section.getBoolean("enabled", true),
            permission = section.getString("permission")?.takeIf { it.isNotBlank() },
            conditions = section.getStringList("conditions"),
            cost = parseCost(section, productId),
            submitItems = parseSubmitItems(section.getConfigurationSection("submit-items"), productId),
            icon = parseIcon(section, productId, targetId),
        )
    }

    private fun parseIcon(section: ConfigurationSection, productId: String, targetId: String): ItemTemplate {
        val defaultIcon = resolver.defaultIcon(targetId, productId)
        if (section.isString("icon")) {
            val material = section.getString("icon")?.trim()?.takeIf { it.isNotBlank() } ?: defaultIcon.material
            return defaultIcon.copy(material = material)
        }
        val iconSection = section.getConfigurationSection("icon") ?: return defaultIcon
        return ItemTemplate(
            material = iconSection.getString("material", defaultIcon.material) ?: defaultIcon.material,
            name = iconSection.getString("name")?.takeIf { it.isNotBlank() } ?: defaultIcon.name,
            lore = iconSection.getStringList("lore").ifEmpty { defaultIcon.lore },
        )
    }

    private fun parseCost(section: ConfigurationSection, productId: String): CostDefinition {
        val costSection = section.getConfigurationSection("cost")
        if (costSection != null) {
            val type = parseCurrency(costSection.getString("type"), "products.$productId.cost.type")
            return CostDefinition(
                type = type,
                amount = costSection.getDouble("amount", 0.0).coerceAtLeast(0.0),
                levelAmounts = parseLevelAmounts(costSection.getConfigurationSection("levels")),
                conditions = costSection.getStringList("conditions"),
            )
        }
        val price = section.get("price")?.toString()?.trim()?.takeIf { it.isNotBlank() } ?: return CostDefinition()
        val parts = price.split(':', limit = 2)
        if (parts.size != 2) {
            warn("shop.yml", "products.$productId.price", "商品 '$productId' 的 price 简写 '$price' 格式无效，应类似 POINTS:3000。")
            return CostDefinition()
        }
        return CostDefinition(
            type = parseCurrency(parts[0], "products.$productId.price"),
            amount = parts[1].trim().toDoubleOrNull()?.coerceAtLeast(0.0) ?: 0.0,
        )
    }

    private fun parseSubmitItems(section: ConfigurationSection?, productId: String): List<SubmitItemDefinition> {
        if (section == null) {
            return emptyList()
        }
        return section.getKeys(false).mapNotNull { key ->
            val itemPath = "products.$productId.submit-items.$key"
            val itemSection = section.getConfigurationSection(key)
            if (itemSection != null) {
                val material = itemSection.getString("material")?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
                validateSubmitMaterial(material, "$itemPath.material", productId)
                return@mapNotNull SubmitItemDefinition(
                    material = material,
                    amount = itemSection.getInt("amount", 1).coerceAtLeast(1),
                    name = itemSection.getString("name")?.takeIf { it.isNotBlank() },
                )
            }
            val material = key.trim()
            validateSubmitMaterial(material, itemPath, productId)
            SubmitItemDefinition(
                material = material,
                amount = parseAmount(section.get(key)).coerceAtLeast(1),
                name = null,
            )
        }
    }

    private fun parseAmount(raw: Any?): Int {
        return when (raw) {
            is Number -> raw.toInt()
            is String -> raw.trim().toIntOrNull() ?: 1
            else -> 1
        }
    }

    private fun parseLevelAmounts(section: ConfigurationSection?): Map<Int, Double> {
        if (section == null) {
            return emptyMap()
        }
        return section.getKeys(false).mapNotNull { key ->
            val level = key.toIntOrNull() ?: return@mapNotNull null
            level to section.getDouble(key, 0.0).coerceAtLeast(0.0)
        }.toMap()
    }

    private fun parseCurrency(raw: String?, path: String): CurrencyType {
        val normalized = raw?.trim()?.replace('-', '_')?.uppercase(Locale.ROOT)
        if (normalized.isNullOrBlank()) {
            return CurrencyType.POINTS
        }
        val parsed = CurrencyType.entries.firstOrNull { it.name == normalized }
        if (parsed == null) {
            warn("shop.yml", path, "货币类型 '$raw' 无效，已按 POINTS:0/POINTS 兜底。")
            return CurrencyType.POINTS
        }
        return parsed
    }

    private fun parseType(raw: String?, path: String): ShopProductType {
        if (raw.isNullOrBlank()) {
            return ShopProductType.TAG
        }
        return ShopProductType.entries.firstOrNull { it.name.equals(raw.trim(), ignoreCase = true) } ?: run {
            warn("shop.yml", path, "商品类型 '$raw' 无效，已按 TAG 兜底。")
            ShopProductType.TAG
        }
    }

    private fun parseMode(raw: String?, path: String): ShopProductMode {
        if (raw.isNullOrBlank()) {
            return ShopProductMode.BUY
        }
        return ShopProductMode.entries.firstOrNull { it.name.equals(raw.trim(), ignoreCase = true) } ?: run {
            warn("shop.yml", path, "商品模式 '$raw' 无效，已按 BUY 兜底。")
            ShopProductMode.BUY
        }
    }

    private fun validateSubmitMaterial(material: String, path: String, productId: String) {
        if (Material.matchMaterial(material.trim()) == null) {
            warn("shop.yml", path, "商品 '$productId' 的提交材料 '$material' 不是有效原版材质。")
        }
    }

    private fun warn(source: String, path: String, message: String) {
        issueSink(ConfigIssue(ConfigIssueSeverity.WARNING, source, path, message))
    }
}
