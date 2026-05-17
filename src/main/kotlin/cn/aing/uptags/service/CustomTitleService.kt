package cn.aing.uptags.service

import cn.aing.uptags.Support
import cn.aing.uptags.compat.PlatformScheduler
import cn.aing.uptags.config.ConfigRegistry
import cn.aing.uptags.config.MessageService
import cn.aing.uptags.model.config.CurrencyType
import cn.aing.uptags.model.config.CustomTitlePreset
import cn.aing.uptags.model.runtime.CustomTitleData
import cn.aing.uptags.model.runtime.CustomTitleOrderStatus
import cn.aing.uptags.model.runtime.CustomTitlePurchaseOrderData
import cn.aing.uptags.model.runtime.TagProgress
import cn.aing.uptags.repository.PlayerDataRepository
import cn.aing.uptags.repository.SaveResult
import cn.aing.uptags.util.UnicodeText
import org.bukkit.entity.Player
import java.util.Locale
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.logging.Logger

class CustomTitleService(
    private val config: ConfigRegistry,
    private val repository: PlayerDataRepository,
    private val economyBridge: EconomyBridge,
    private val messageService: MessageService,
    private val scheduler: PlatformScheduler? = null,
) {
    private val drafts = ConcurrentHashMap<UUID, CustomTitleDraft>()
    private val customTagPrefix = "custom-"
    private val palettes = CustomTitlePaletteService()
    private val manualPalettes = CustomTitleManualPaletteService(economyBridge, palettes)
    private val titleCoins = TitleCoinService(config, repository)
    private val logger = Logger.getLogger(CustomTitleService::class.java.name)

    fun startDraft(player: Player, presetId: String): Boolean {
        val preset = config.customTitleSettings.presets[presetId] ?: return false
        drafts[player.uniqueId] = CustomTitleDraft(
            presetId = preset.id,
            stage = CustomTitleStage.CHOOSE_CURRENCY,
            updatedAt = System.currentTimeMillis(),
        )
        return true
    }

    fun startProductDraft(
        player: Player,
        presetId: String,
        currencyType: CurrencyType,
        currencyAmount: Double,
        productId: String? = null,
    ): Boolean {
        val preset = config.customTitleSettings.presets[presetId] ?: return false
        drafts[player.uniqueId] = CustomTitleDraft(
            presetId = preset.id,
            stage = CustomTitleStage.INPUT_NAME,
            currencyType = currencyType,
            currencyAmount = currencyAmount,
            productId = productId,
            updatedAt = System.currentTimeMillis(),
        )
        return true
    }

    fun activeDraft(player: Player): CustomTitleDraft? {
        val draft = drafts[player.uniqueId] ?: return null
        val timeout = config.customTitleSettings.sessionTimeoutSeconds * 1000
        if (System.currentTimeMillis() - draft.updatedAt > timeout) {
            drafts.remove(player.uniqueId)
            return null
        }
        return draft
    }

    fun hasActiveDraft(uniqueId: UUID): Boolean {
        val draft = drafts[uniqueId] ?: return false
        val timeout = config.customTitleSettings.sessionTimeoutSeconds * 1000
        if (System.currentTimeMillis() - draft.updatedAt > timeout) {
            drafts.remove(uniqueId)
            return false
        }
        return true
    }

    fun currencyChoices(): List<Pair<CurrencyType, Double>> {
        val configured = config.customTitleSettings.currencyCosts
        return listOf(CurrencyType.MONEY, CurrencyType.TITLE_COIN, CurrencyType.POINTS)
            .mapNotNull { type ->
                val amount = configured[type] ?: return@mapNotNull null
                if (amount < 0.0) null else type to amount
            }
    }

    fun cancelDraft(player: Player, notify: Boolean = true) {
        drafts.remove(player.uniqueId)
        if (notify) {
            messageService.send(player, "custom-title-cancelled")
        }
    }

    fun handleInput(player: Player, input: String): ValidationResult {
        val draft = activeDraft(player) ?: return ValidationResult(false, "custom-title-no-session")
        if (input.equals("cancel", true)) {
            cancelDraft(player)
            return ValidationResult(false, null)
        }
        val preset = config.customTitleSettings.presets[draft.presetId]
            ?: return ValidationResult(false, "custom-title-invalid-preset")
        draft.updatedAt = System.currentTimeMillis()
        return when (draft.stage) {
            CustomTitleStage.CHOOSE_CURRENCY -> handleCurrencyChoice(player, draft, input)
            CustomTitleStage.INPUT_NAME -> handleNameInput(draft, preset, input)
            CustomTitleStage.CHOOSE_GROUP -> handleGroupChoice(player, draft, preset, input)
            CustomTitleStage.PREVIEW -> ValidationResult(false, "custom-title-preview-help")
        }
    }

    private fun handleCurrencyChoice(player: Player, draft: CustomTitleDraft, input: String): ValidationResult {
        val normalized = input.trim().lowercase(Locale.ROOT)
        val currency = when (normalized) {
            "money", "gold" -> CurrencyType.MONEY
            "title_coin", "coin" -> CurrencyType.TITLE_COIN
            "points" -> CurrencyType.POINTS
            else -> null
        } ?: return ValidationResult(false, "custom-title-invalid-currency")
        val amount = config.customTitleSettings.currencyCosts[currency]
            ?.takeIf { it >= 0.0 }
            ?: return ValidationResult(false, "custom-title-invalid-currency")
        if (!economyBridge.isAvailable(currency)) {
            return ValidationResult(false, "economy-unavailable", economyBridge.displayName(currency))
        }
        if (economyBridge.balance(player, currency) < amount) {
            return ValidationResult(
                false,
                "not-enough",
                arrayOf(Support.formatDouble(amount), economyBridge.displayName(currency)),
            )
        }
        draft.currencyType = currency
        draft.currencyAmount = amount
        draft.stage = CustomTitleStage.INPUT_NAME
        messageService.send(player, "custom-title-input")
        return ValidationResult(false, null)
    }

    private fun handleNameInput(draft: CustomTitleDraft, preset: CustomTitlePreset, input: String): ValidationResult {
        val visible = UnicodeText.sanitizePlayerTitleInput(input)
        if (visible.isBlank()) return ValidationResult(false, "custom-title-empty")
        if (UnicodeText.containsForbiddenCodePoint(visible)) {
            return ValidationResult(false, "custom-title-invalid-pattern")
        }
        if (!preset.allowSpaces && visible.any { it.isWhitespace() || it == '\u3000' }) {
            return ValidationResult(false, "custom-title-no-spaces")
        }
        val visibleLength = UnicodeText.visibleCharacterCount(visible)
        if (visibleLength < preset.minLength) return ValidationResult(false, "custom-title-too-short", preset.minLength)
        if (visibleLength > preset.maxLength) return ValidationResult(false, "custom-title-too-long", preset.maxLength)
        preset.allowedPattern?.toRegex()?.let { regex ->
            if (!regex.matches(visible)) return ValidationResult(false, "custom-title-invalid-pattern")
        }
        val riskText = UnicodeText.riskText(visible)
        if (preset.blockedWords.any { it.isNotBlank() && UnicodeText.riskText(it) in riskText }) {
            return ValidationResult(false, "custom-title-blocked-word")
        }
        if (preset.blockedPatterns.any { runCatching { Regex(it).containsMatchIn(riskText) }.getOrDefault(false) }) {
            return ValidationResult(false, "custom-title-blocked-word")
        }
        draft.rawText = visible
        draft.manualColors.clear()
        draft.manualColorTarget = null
        draft.manualColorPage = 0
        draft.hexBuffer = ""
        draft.selectedPaletteLibrary = palettes.defaultPaletteLibrary(draft, preset)
        draft.randomSchemes = palettes.generatePreviewSchemes(preset, draft.selectedPaletteLibrary)
        draft.selectedSchemeIndex = 0
        draft.stage = CustomTitleStage.PREVIEW
        return ValidationResult(true, null)
    }

    fun previewText(player: Player): String? {
        val draft = activeDraft(player) ?: return null
        return previewTextFromDraft(draft)
    }

    fun previewMessage(player: Player): String? {
        val draft = activeDraft(player) ?: return null
        val preset = config.customTitleSettings.presets[draft.presetId] ?: return previewTextFromDraft(draft)
        return Support.color(
            Support.apply(
                preset.previewTemplate,
                mapOf("title" to previewTextFromDraft(draft)),
            ),
        )
    }

    fun previewPalette(player: Player): List<String> {
        val draft = activeDraft(player) ?: return emptyList()
        return effectiveDraftColors(draft).toList()
    }

    fun availablePaletteLibraries(player: Player): List<Int> {
        val draft = activeDraft(player) ?: return emptyList()
        val preset = config.customTitleSettings.presets[draft.presetId] ?: return emptyList()
        return palettes.allowedPaletteLibraries(draft, preset)
    }

    fun currentPaletteLibrary(player: Player): Int? = activeDraft(player)?.selectedPaletteLibrary

    fun manualPaletteTarget(player: Player): Int? = activeDraft(player)?.manualColorTarget

    fun manualPaletteInProgress(player: Player): Boolean = activeDraft(player)?.manualColorTarget != null

    fun selectedManualColors(player: Player): List<String> = activeDraft(player)?.manualColors?.mapNotNull(Support::normalizeHex).orEmpty()

    fun draftRawText(player: Player): String = activeDraft(player)?.rawText.orEmpty()

    fun selectPaletteLibrary(player: Player, colorCount: Int): ValidationResult {
        val draft = activeDraft(player) ?: return ValidationResult(false, "custom-title-no-session")
        val preset = config.customTitleSettings.presets[draft.presetId]
            ?: return ValidationResult(false, "custom-title-invalid-preset")
        return manualPalettes.selectPaletteLibrary(draft, preset, colorCount)
    }

    fun beginManualPaletteEditing(player: Player, colorCount: Int? = null): ValidationResult {
        val draft = activeDraft(player) ?: return ValidationResult(false, "custom-title-no-session")
        val preset = config.customTitleSettings.presets[draft.presetId]
            ?: return ValidationResult(false, "custom-title-invalid-preset")
        return manualPalettes.beginManualPaletteEditing(draft, preset, colorCount)
    }

    fun manualColorChoices(player: Player): List<String> {
        val draft = activeDraft(player) ?: return emptyList()
        val preset = config.customTitleSettings.presets[draft.presetId] ?: return emptyList()
        return manualPalettes.manualColorChoices(draft, preset)
    }

    fun manualColorPage(player: Player): ManualColorPage {
        val draft = activeDraft(player) ?: return manualPalettes.emptyPage()
        val preset = config.customTitleSettings.presets[draft.presetId] ?: return manualPalettes.emptyPage()
        return manualPalettes.manualColorPage(draft, preset)
    }

    fun changeManualColorPage(player: Player, delta: Int): ValidationResult {
        val draft = activeDraft(player) ?: return ValidationResult(false, "custom-title-no-session")
        val preset = config.customTitleSettings.presets[draft.presetId]
            ?: return ValidationResult(false, "custom-title-invalid-preset")
        return manualPalettes.changeManualColorPage(draft, preset, delta)
    }

    fun selectManualColor(player: Player, colorIndex: Int): ValidationResult {
        val draft = activeDraft(player) ?: return ValidationResult(false, "custom-title-no-session")
        val preset = config.customTitleSettings.presets[draft.presetId]
            ?: return ValidationResult(false, "custom-title-invalid-preset")
        return manualPalettes.selectManualColor(draft, preset, colorIndex)
    }

    fun removeLastManualColor(player: Player): ValidationResult {
        val draft = activeDraft(player) ?: return ValidationResult(false, "custom-title-no-session")
        return manualPalettes.removeLastManualColor(draft)
    }

    fun clearManualColors(player: Player): ValidationResult {
        val draft = activeDraft(player) ?: return ValidationResult(false, "custom-title-no-session")
        return manualPalettes.clearManualColors(draft)
    }

    fun finishManualPaletteEditing(player: Player): ValidationResult {
        val draft = activeDraft(player) ?: return ValidationResult(false, "custom-title-no-session")
        return manualPalettes.finishManualPaletteEditing(draft)
    }

    fun cancelManualPaletteEditing(player: Player): ValidationResult {
        val draft = activeDraft(player) ?: return ValidationResult(false, "custom-title-no-session")
        return manualPalettes.cancelManualPaletteEditing(draft)
    }

    fun autoComposePalette(player: Player): ValidationResult {
        val draft = activeDraft(player) ?: return ValidationResult(false, "custom-title-no-session")
        val preset = config.customTitleSettings.presets[draft.presetId]
            ?: return ValidationResult(false, "custom-title-invalid-preset")
        return manualPalettes.autoComposePalette(draft, preset)
    }

    fun paletteLibraryName(colorCount: Int): String {
        return palettes.paletteLibraryName(colorCount)
    }

    fun manualColorsAllowed(player: Player): Boolean {
        val draft = activeDraft(player) ?: return false
        return config.customTitleSettings.presets[draft.presetId]?.allowManualColors == true
    }

    private fun previewTextFromDraft(draft: CustomTitleDraft): String {
        if (draft.rawText.isBlank()) {
            return ""
        }
        return Support.renderPaletteText(Support.decorateCustomTitle(draft.rawText), effectiveDraftColors(draft))
    }

    private fun effectiveDraftColors(draft: CustomTitleDraft): List<String> {
        val manualPalette = draft.manualColors.mapNotNull(Support::normalizeHex)
        if (manualPalette.isNotEmpty()) {
            return manualPalette
        }
        if (draft.randomSchemes.isEmpty()) {
            val preset = config.customTitleSettings.presets[draft.presetId]
            if (preset != null) {
                draft.randomSchemes = palettes.generatePreviewSchemes(preset, draft.selectedPaletteLibrary)
                draft.selectedSchemeIndex = 0
            }
        }
        return draft.randomSchemes
            .getOrNull(draft.selectedSchemeIndex)
            .orEmpty()
            .mapNotNull(Support::normalizeHex)
            .ifEmpty { listOf("#FFFFFF") }
    }

    private fun handleGroupChoice(
        player: Player,
        draft: CustomTitleDraft,
        preset: CustomTitlePreset,
        input: String,
    ): ValidationResult {
        val group = input.trim()
        if (!config.hasUpgradeGroup(group)) {
            return ValidationResult(false, "custom-title-invalid-group")
        }
        draft.groupId = group
        return finalizeDraft(player, draft, preset, group)
    }

    private fun finalizeDraft(
        player: Player,
        draft: CustomTitleDraft,
        preset: CustomTitlePreset,
        groupId: String,
    ): ValidationResult {
        val currencyType = draft.currencyType ?: return ValidationResult(false, "custom-title-invalid-currency")
        val currencyAmount = draft.currencyAmount
        if (currencyAmount <= 0.0) {
            return ValidationResult(false, "custom-title-invalid-currency")
        }
        if (!economyBridge.isAvailable(currencyType)) {
            return ValidationResult(false, "economy-unavailable", economyBridge.displayName(currencyType))
        }
        if (economyBridge.balance(player, currencyType) < currencyAmount) {
            return ValidationResult(
                false,
                "not-enough",
                arrayOf(Support.formatDouble(currencyAmount), economyBridge.displayName(currencyType)),
            )
        }

        val colors = effectiveDraftColors(draft)
        val titleId = "$customTagPrefix${UUID.randomUUID().toString().replace("-", "")}"
        val randomSchemes = if (draft.randomSchemes.isEmpty()) {
            mutableListOf(colors.toMutableList())
        } else {
            draft.randomSchemes.map { scheme -> scheme.mapNotNull(Support::normalizeHex).toMutableList() }.toMutableList()
        }
        val orderId = "order-${UUID.randomUUID().toString().replace("-", "")}"
        val now = System.currentTimeMillis()

        val data = repository.get(player.uniqueId)
        val context = PendingCustomTitlePurchase(
            orderId = orderId,
            titleId = titleId,
            rawText = draft.rawText,
            presetId = draft.presetId,
            groupId = groupId,
            currencyType = currencyType,
            currencyAmount = currencyAmount,
            manualColors = draft.manualColors.mapNotNull(Support::normalizeHex).toMutableList(),
            randomSchemes = randomSchemes,
            selectedSchemeIndex = draft.selectedSchemeIndex.coerceAtLeast(0),
            createdAt = now,
            equipAfterConfirm = preset.equipAfterConfirm,
            previewText = previewTextFromDraft(draft),
            previousEquippedTagId = data.equippedTagId,
            previousEquippedCustomTitleId = data.equippedCustomTitleId,
        )
        data.customTitleOrders[orderId] = CustomTitlePurchaseOrderData(
            orderId = orderId,
            titleId = titleId,
            rawText = context.rawText,
            presetId = context.presetId,
            groupId = context.groupId,
            currencyType = context.currencyType,
            currencyAmount = context.currencyAmount,
            status = CustomTitleOrderStatus.PENDING,
            createdAt = now,
            updatedAt = now,
            previousEquippedTagId = context.previousEquippedTagId,
            previousEquippedCustomTitleId = context.previousEquippedCustomTitleId,
        )
        drafts.remove(player.uniqueId)
        repository.saveAsync(data) { result ->
            runForPlayer(player) {
                handlePendingOrderSaved(player, context, result)
            }
        }
        return ValidationResult(true, "custom-title-purchase-pending")
    }

    private fun handlePendingOrderSaved(
        player: Player,
        context: PendingCustomTitlePurchase,
        result: SaveResult,
    ) {
        if (result !is SaveResult.Success) {
            val reason = "Pending custom title order save failed: ${describeSaveResult(result)}"
            logger.warning("${reason}; player=${player.uniqueId}; order=${context.orderId}")
            markOrderFailed(player, context, reason)
            messageService.send(player, "custom-title-save-failed")
            return
        }
        if (!economyBridge.isAvailable(context.currencyType)) {
            val reason = "Economy unavailable before custom title withdraw: ${context.currencyType}"
            logger.warning("${reason}; player=${player.uniqueId}; order=${context.orderId}")
            markOrderFailed(player, context, reason)
            messageService.send(player, "economy-unavailable", economyBridge.displayName(context.currencyType))
            return
        }
        if (economyBridge.balance(player, context.currencyType) < context.currencyAmount ||
            !economyBridge.withdraw(player, context.currencyType, context.currencyAmount)
        ) {
            val reason = "Withdraw failed for custom title order"
            logger.warning("${reason}; player=${player.uniqueId}; order=${context.orderId}; currency=${context.currencyType}; amount=${context.currencyAmount}")
            markOrderFailed(player, context, reason)
            messageService.send(
                player,
                "not-enough",
                Support.formatDouble(context.currencyAmount),
                economyBridge.displayName(context.currencyType),
            )
            return
        }
        saveCompletedOrder(player, context)
    }

    private fun saveCompletedOrder(player: Player, context: PendingCustomTitlePurchase) {
        val data = repository.get(player.uniqueId)
        data.customTitles[context.titleId] = CustomTitleData(
            id = context.titleId,
            rawText = context.rawText,
            presetId = context.presetId,
            groupId = context.groupId,
            manualColors = context.manualColors.toMutableList(),
            randomSchemes = context.randomSchemes.map { it.toMutableList() }.toMutableList(),
            selectedSchemeIndex = context.selectedSchemeIndex,
            createdAt = context.createdAt,
        )
        data.tagProgress.putIfAbsent(context.titleId, TagProgress())
        if (context.equipAfterConfirm) {
            data.equippedCustomTitleId = context.titleId
            data.equippedTagId = null
        }
        data.customTitleOrders[context.orderId]?.let { order ->
            order.status = CustomTitleOrderStatus.COMPLETED
            order.updatedAt = System.currentTimeMillis()
            order.failureReason = null
        }
        repository.saveAsync(data) { result ->
            runForPlayer(player) {
                handleCompletedOrderSaved(player, context, result)
            }
        }
    }

    private fun handleCompletedOrderSaved(
        player: Player,
        context: PendingCustomTitlePurchase,
        result: SaveResult,
    ) {
        if (result is SaveResult.Success) {
            messageService.send(player, "custom-title-confirmed", context.previewText)
            return
        }
        val reason = "Completed custom title save failed after withdraw: ${describeSaveResult(result)}"
        logger.warning("${reason}; player=${player.uniqueId}; order=${context.orderId}")
        val data = repository.get(player.uniqueId)
        data.customTitles.remove(context.titleId)
        data.tagProgress.remove(context.titleId)
        if (data.equippedCustomTitleId == context.titleId) {
            data.equippedCustomTitleId = context.previousEquippedCustomTitleId
            data.equippedTagId = context.previousEquippedTagId
        }
        val order = data.customTitleOrders[context.orderId]
        if (context.currencyType == CurrencyType.TITLE_COIN) {
            order?.status = CustomTitleOrderStatus.REFUNDED
            order?.failureReason = reason
            order?.updatedAt = System.currentTimeMillis()
            economyBridge.refund(player, context.currencyType, context.currencyAmount)
            messageService.send(player, "custom-title-refunded")
            return
        }
        order?.status = CustomTitleOrderStatus.REFUND_PENDING
        order?.failureReason = reason
        order?.updatedAt = System.currentTimeMillis()
        repository.saveAsync(data) { saveResult ->
            if (saveResult !is SaveResult.Success) {
                logger.warning(
                    "Failed to record refund-pending custom title order; player=${player.uniqueId}; order=${context.orderId}; result=${describeSaveResult(saveResult)}",
                )
            }
        }
        messageService.send(player, "custom-title-refund-pending")
    }

    private fun markOrderFailed(player: Player, context: PendingCustomTitlePurchase, reason: String) {
        val data = repository.get(player.uniqueId)
        val now = System.currentTimeMillis()
        val order = data.customTitleOrders[context.orderId] ?: CustomTitlePurchaseOrderData(
            orderId = context.orderId,
            titleId = context.titleId,
            rawText = context.rawText,
            presetId = context.presetId,
            groupId = context.groupId,
            currencyType = context.currencyType,
            currencyAmount = context.currencyAmount,
            status = CustomTitleOrderStatus.FAILED,
            createdAt = context.createdAt,
            updatedAt = now,
            previousEquippedTagId = context.previousEquippedTagId,
            previousEquippedCustomTitleId = context.previousEquippedCustomTitleId,
        ).also { data.customTitleOrders[context.orderId] = it }
        order.status = CustomTitleOrderStatus.FAILED
        order.failureReason = reason
        order.updatedAt = now
        repository.saveAsync(data) { saveResult ->
            if (saveResult !is SaveResult.Success) {
                logger.warning(
                    "Failed to record failed custom title order; player=${player.uniqueId}; order=${context.orderId}; result=${describeSaveResult(saveResult)}",
                )
            }
        }
    }

    private fun runForPlayer(player: Player, task: () -> Unit) {
        val platformScheduler = scheduler
        if (platformScheduler == null) {
            task()
        } else {
            platformScheduler.runPlayer(player, task)
        }
    }

    private fun describeSaveResult(result: SaveResult): String {
        return when (result) {
            is SaveResult.Success -> "success(version=${result.version})"
            is SaveResult.Conflict -> "conflict"
            is SaveResult.Failure -> result.message
        }
    }

    fun applyManualColors(draft: CustomTitleDraft, colors: List<String>) {
        manualPalettes.applyManualColors(draft, colors)
    }

    fun confirm(player: Player): ValidationResult {
        val draft = activeDraft(player) ?: return ValidationResult(false, "custom-title-no-session")
        if (draft.manualColorTarget != null) {
            val target = draft.manualColorTarget ?: 0
            return ValidationResult(false, "custom-title-manual-count-mismatch", arrayOf(target, draft.manualColors.size))
        }
        val preset = config.customTitleSettings.presets[draft.presetId]
            ?: return ValidationResult(false, "custom-title-invalid-preset")
        val groups = config.allUpgradeGroups()
        if (groups.isEmpty()) {
            return ValidationResult(false, "custom-title-invalid-group")
        }
        if (groups.size == 1) {
            draft.stage = CustomTitleStage.CHOOSE_GROUP
            return finalizeDraft(player, draft, preset, groups.first())
        }
        draft.stage = CustomTitleStage.CHOOSE_GROUP
        return ValidationResult(true, "custom-title-choose-group", groups.joinToString(", "))
    }

    fun cycleScheme(player: Player, delta: Int): String? {
        val draft = activeDraft(player) ?: return null
        val preset = config.customTitleSettings.presets[draft.presetId] ?: return null
        if (draft.manualColors.isNotEmpty()) {
            draft.manualColors.clear()
        }
        draft.manualColorTarget = null
        draft.hexBuffer = ""
        if (draft.randomSchemes.isEmpty()) {
            draft.randomSchemes = palettes.generatePreviewSchemes(preset, draft.selectedPaletteLibrary)
            draft.selectedSchemeIndex = 0
        }
        if (draft.randomSchemes.isEmpty()) {
            return previewTextFromDraft(draft)
        }
        val size = draft.randomSchemes.size
        val current = draft.selectedSchemeIndex.coerceIn(0, size - 1)
        draft.selectedSchemeIndex = (current + delta).mod(size)
        draft.updatedAt = System.currentTimeMillis()
        return previewTextFromDraft(draft)
    }

    fun currentDisplay(player: Player): String? {
        val id = repository.get(player.uniqueId).equippedCustomTitleId ?: return null
        val custom = repository.get(player.uniqueId).customTitles[id] ?: return null
        return renderCustomTitle(custom)
    }

    fun addTitleCoins(player: Player, amount: Double): Double {
        return titleCoins.add(player, amount)
    }

    fun addTitleCoins(uniqueId: UUID, amount: Double): Double {
        return titleCoins.add(uniqueId, amount)
    }

    fun takeTitleCoins(player: Player, amount: Double): Boolean {
        return titleCoins.take(player, amount)
    }

    fun takeTitleCoins(uniqueId: UUID, amount: Double): Double? {
        return titleCoins.take(uniqueId, amount)
    }

    fun setTitleCoins(uniqueId: UUID, amount: Double): Double {
        return titleCoins.set(uniqueId, amount)
    }

    fun titleCoins(player: Player): Double = titleCoins.balance(player)

    fun preparePlayer(player: Player) {
        titleCoins.preparePlayer(player)
    }

    fun renderCustomTitle(customTitle: CustomTitleData): String {
        if (customTitle.rawText.isBlank()) {
            return ""
        }
        val colors = if (customTitle.manualColors.isNotEmpty()) {
            customTitle.manualColors
        } else {
            customTitle.randomSchemes.getOrNull(customTitle.selectedSchemeIndex).orEmpty()
        }
        return Support.renderPaletteText(Support.decorateCustomTitle(customTitle.rawText), colors)
    }

}

enum class CustomTitleStage {
    CHOOSE_CURRENCY,
    INPUT_NAME,
    PREVIEW,
    CHOOSE_GROUP,
}

data class CustomTitleDraft(
    val presetId: String,
    var stage: CustomTitleStage,
    var rawText: String = "",
    var currencyType: CurrencyType? = null,
    var currencyAmount: Double = 0.0,
    var manualColors: MutableList<String> = mutableListOf(),
    var randomSchemes: MutableList<MutableList<String>> = mutableListOf(),
    var selectedSchemeIndex: Int = 0,
    var selectedPaletteLibrary: Int? = null,
    var manualColorTarget: Int? = null,
    var manualColorPage: Int = 0,
    var groupId: String? = null,
    var productId: String? = null,
    var hexBuffer: String = "",
    var updatedAt: Long = System.currentTimeMillis(),
)

private data class PendingCustomTitlePurchase(
    val orderId: String,
    val titleId: String,
    val rawText: String,
    val presetId: String,
    val groupId: String?,
    val currencyType: CurrencyType,
    val currencyAmount: Double,
    val manualColors: MutableList<String>,
    val randomSchemes: MutableList<MutableList<String>>,
    val selectedSchemeIndex: Int,
    val createdAt: Long,
    val equipAfterConfirm: Boolean,
    val previewText: String,
    val previousEquippedTagId: String?,
    val previousEquippedCustomTitleId: String?,
)

data class ValidationResult(
    val success: Boolean,
    val messageKey: String?,
    val args: Any? = null,
)

data class ManualColorPage(
    val colors: List<String>,
    val pageIndex: Int,
    val totalPages: Int,
    val pageOffset: Int,
)
