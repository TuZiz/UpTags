package cn.aing.uptags.service.shop

import cn.aing.uptags.Support
import cn.aing.uptags.command.admin.AdminAccess
import cn.aing.uptags.compat.PlatformScheduler
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
import java.util.Locale
import java.util.UUID

class ShopService(
    private val config: ConfigRegistry,
    private val tagService: TagService,
    private val customTitleService: CustomTitleService,
    private val economyBridge: EconomyBridge,
    private val messageService: MessageService,
    private val challengeProgressService: ChallengeProgressService,
    private val scheduler: PlatformScheduler,
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
        val recoverable = data.purchaseOrders.values
            .filter { it.status in recoverableStatuses }
            .map { it.copyDeep() }
        if (recoverable.isEmpty()) {
            return
        }
        scheduler.runPlayerOrRetired(player, retired = {}) {
            if (!player.isOnline) {
                return@runPlayerOrRetired
            }
            recoverable.forEach { order -> recoverOrderOnPlayerThread(player, order) }
        }
    }

    fun buy(player: Player, productId: String): Boolean {
        val product = validateProduct(player, productId, ShopProductType.TAG) ?: return false
        if (tagService.isOwned(player, product.targetId)) {
            messageService.sendThrottled(player, "tag-already-owned", tagService.tagName(product.targetId))
            return false
        }

        val price = product.cost.priceForLevel(1)
        if (product.mode == ShopProductMode.ITEM_EXCHANGE && product.submitItems.isEmpty()) {
            messageService.sendThrottled(player, "shop-not-available")
            return false
        }
        if ((product.mode == ShopProductMode.SEASONAL || product.mode == ShopProductMode.PRESTIGE) && product.conditions.isEmpty()) {
            messageService.sendThrottled(player, "shop-not-available")
            return false
        }
        if (!validatePaymentAndSubmitItems(player, product, price)) {
            return false
        }
        if (product.mode == ShopProductMode.CHALLENGE_CLAIM && !challengeProgressService.canClaim(player, product.conditions, "shop.yml products.${product.id}.conditions")) {
            messageService.sendThrottled(player, "condition-failed")
            return false
        }

        val order = PurchaseOrderData(
            orderId = UUID.randomUUID().toString(),
            productId = product.id,
            targetId = product.targetId,
            status = PurchaseOrderStatus.PENDING,
            currencyType = product.cost.type,
            currencyAmount = price,
            submittedItems = mutableListOf(),
        )
        saveOrderStrict(player, order) { result ->
            if (result is SaveResult.Success) {
                runOnlinePlayer(player) {
                    takeItemsStage(player, product, order)
                }
            } else {
                runOnlinePlayer(player) {
                    messageService.sendThrottled(player, "shop-purchase-save-failed")
                }
            }
        }
        messageService.sendThrottled(player, "shop-purchase-pending")
        return true
    }

    fun startCustomFlow(player: Player, productId: String): Boolean {
        val product = validateProduct(player, productId, ShopProductType.CUSTOM) ?: return false
        val price = product.cost.priceForLevel(1)
        if (!economyBridge.isAvailable(product.cost.type)) {
            messageService.sendThrottled(player, "economy-unavailable", economyBridge.displayName(product.cost.type))
            return false
        }
        if (economyBridge.balance(player, product.cost.type) < price) {
            messageService.sendThrottled(player, "not-enough", Support.formatDouble(price), economyBridge.displayName(product.cost.type))
            return false
        }

        customTitleService.cancelDraft(player, notify = false)
        if (!customTitleService.startProductDraft(player, product.targetId, product.cost.type, price, product.id)) {
            messageService.sendThrottled(player, "custom-title-invalid-preset")
            return false
        }
        messageService.sendThrottled(
            player,
            "shop-custom-selected",
            Support.stripColor(product.icon.name),
            Support.formatDouble(price),
            economyBridge.displayName(product.cost.type),
        )
        messageService.sendThrottled(player, "custom-title-input")
        return true
    }

    fun currencyDisplay(type: CurrencyType): String = economyBridge.displayName(type)

    fun requirementDisplay(product: ShopProductDefinition): String {
        val parts = ArrayList<String>()
        val price = product.cost.priceForLevel(1)
        if (price > 0.0) {
            parts += config.shopText(
                "shop.requirement.price",
                mapOf(
                    "amount" to Support.formatDouble(price),
                    "currency" to currencyDisplay(product.cost.type),
                ),
            )
        }
        if (product.conditions.isNotEmpty()) {
            parts += config.shopText("shop.requirement.conditions")
        }
        if (product.submitItems.isNotEmpty()) {
            parts += submitItemRequirementLines(product.submitItems)
        }
        return parts.ifEmpty { listOf(config.shopText("shop.requirement.empty")) }
            .joinToString(config.shopText("shop.requirement.separator"))
    }

    fun requirementDisplay(player: Player, product: ShopProductDefinition): String {
        val parts = ArrayList<String>()
        val price = product.cost.priceForLevel(1)
        if (price > 0.0) {
            parts += config.shopText(
                "shop.requirement.price",
                mapOf(
                    "amount" to Support.formatDouble(price),
                    "currency" to currencyDisplay(product.cost.type),
                ),
            )
        }
        val challengeParts = product.conditions
            .filter(::isChallengeCondition)
            .mapNotNull { challengeProgressDisplay(player, it) }
        if (challengeParts.isNotEmpty()) {
            parts += challengeParts
        } else if (product.conditions.isNotEmpty()) {
            parts += config.shopText("shop.requirement.conditions")
        }
        if (product.submitItems.isNotEmpty()) {
            parts += submitItemsProgressDisplay(player, product.submitItems)
        }
        return parts.ifEmpty { listOf(config.shopText("shop.requirement.empty")) }
            .joinToString(config.shopText("shop.requirement.separator"))
    }

    private fun takeItemsStage(player: Player, product: ShopProductDefinition, order: PurchaseOrderData) {
        if (!player.isOnline) {
            return
        }
        if (tagService.isOwned(player, product.targetId)) {
            order.fail("already-owned-during-purchase")
            saveOrderStrict(player, order) {}
            return
        }
        val deductedItems = ArrayList<ItemStack>()
        if (product.submitItems.isNotEmpty() && !takeSubmitItems(player, product.submitItems, deductedItems)) {
            order.fail("submit-items-missing")
            saveOrderStrict(player, order) {}
            messageService.sendThrottled(player, "shop-submit-items-missing", submitItemsDisplay(product.submitItems))
            return
        }
        order.status = PurchaseOrderStatus.ITEMS_TAKEN
        order.submittedItems.clear()
        order.submittedItems.addAll(deductedItems.map(::encodeStack))
        order.touch()
        saveOrderStrict(player, order) { result ->
            if (result is SaveResult.Success) {
                runOnlinePlayer(player) { payStage(player, product, order) }
            } else {
                runOnlinePlayer(player) {
                    val compensated = restoreSubmittedItems(player, order)
                    order.failOrRefundPending(compensated, "items-taken-save-failed")
                    saveOrderStrict(player, order) {}
                    messageService.sendThrottled(player, "shop-purchase-refund-pending")
                }
            }
        }
    }

    private fun payStage(player: Player, product: ShopProductDefinition, order: PurchaseOrderData) {
        if (!player.isOnline) {
            return
        }
        val price = order.currencyAmount
        if (price > 0.0 && !economyBridge.withdraw(player, order.currencyType, price)) {
            val compensated = restoreSubmittedItems(player, order)
            order.failOrRefundPending(compensated, "currency-withdraw-failed")
            saveOrderStrict(player, order) {}
            messageService.sendThrottled(player, "not-enough", Support.formatDouble(price), economyBridge.displayName(order.currencyType))
            return
        }
        order.status = PurchaseOrderStatus.PAID
        order.touch()
        saveOrderStrict(player, order) { paidResult ->
            if (paidResult is SaveResult.Success) {
                runOnlinePlayer(player) { markGrantingStage(player, product, order) }
            } else {
                runOnlinePlayer(player) {
                    val compensated = refundAndRestore(player, order, restoreItems = true, refundCurrency = price > 0.0)
                    order.failOrRefundPending(compensated, "paid-save-failed")
                    saveOrderStrict(player, order) {}
                    messageService.sendThrottled(player, "shop-purchase-refund-pending")
                }
            }
        }
    }

    private fun markGrantingStage(player: Player, product: ShopProductDefinition, order: PurchaseOrderData) {
        order.status = PurchaseOrderStatus.GRANTING
        order.touch()
        saveOrderStrict(player, order) { grantingResult ->
            if (grantingResult is SaveResult.Success) {
                runOnlinePlayer(player) { grantStage(player, product, order) }
            } else {
                runOnlinePlayer(player) {
                    val compensated = refundAndRestore(player, order, restoreItems = true, refundCurrency = order.currencyAmount > 0.0)
                    order.failOrRefundPending(compensated, "granting-save-failed")
                    saveOrderStrict(player, order) {}
                    messageService.sendThrottled(player, "shop-purchase-refund-pending")
                }
            }
        }
    }

    private fun grantStage(player: Player, product: ShopProductDefinition, order: PurchaseOrderData) {
        if (!player.isOnline) {
            return
        }
        if (!tagService.grantTagNoSave(player, product.targetId)) {
            val compensated = refundAndRestore(player, order, restoreItems = true, refundCurrency = order.currencyAmount > 0.0)
            order.failOrRefundPending(compensated, "grant-failed")
            saveOrderStrict(player, order) {}
            messageService.sendThrottled(player, "shop-purchase-refund-pending")
            return
        }
        order.status = PurchaseOrderStatus.GRANTED
        order.touch()
        saveOrderWithCurrentDataStrict(player, order) { grantedResult ->
            if (grantedResult is SaveResult.Success) {
                runOnlinePlayer(player) { sendPurchaseSuccess(player, product, order.currencyAmount) }
            } else {
                runOnlinePlayer(player) {
                    revokeGrantedTagNoSave(player, order.targetId)
                    val compensated = refundAndRestore(player, order, restoreItems = true, refundCurrency = order.currencyAmount > 0.0)
                    order.failOrRefundPending(compensated, "granted-save-failed")
                    saveOrderStrict(player, order) {}
                    messageService.sendThrottled(player, "shop-purchase-refund-pending")
                }
            }
        }
    }

    private fun recoverOrderOnPlayerThread(player: Player, order: PurchaseOrderData) {
        if (!player.isOnline) {
            return
        }
        when (order.status) {
            PurchaseOrderStatus.PENDING -> {
                order.fail("interrupted-before-items")
                saveOrderStrict(player, order) {}
            }
            PurchaseOrderStatus.ITEMS_TAKEN -> {
                if (order.submittedItems.isEmpty()) {
                    order.status = PurchaseOrderStatus.REFUND_PENDING
                    order.failureReason = "missing-submitted-items-for-recovery"
                    order.touch()
                    saveOrderStrict(player, order) {}
                } else {
                    val restored = restoreSubmittedItems(player, order)
                    order.status = if (restored) PurchaseOrderStatus.REFUNDED else PurchaseOrderStatus.REFUND_PENDING
                    order.failureReason = "recovered-items-taken"
                    order.touch()
                    saveOrderStrict(player, order) {}
                }
            }
            PurchaseOrderStatus.PAID,
            PurchaseOrderStatus.GRANTING,
            -> {
                if (tagService.isOwned(player, order.targetId)) {
                    order.status = PurchaseOrderStatus.GRANTED
                    order.failureReason = null
                    order.touch()
                    saveOrderStrict(player, order) {}
                } else {
                    refundPendingOrder(player, order, "recovered-after-paid")
                }
            }
            PurchaseOrderStatus.REFUND_PENDING -> refundPendingOrder(player, order, "retry-refund")
            PurchaseOrderStatus.GRANTED,
            PurchaseOrderStatus.FAILED,
            PurchaseOrderStatus.REFUNDED,
            -> Unit
        }
    }

    private fun refundPendingOrder(player: Player, order: PurchaseOrderData, reason: String) {
        val restored = refundAndRestore(
            player = player,
            order = order,
            restoreItems = order.submittedItems.isNotEmpty(),
            refundCurrency = order.currencyAmount > 0.0,
        )
        order.status = if (restored) PurchaseOrderStatus.REFUNDED else PurchaseOrderStatus.REFUND_PENDING
        order.failureReason = reason
        order.touch()
        saveOrderStrict(player, order) {}
    }

    private fun validateProduct(
        player: Player,
        productId: String,
        expectedType: ShopProductType,
    ): ShopProductDefinition? {
        val product = config.shopProducts[productId] ?: return null
        if (!product.enabled || !hasPermission(player, product)) {
            messageService.sendThrottled(player, "shop-not-available")
            return null
        }
        if (!canUseProductConditions(player, product)) {
            messageService.sendThrottled(player, "condition-failed")
            return null
        }
        if (product.type != expectedType) {
            messageService.sendThrottled(player, "shop-not-available")
            return null
        }
        return product
    }

    private fun validatePaymentAndSubmitItems(player: Player, product: ShopProductDefinition, price: Double): Boolean {
        if (price > 0.0) {
            if (!economyBridge.isAvailable(product.cost.type)) {
                messageService.sendThrottled(player, "economy-unavailable", economyBridge.displayName(product.cost.type))
                return false
            }
            if (economyBridge.balance(player, product.cost.type) < price) {
                messageService.sendThrottled(player, "not-enough", Support.formatDouble(price), economyBridge.displayName(product.cost.type))
                return false
            }
        }
        if (product.submitItems.isNotEmpty() && !hasSubmitItems(player, product.submitItems)) {
            messageService.sendThrottled(player, "shop-submit-items-missing", submitItemsDisplay(product.submitItems))
            return false
        }
        return true
    }

    private fun hasPermission(player: Player, product: ShopProductDefinition): Boolean {
        val permission = product.permission?.takeIf { it.isNotBlank() } ?: return true
        return player.hasPermission(AdminAccess.ADMIN) || player.hasPermission(permission) || player.hasPermission("uptags.shop.*")
    }

    private fun canUseProductConditions(player: Player, product: ShopProductDefinition): Boolean {
        if (player.hasPermission(AdminAccess.ADMIN)) {
            return true
        }
        val regularConditions = product.conditions.filterNot(::isChallengeCondition)
        if (regularConditions.isNotEmpty() && !tagService.checkConditions(player, regularConditions)) {
            return false
        }
        val challengeConditions = product.conditions.filter(::isChallengeCondition)
        return challengeConditions.isEmpty() ||
            challengeProgressService.canClaim(player, challengeConditions, "shop.yml products.${product.id}.conditions")
    }

    private fun isChallengeCondition(condition: String): Boolean =
        condition.trim().startsWith("challenge:", ignoreCase = true)

    private fun runOnlinePlayer(player: Player, task: () -> Unit) {
        scheduler.runPlayerOrRetired(player, retired = {}) {
            if (player.isOnline) {
                task()
            }
        }
    }

    private fun saveOrderStrict(player: Player, order: PurchaseOrderData, callback: (SaveResult) -> Unit) {
        tagService.recordPurchaseOrderStrict(player, order.copyDeep(), callback)
    }

    private fun saveOrderWithCurrentDataStrict(player: Player, order: PurchaseOrderData, callback: (SaveResult) -> Unit) {
        val data = tagService.data(player)
        data.purchaseOrders[order.orderId] = order.copyDeep()
        tagService.saveStrict(data, callback)
    }

    private fun takeSubmitItems(player: Player, requirements: List<SubmitItemDefinition>, deducted: MutableList<ItemStack>): Boolean {
        if (!hasSubmitItems(player, requirements)) {
            return false
        }
        val contents = player.inventory.storageContents
        requirements.forEach { requirement ->
            var remaining = requirement.amount
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
        }
        player.inventory.storageContents = contents
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

    private fun challengeProgressDisplay(player: Player, condition: String): String? {
        val parsed = parseChallengeCondition(condition) ?: return null
        val current = challengeProgressService.progress(player, parsed.key).coerceAtLeast(0L)
        val capped = current.coerceAtMost(parsed.required)
        return config.shopText(
            "shop.requirement.progress",
            mapOf(
                "name" to parsed.display,
                "current" to formatProgress(capped),
                "required" to formatProgress(parsed.required),
            ),
        )
    }

    private fun parseChallengeCondition(condition: String): ChallengeRequirement? {
        val parts = condition.trim().split(':')
        if (parts.size < 4 || !parts[0].equals("challenge", ignoreCase = true)) {
            return null
        }
        val required = parts.last().toLongOrNull()?.coerceAtLeast(0L) ?: return null
        val key = parts.dropLast(1).joinToString(":").lowercase(Locale.ROOT)
        val type = parts.getOrNull(1)?.lowercase(Locale.ROOT) ?: return null
        val target = keyDisplayName(parts.getOrNull(2).orEmpty())
        val display = when (type) {
            "mine" -> config.shopText("shop.challenge.mine", mapOf("target" to target))
            "collect" -> config.shopText("shop.challenge.collect", mapOf("target" to target))
            "biome" -> config.shopText("shop.challenge.biome", mapOf("target" to target))
            "world" -> config.shopText("shop.challenge.world", mapOf("target" to target))
            "kill" -> config.shopText("shop.challenge.kill", mapOf("target" to target))
            "height" -> config.shopText("shop.challenge.height", mapOf("target" to target))
            "stat" -> target
            "deep_dark_stay" -> config.shopText("shop.challenge.deep-dark-stay")
            "advancement" -> config.shopText("shop.challenge.advancement")
            else -> config.shopText("shop.challenge.default", mapOf("target" to keyDisplayName(type)))
        }
        return ChallengeRequirement(key, display, required)
    }

    private fun submitItemsProgressDisplay(player: Player, items: List<SubmitItemDefinition>): List<String> {
        return items.mapIndexed { index, item ->
            val current = player.inventory.storageContents
                .filterNotNull()
                .filter { matchesSubmitItem(it, item) }
                .sumOf { it.amount }
                .coerceAtMost(item.amount)
            requirementLineText(
                "shop.requirement.item-progress",
                index,
                mapOf(
                    "item" to Support.stripColor(itemDisplayName(item)),
                    "current" to current.toString(),
                    "required" to item.amount.toString(),
                ),
            )
        }
    }

    private fun formatProgress(value: Long): String {
        return "%,d".format(Locale.US, value)
    }

    private fun refundAndRestore(
        player: Player,
        order: PurchaseOrderData,
        restoreItems: Boolean,
        refundCurrency: Boolean,
    ): Boolean {
        var ok = true
        if (restoreItems && !restoreSubmittedItems(player, order)) {
            ok = false
        }
        if (refundCurrency && !economyBridge.refund(player, order.currencyType, order.currencyAmount)) {
            ok = false
        }
        return ok
    }

    private fun restoreSubmittedItems(player: Player, order: PurchaseOrderData): Boolean {
        decodeStacks(order.submittedItems).forEach { stack ->
            val leftovers = player.inventory.addItem(stack.clone())
            leftovers.values.forEach { leftover ->
                player.world.dropItemNaturally(player.location, leftover)
            }
        }
        order.compensatedItems.clear()
        order.compensatedItems.addAll(order.submittedItems)
        return true
    }

    private fun revokeGrantedTagNoSave(player: Player, targetId: String) {
        val data = tagService.data(player)
        data.ownedTags.remove(targetId)
        data.tagProgress.remove(targetId)
        if (data.equippedTagId == targetId) {
            data.equippedTagId = null
        }
    }

    private fun encodeStack(stack: ItemStack): String = "${stack.amount}x${stack.type.name.lowercase(Locale.ROOT)}"

    private fun decodeStacks(encoded: List<String>): List<ItemStack> {
        return encoded.mapNotNull { raw ->
            val marker = raw.indexOf('x')
            if (marker <= 0 || marker >= raw.lastIndex) {
                return@mapNotNull null
            }
            val amount = raw.substring(0, marker).toIntOrNull()?.coerceAtLeast(1) ?: return@mapNotNull null
            val material = Material.matchMaterial(raw.substring(marker + 1)) ?: return@mapNotNull null
            ItemStack(material, amount)
        }
    }

    private fun sendPurchaseSuccess(player: Player, product: ShopProductDefinition, price: Double) {
        if (product.submitItems.isNotEmpty()) {
            messageService.sendThrottled(player, "shop-tag-unlocked-submit", tagService.tagName(product.targetId))
        } else if (price > 0.0) {
            messageService.sendThrottled(
                player,
                "shop-tag-bought",
                tagService.tagName(product.targetId),
                economyBridge.displayName(product.cost.type),
                Support.formatDouble(price),
            )
        } else {
            messageService.sendThrottled(player, "shop-tag-unlocked", tagService.tagName(product.targetId))
        }
    }

    private fun submitItemsDisplay(items: List<SubmitItemDefinition>): String {
        return submitItemRequirementLines(items).joinToString(config.shopText("shop.requirement.inline-separator"))
    }

    private fun submitItemRequirementLines(items: List<SubmitItemDefinition>): List<String> =
        items.mapIndexed { index, item ->
            requirementLineText(
                "shop.requirement.item",
                index,
                mapOf(
                    "amount" to item.amount.toString(),
                    "item" to Support.stripColor(itemDisplayName(item)),
                ),
            )
        }

    private fun requirementLineText(baseKey: String, index: Int, placeholders: Map<String, String>): String {
        val indexedKey = "$baseKey-${index + 1}"
        val indexedPattern = config.shopText(indexedKey)
        if (indexedPattern != indexedKey) {
            return Support.apply(indexedPattern, placeholders)
        }
        return config.shopText(baseKey, placeholders)
    }

    private fun itemDisplayName(item: SubmitItemDefinition): String =
        item.name?.takeIf { it.isNotBlank() } ?: config.displayName(item.material)

    private fun keyDisplayName(raw: String): String = config.displayName(raw)

    private fun PurchaseOrderData.fail(reason: String?) {
        status = PurchaseOrderStatus.FAILED
        failureReason = reason
        touch()
    }

    private fun PurchaseOrderData.failOrRefundPending(compensated: Boolean, reason: String?) {
        status = if (compensated) PurchaseOrderStatus.FAILED else PurchaseOrderStatus.REFUND_PENDING
        failureReason = reason
        touch()
    }

    private fun PurchaseOrderData.touch() {
        updatedAt = System.currentTimeMillis()
    }

    private companion object {
        val recoverableStatuses = setOf(
            PurchaseOrderStatus.PENDING,
            PurchaseOrderStatus.ITEMS_TAKEN,
            PurchaseOrderStatus.PAID,
            PurchaseOrderStatus.GRANTING,
            PurchaseOrderStatus.REFUND_PENDING,
        )
    }

    private data class ChallengeRequirement(
        val key: String,
        val display: String,
        val required: Long,
    )
}
