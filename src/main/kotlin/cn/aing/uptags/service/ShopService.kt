package cn.aing.uptags.service

import cn.aing.uptags.Support
import cn.aing.uptags.config.ConfigRegistry
import cn.aing.uptags.config.MessageService
import cn.aing.uptags.model.config.CurrencyType
import cn.aing.uptags.model.config.ShopProductDefinition
import cn.aing.uptags.model.config.ShopProductType
import org.bukkit.entity.Player

class ShopService(
    private val config: ConfigRegistry,
    private val tagService: TagService,
    private val customTitleService: CustomTitleService,
    private val economyBridge: EconomyBridge,
    private val messageService: MessageService,
) {
    fun visibleProducts(player: Player): List<ShopProductDefinition> {
        return config.shopProducts.values.filter { product ->
            product.enabled &&
                hasPermission(player, product) &&
                tagService.checkConditions(player, product.conditions)
        }
    }

    fun visibleCustomProducts(player: Player): List<ShopProductDefinition> {
        return visibleProducts(player).filter { it.type == ShopProductType.CUSTOM }
    }

    fun buy(player: Player, productId: String): Boolean {
        val product = validateProduct(player, productId, ShopProductType.TAG) ?: return false
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
            messageService.send(player, "not-enough", Support.formatDouble(price), economyBridge.displayName(product.cost.type))
            return false
        }
        val success = tagService.giveTag(player, product.targetId)
        if (success) {
            messageService.send(
                player,
                "shop-tag-bought",
                tagService.tagName(product.targetId),
                economyBridge.displayName(product.cost.type),
                Support.formatDouble(price),
            )
        }
        return success
    }

    fun startCustomFlow(player: Player, productId: String): Boolean {
        val product = validateProduct(player, productId, ShopProductType.CUSTOM) ?: return false
        val price = product.cost.priceForLevel(1)
        if (!economyBridge.isAvailable(product.cost.type)) {
            messageService.send(player, "economy-unavailable", economyBridge.displayName(product.cost.type))
            return false
        }
        if (economyBridge.balance(player, product.cost.type) < price) {
            messageService.send(player, "not-enough", Support.formatDouble(price), economyBridge.displayName(product.cost.type))
            return false
        }

        customTitleService.cancelDraft(player, notify = false)
        if (!customTitleService.startProductDraft(player, product.targetId, product.cost.type, price, product.id)) {
            messageService.send(player, "custom-title-invalid-preset")
            return false
        }
        messageService.send(
            player,
            "shop-custom-selected",
            Support.stripColor(product.icon.name),
            Support.formatDouble(price),
            economyBridge.displayName(product.cost.type),
        )
        messageService.send(player, "custom-title-input")
        return true
    }

    fun currencyDisplay(type: CurrencyType): String = economyBridge.displayName(type)

    private fun validateProduct(
        player: Player,
        productId: String,
        expectedType: ShopProductType,
    ): ShopProductDefinition? {
        val product = config.shopProducts[productId] ?: return null
        if (!product.enabled || !hasPermission(player, product)) {
            messageService.send(player, "shop-not-available")
            return null
        }
        if (!tagService.checkConditions(player, product.conditions)) {
            messageService.send(player, "condition-failed")
            return null
        }
        if (product.type != expectedType) {
            messageService.send(player, "shop-not-available")
            return null
        }
        return product
    }

    private fun hasPermission(player: Player, product: ShopProductDefinition): Boolean {
        val permission = product.permission?.takeIf { it.isNotBlank() } ?: return true
        return player.hasPermission(permission) || player.hasPermission("uptags.shop.*")
    }
}
