package cn.aing.uptags.config.shop

import cn.aing.uptags.model.config.ItemTemplate
import cn.aing.uptags.model.config.ShopProductDefinition
import cn.aing.uptags.model.config.ShopProductMode
import cn.aing.uptags.model.config.ShopProductType
import cn.aing.uptags.model.config.TagDefinition
import cn.aing.uptags.model.config.TagShopDefinition

class TagProductResolver(
    private val tags: Map<String, TagDefinition>,
    private val rarityDisplay: (String) -> String,
) {
    fun productFromLegacyTagShop(tag: TagDefinition, shop: TagShopDefinition): ShopProductDefinition {
        val defaultIcon = defaultIcon(tag)
        val icon = shop.icon?.let {
            ItemTemplate(
                material = it.material,
                name = it.name.takeIf(String::isNotBlank) ?: defaultIcon.name,
                lore = it.lore.ifEmpty { defaultIcon.lore },
            )
        } ?: defaultIcon
        return ShopProductDefinition(
            id = tag.id,
            type = ShopProductType.TAG,
            targetId = tag.id,
            mode = legacyShopMode(shop),
            category = null,
            enabled = shop.enabled,
            permission = shop.permission,
            conditions = shop.conditions,
            cost = shop.cost,
            submitItems = shop.submitItems,
            icon = icon,
        )
    }

    fun defaultProductForTag(tagId: String): ShopProductDefinition? {
        val tag = tags[tagId] ?: return null
        return ShopProductDefinition(
            id = tag.id,
            type = ShopProductType.TAG,
            targetId = tag.id,
            mode = ShopProductMode.BUY,
            category = null,
            enabled = true,
            permission = null,
            conditions = emptyList(),
            cost = cn.aing.uptags.model.config.CostDefinition(),
            submitItems = emptyList(),
            icon = defaultIcon(tag),
        )
    }

    fun defaultIcon(targetId: String, fallbackName: String): ItemTemplate {
        val tag = tags[targetId] ?: return ItemTemplate("NAME_TAG", fallbackName, emptyList())
        return defaultIcon(tag)
    }

    private fun defaultIcon(tag: TagDefinition): ItemTemplate =
        ItemTemplate(
            material = "NAME_TAG",
            name = tag.display,
            lore = tag.description,
        )

    fun legacyDefaultIcon(tag: TagDefinition): ItemTemplate =
        ItemTemplate(
            material = "NAME_TAG",
            name = tag.display,
            lore = buildList {
                addAll(tag.description)
                add(rarityDisplay(tag.rarity))
            },
        )

    private fun legacyShopMode(shop: TagShopDefinition): ShopProductMode {
        if (shop.submitItems.isNotEmpty()) {
            return ShopProductMode.ITEM_EXCHANGE
        }
        if (shop.conditions.isNotEmpty()) {
            return ShopProductMode.CHALLENGE_CLAIM
        }
        return ShopProductMode.BUY
    }
}
