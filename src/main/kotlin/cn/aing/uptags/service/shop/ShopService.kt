package cn.aing.uptags.service.shop

import cn.aing.uptags.Support
import cn.aing.uptags.command.admin.AdminAccess
import cn.aing.uptags.config.ConfigRegistry
import cn.aing.uptags.config.MessageService
import cn.aing.uptags.model.config.CurrencyType
import cn.aing.uptags.model.config.ShopProductDefinition
import cn.aing.uptags.model.config.ShopProductType
import cn.aing.uptags.model.config.SubmitItemDefinition
import cn.aing.uptags.service.economy.EconomyBridge
import cn.aing.uptags.service.tag.TagService
import cn.aing.uptags.service.title.CustomTitleService
import org.bukkit.Material
import org.bukkit.entity.Player
import org.bukkit.inventory.ItemStack

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
                canUseProductConditions(player, product)
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
        if (price > 0.0) {
            if (!economyBridge.isAvailable(product.cost.type)) {
                messageService.send(player, "economy-unavailable", economyBridge.displayName(product.cost.type))
                return false
            }
            if (economyBridge.balance(player, product.cost.type) < price || !economyBridge.withdraw(player, product.cost.type, price)) {
                messageService.send(player, "not-enough", Support.formatDouble(price), economyBridge.displayName(product.cost.type))
                return false
            }
        }
        if (product.submitItems.isNotEmpty() && !takeSubmitItems(player, product.submitItems)) {
            messageService.send(player, "shop-submit-items-missing", submitItemsDisplay(product.submitItems))
            return false
        }
        val success = tagService.giveTag(player, product.targetId)
        if (success) {
            if (product.submitItems.isNotEmpty()) {
                messageService.send(player, "shop-tag-unlocked-submit", tagService.tagName(product.targetId))
            } else if (price > 0.0) {
                messageService.send(
                    player,
                    "shop-tag-bought",
                    tagService.tagName(product.targetId),
                    economyBridge.displayName(product.cost.type),
                    Support.formatDouble(price),
                )
            } else {
                messageService.send(player, "shop-tag-unlocked", tagService.tagName(product.targetId))
            }
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

    fun requirementDisplay(product: ShopProductDefinition): String {
        val parts = ArrayList<String>()
        val price = product.cost.priceForLevel(1)
        if (price > 0.0) {
            parts += "${Support.formatDouble(price)} ${currencyDisplay(product.cost.type)}"
        }
        if (product.conditions.isNotEmpty()) {
            parts += "完成条件"
        }
        if (product.submitItems.isNotEmpty()) {
            parts += submitItemsDisplay(product.submitItems)
        }
        return parts.ifEmpty { listOf("完成后领取") }.joinToString(" / ")
    }

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
        if (!canUseProductConditions(player, product)) {
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
        return player.hasPermission(AdminAccess.ADMIN) || player.hasPermission(permission) || player.hasPermission("uptags.shop.*")
    }

    private fun canUseProductConditions(player: Player, product: ShopProductDefinition): Boolean {
        return player.hasPermission(AdminAccess.ADMIN) || tagService.checkConditions(player, product.conditions)
    }

    private fun takeSubmitItems(player: Player, requirements: List<SubmitItemDefinition>): Boolean {
        if (!hasSubmitItems(player, requirements)) {
            return false
        }
        requirements.forEach { requirement ->
            var remaining = requirement.amount
            val contents = player.inventory.storageContents
            contents.forEachIndexed { index, stack ->
                if (remaining <= 0 || stack == null || !matchesSubmitItem(stack, requirement)) {
                    return@forEachIndexed
                }
                val taken = minOf(stack.amount, remaining)
                stack.amount -= taken
                remaining -= taken
                if (stack.amount <= 0) {
                    contents[index] = null
                }
            }
            player.inventory.storageContents = contents
        }
        return true
    }

    private fun hasSubmitItems(player: Player, requirements: List<SubmitItemDefinition>): Boolean {
        return requirements.all { requirement ->
            player.inventory.storageContents
                .filterNotNull()
                .filter { matchesSubmitItem(it, requirement) }
                .sumOf { it.amount } >= requirement.amount
        }
    }

    private fun matchesSubmitItem(stack: ItemStack, requirement: SubmitItemDefinition): Boolean {
        val material = Material.matchMaterial(requirement.material) ?: return false
        if (stack.type != material) {
            return false
        }
        val expectedName = requirement.name?.takeIf { it.isNotBlank() } ?: return true
        val actualName = stack.itemMeta?.displayName ?: return false
        return Support.stripColor(actualName).equals(Support.stripColor(expectedName), ignoreCase = true)
    }

    private fun submitItemsDisplay(items: List<SubmitItemDefinition>): String {
        return items.joinToString(", ") { item ->
            val name = item.name?.takeIf { it.isNotBlank() } ?: materialDisplayName(item.material)
            "${item.amount}x ${Support.stripColor(name)}"
        }
    }

    private fun materialDisplayName(raw: String): String {
        val material = Material.matchMaterial(raw)
        return when (material) {
            Material.REDSTONE -> "红石粉"
            Material.REPEATER -> "红石中继器"
            Material.WHEAT -> "小麦"
            Material.CARROT -> "胡萝卜"
            Material.TORCH -> "火把"
            Material.CHEST -> "箱子"
            Material.BARREL -> "木桶"
            Material.BREAD -> "面包"
            Material.SLIME_BALL -> "黏液球"
            else -> raw.lowercase()
                .split('_')
                .filter(String::isNotBlank)
                .joinToString(" ") { it.replaceFirstChar(Char::uppercaseChar) }
        }
    }
}
