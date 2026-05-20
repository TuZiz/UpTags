package cn.aing.uptags.service.shop

import cn.aing.uptags.Support
import cn.aing.uptags.command.admin.AdminAccess
import cn.aing.uptags.config.ConfigRegistry
import cn.aing.uptags.config.MessageService
import cn.aing.uptags.model.config.CurrencyType
import cn.aing.uptags.model.config.ShopProductDefinition
import cn.aing.uptags.model.config.ShopProductMode
import cn.aing.uptags.model.config.ShopProductType
import cn.aing.uptags.model.config.SubmitItemDefinition
import cn.aing.uptags.model.runtime.PurchaseOrderData
import cn.aing.uptags.model.runtime.PurchaseOrderStatus
import cn.aing.uptags.repository.SaveResult
import cn.aing.uptags.service.economy.EconomyBridge
import cn.aing.uptags.service.tag.TagService
import cn.aing.uptags.service.title.CustomTitleService
import org.bukkit.Material
import org.bukkit.entity.Player
import org.bukkit.inventory.ItemStack
import java.util.UUID

class ShopService(
    private val config: ConfigRegistry,
    private val tagService: TagService,
    private val customTitleService: CustomTitleService,
    private val economyBridge: EconomyBridge,
    private val messageService: MessageService,
    private val challengeProgressService: ChallengeProgressService,
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

    fun recoverPendingOrders(player: Player) {
        val data = tagService.requireLoaded(player) ?: return
        data.purchaseOrders.values
            .filter { it.status == PurchaseOrderStatus.PENDING || it.status == PurchaseOrderStatus.PAID || it.status == PurchaseOrderStatus.REFUND_PENDING }
            .forEach { order ->
                val product = config.shopProducts[order.productId] ?: return@forEach
                if (order.status == PurchaseOrderStatus.PENDING) {
                    order.fail("interrupted-before-payment")
                    tagService.recordPurchaseOrderStrict(player, order) {}
                    return@forEach
                }
                if (order.status == PurchaseOrderStatus.PAID || order.status == PurchaseOrderStatus.REFUND_PENDING) {
                    val refunded = economyBridge.refund(player, order.currencyType, order.currencyAmount)
                    order.status = if (refunded) PurchaseOrderStatus.FAILED else PurchaseOrderStatus.REFUND_PENDING
                    order.failureReason = "recovered-after-restart"
                    order.updatedAt = System.currentTimeMillis()
                    tagService.recordPurchaseOrderStrict(player, order) {}
                }
            }
    }

    fun buy(player: Player, productId: String): Boolean {
        val product = validateProduct(player, productId, ShopProductType.TAG) ?: return false
        if (tagService.isOwned(player, product.targetId)) {
            messageService.send(player, "tag-already-owned", tagService.tagName(product.targetId))
            return false
        }

        val price = product.cost.priceForLevel(1)
        if (product.mode == ShopProductMode.ITEM_EXCHANGE && product.submitItems.isEmpty()) {
            messageService.send(player, "shop-not-available")
            return false
        }
        if ((product.mode == ShopProductMode.SEASONAL || product.mode == ShopProductMode.PRESTIGE) && product.conditions.isEmpty()) {
            messageService.send(player, "shop-not-available")
            return false
        }
        if (!validatePaymentAndSubmitItems(player, product, price)) {
            return false
        }
        if (product.mode == ShopProductMode.CHALLENGE_CLAIM && !challengeProgressService.canClaim(player, product.conditions)) {
            messageService.send(player, "condition-failed")
            return false
        }

        val order = PurchaseOrderData(
            orderId = UUID.randomUUID().toString(),
            productId = product.id,
            targetId = product.targetId,
            status = PurchaseOrderStatus.PENDING,
            currencyType = product.cost.type,
            currencyAmount = price,
            submittedItems = product.submitItems.map { "${it.amount}x${it.material}" }.toMutableList(),
        )
        tagService.recordPurchaseOrderStrict(player, order) { result ->
            if (result is SaveResult.Success) {
                processPendingOrder(player, product, order)
            } else {
                messageService.send(player, "shop-purchase-save-failed")
            }
        }
        messageService.send(player, "shop-purchase-pending")
        return true
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

    private fun validatePaymentAndSubmitItems(player: Player, product: ShopProductDefinition, price: Double): Boolean {
        if (price > 0.0) {
            if (!economyBridge.isAvailable(product.cost.type)) {
                messageService.send(player, "economy-unavailable", economyBridge.displayName(product.cost.type))
                return false
            }
            if (economyBridge.balance(player, product.cost.type) < price) {
                messageService.send(player, "not-enough", Support.formatDouble(price), economyBridge.displayName(product.cost.type))
                return false
            }
        }
        if (product.submitItems.isNotEmpty() && !hasSubmitItems(player, product.submitItems)) {
            messageService.send(player, "shop-submit-items-missing", submitItemsDisplay(product.submitItems))
            return false
        }
        return true
    }

    private fun hasPermission(player: Player, product: ShopProductDefinition): Boolean {
        val permission = product.permission?.takeIf { it.isNotBlank() } ?: return true
        return player.hasPermission(AdminAccess.ADMIN) || player.hasPermission(permission) || player.hasPermission("uptags.shop.*")
    }

    private fun canUseProductConditions(player: Player, product: ShopProductDefinition): Boolean {
        return player.hasPermission(AdminAccess.ADMIN) || tagService.checkConditions(player, product.conditions)
    }

    private fun processPendingOrder(player: Player, product: ShopProductDefinition, order: PurchaseOrderData) {
        val price = order.currencyAmount
        val deductedItems = ArrayList<ItemStack>()
        var currencyTaken = false
        try {
            if (product.submitItems.isNotEmpty() && !takeSubmitItems(player, product.submitItems, deductedItems)) {
                order.fail("submit-items-missing")
                tagService.recordPurchaseOrderStrict(player, order) {}
                messageService.send(player, "shop-submit-items-missing", submitItemsDisplay(product.submitItems))
                return
            }
            order.submittedItems.clear()
            order.submittedItems.addAll(deductedItems.map(::encodeStack))
            if (price > 0.0) {
                if (!economyBridge.withdraw(player, product.cost.type, price)) {
                    restoreDeductedItems(player, deductedItems)
                    order.compensatedItems.addAll(deductedItems.map(::encodeStack))
                    order.fail("currency-withdraw-failed")
                    tagService.recordPurchaseOrderStrict(player, order) {}
                    messageService.send(player, "not-enough", Support.formatDouble(price), economyBridge.displayName(product.cost.type))
                    return
                }
                currencyTaken = true
                order.status = PurchaseOrderStatus.PAID
                order.updatedAt = System.currentTimeMillis()
                tagService.recordPurchaseOrderStrict(player, order) { paidResult ->
                    if (paidResult !is SaveResult.Success) {
                        order.failOrRefundPending(compensate(player, deductedItems, currencyTaken, product, price), "paid-save-failed")
                        tagService.recordPurchaseOrderStrict(player, order) {}
                    }
                }
            }
            if (!tagService.grantTagNoSave(player, product.targetId)) {
                order.failOrRefundPending(compensate(player, deductedItems, currencyTaken, product, price), "grant-failed")
                tagService.recordPurchaseOrderStrict(player, order) {}
                messageService.send(player, "shop-not-available")
                return
            }
            val data = tagService.data(player)
            order.status = PurchaseOrderStatus.GRANTED
            order.updatedAt = System.currentTimeMillis()
            data.purchaseOrders[order.orderId] = order.copyDeep()
            tagService.saveStrict(data) { grantedResult ->
                if (grantedResult is SaveResult.Success) {
                    sendPurchaseSuccess(player, product, price)
                } else {
                    order.failOrRefundPending(compensate(player, deductedItems, currencyTaken, product, price), "granted-save-failed")
                    tagService.recordPurchaseOrderStrict(player, order) {}
                    messageService.send(player, "shop-purchase-refund-pending")
                }
            }
        } catch (ex: RuntimeException) {
            order.failOrRefundPending(compensate(player, deductedItems, currencyTaken, product, price), ex.message)
            tagService.recordPurchaseOrderStrict(player, order) {}
            throw ex
        }
    }

    private fun takeSubmitItems(player: Player, requirements: List<SubmitItemDefinition>, deducted: MutableList<ItemStack>): Boolean {
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
                val takenStack = stack.clone()
                takenStack.amount = taken
                deducted += takenStack
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

    private fun compensate(
        player: Player,
        deductedItems: List<ItemStack>,
        currencyTaken: Boolean,
        product: ShopProductDefinition,
        price: Double,
    ): Boolean {
        var ok = true
        if (deductedItems.isNotEmpty()) {
            restoreDeductedItems(player, deductedItems)
        }
        if (currencyTaken && !economyBridge.refund(player, product.cost.type, price)) {
            ok = false
        }
        return ok
    }

    private fun restoreDeductedItems(player: Player, deductedItems: List<ItemStack>) {
        deductedItems.forEach { stack ->
            val leftovers = player.inventory.addItem(stack.clone())
            leftovers.values.forEach { leftover ->
                player.world.dropItemNaturally(player.location, leftover)
            }
        }
    }

    private fun encodeStack(stack: ItemStack): String = "${stack.amount}x${stack.type.name.lowercase()}"

    private fun sendPurchaseSuccess(player: Player, product: ShopProductDefinition, price: Double) {
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
            Material.SLIME_BALL -> "粘液球"
            else -> raw.lowercase()
                .split('_')
                .filter(String::isNotBlank)
                .joinToString(" ") { it.replaceFirstChar(Char::uppercaseChar) }
        }
    }

    private fun PurchaseOrderData.fail(reason: String?) {
        status = PurchaseOrderStatus.FAILED
        failureReason = reason
        updatedAt = System.currentTimeMillis()
    }

    private fun PurchaseOrderData.failOrRefundPending(compensated: Boolean, reason: String?) {
        status = if (compensated) PurchaseOrderStatus.FAILED else PurchaseOrderStatus.REFUND_PENDING
        failureReason = reason
        updatedAt = System.currentTimeMillis()
    }
}
