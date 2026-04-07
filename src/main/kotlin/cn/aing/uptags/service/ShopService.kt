package cn.aing.uptags.service

import cn.aing.uptags.config.ConfigRegistry
import cn.aing.uptags.config.MessageService
import cn.aing.uptags.model.config.CurrencyType
import cn.aing.uptags.model.config.ShopProductDefinition
import cn.aing.uptags.model.config.ShopProductType
import org.bukkit.entity.Player

class ShopService(
    private val config: ConfigRegistry,
    private val tagService: TagService,
    private val economyBridge: EconomyBridge,
    private val customTitleService: CustomTitleService,
    private val clickableMessageService: ClickableMessageService,
    private val messageService: MessageService,
) {
    fun visibleProducts(player: Player): List<ShopProductDefinition> {
        return config.shopProducts.values.filter { product ->
            product.enabled && hasPermission(player, product) && tagService.checkConditions(player, product.conditions)
        }
    }

    fun buy(player: Player, productId: String): Boolean {
        val product = config.shopProducts[productId] ?: return false
        if (!product.enabled || !hasPermission(player, product)) {
            messageService.send(player, "shop-not-available")
            return false
        }
        if (!tagService.checkConditions(player, product.conditions)) {
            messageService.send(player, "condition-failed")
            return false
        }
        return when (product.type) {
            ShopProductType.TAG -> {
                if (tagService.isOwned(player, product.targetId)) {
                    messageService.send(player, "tag-already-owned", tagService.tagName(product.targetId))
                    return false
                }
                val price = product.cost.priceForLevel(1)
                if (!economyBridge.isAvailable(product.cost.type)) {
                    messageService.send(player, "economy-unavailable", economyBridge.displayName(product.cost.type))
                    return false
                }
                if (economyBridge.balance(player, product.cost.type) < price || !economyBridge.withdraw(player, product.cost.type, price)) {
                    messageService.send(player, "not-enough", price, economyBridge.displayName(product.cost.type))
                    return false
                }
                val success = tagService.giveTag(player, product.targetId)
                if (success) {
                    messageService.send(player, "shop-tag-bought", tagService.tagName(product.targetId), economyBridge.displayName(product.cost.type), price)
                }
                success
            }
            ShopProductType.CUSTOM -> {
                val success = customTitleService.startDraft(player, product.targetId)
                if (success) {
                    messageService.send(player, "shop-custom-start")
                    clickableMessageService.sendCurrencyChoices(player)
                }
                success
            }
        }
    }

    fun currencyDisplay(type: CurrencyType): String = economyBridge.displayName(type)

    private fun hasPermission(player: Player, product: ShopProductDefinition): Boolean {
        val permission = product.permission?.takeIf { it.isNotBlank() } ?: return true
        return player.hasPermission(permission) || player.hasPermission("uptags.shop.*")
    }
}
