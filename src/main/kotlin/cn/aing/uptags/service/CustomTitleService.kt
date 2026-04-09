package cn.aing.uptags.service

import cn.aing.uptags.Support
import cn.aing.uptags.config.ConfigRegistry
import cn.aing.uptags.config.MessageService
import cn.aing.uptags.model.config.CurrencyType
import cn.aing.uptags.model.config.CustomTitlePreset
import cn.aing.uptags.model.runtime.CustomTitleData
import cn.aing.uptags.model.runtime.TagProgress
import cn.aing.uptags.repository.PlayerDataRepository
import org.bukkit.entity.Player
import java.util.LinkedHashSet
import java.util.Locale
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlin.random.Random

class CustomTitleService(
    private val config: ConfigRegistry,
    private val repository: PlayerDataRepository,
    private val economyBridge: EconomyBridge,
    private val messageService: MessageService,
) {
    private val drafts = ConcurrentHashMap<UUID, CustomTitleDraft>()
    private val customTagPrefix = "custom-"
    private val manualColorsPerPage = 12
    private val knownRpgColors = listOf(
        "#F8FAFC", "#E2E8F0", "#CBD5E1", "#94A3B8",
        "#FDE68A", "#FACC15", "#FFD700", "#FFB703",
        "#FDBA74", "#FB923C", "#F97316", "#EA580C",
        "#FCA5A5", "#F87171", "#EF4444", "#DC2626",
        "#FF8FD8", "#FF79C6", "#FF4FA3", "#FF1493",
        "#F9A8D4", "#EC4899", "#DB2777", "#BE185D",
        "#E9D5FF", "#D8B4FE", "#C084FC", "#A855F7",
        "#BD93F9", "#8B5CF6", "#7C3AED", "#6D28D9",
        "#BFDBFE", "#93C5FD", "#60A5FA", "#3B82F6",
        "#7DD3FC", "#38BDF8", "#0EA5E9", "#0284C7",
        "#8BE9FD", "#22D3EE", "#06B6D4", "#0891B2",
        "#99F6E4", "#5EEAD4", "#2DD4BF", "#14B8A6",
        "#86EFAC", "#4ADE80", "#22C55E", "#16A34A",
        "#50FA7B", "#00FA9A", "#84CC16", "#65A30D",
        "#FDE047", "#EAB308", "#F59E0B", "#D97706",
        "#FFDAB9", "#FED7AA", "#FDBA74", "#FB7185",
    )

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
        val visible = Support.stripColor(input).trim()
        if (visible.isBlank()) return ValidationResult(false, "custom-title-empty")
        if (!preset.allowSpaces && visible.contains(' ')) return ValidationResult(false, "custom-title-no-spaces")
        if (visible.length < preset.minLength) return ValidationResult(false, "custom-title-too-short", preset.minLength)
        if (visible.length > preset.maxLength) return ValidationResult(false, "custom-title-too-long", preset.maxLength)
        preset.allowedPattern?.toRegex()?.let { regex ->
            if (!regex.matches(visible)) return ValidationResult(false, "custom-title-invalid-pattern")
        }
        val lower = visible.lowercase(Locale.ROOT)
        if (preset.blockedWords.any { it in lower }) return ValidationResult(false, "custom-title-blocked-word")
        if (preset.blockedPatterns.any { runCatching { Regex(it).containsMatchIn(visible) }.getOrDefault(false) }) {
            return ValidationResult(false, "custom-title-blocked-word")
        }
        draft.rawText = visible
        draft.manualColors.clear()
        draft.manualColorTarget = null
        draft.manualColorPage = 0
        draft.hexBuffer = ""
        draft.selectedPaletteLibrary = defaultPaletteLibrary(draft, preset)
        draft.randomSchemes = generatePreviewSchemes(preset, draft.selectedPaletteLibrary)
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
        return allowedPaletteLibraries(draft, preset)
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
        if (colorCount !in paletteLibraries(preset)) {
            return ValidationResult(false, "custom-title-library-unavailable", paletteLibraryName(colorCount))
        }
        if (colorCount !in allowedPaletteLibraries(draft, preset)) {
            return ValidationResult(
                false,
                "custom-title-library-locked",
                arrayOf(paletteLibraryName(colorCount), economyBridge.displayName(draft.currencyType ?: CurrencyType.POINTS)),
            )
        }
        draft.selectedPaletteLibrary = colorCount
        draft.manualColorTarget = null
        draft.manualColorPage = 0
        draft.manualColors.clear()
        draft.hexBuffer = ""
        draft.randomSchemes = generatePreviewSchemes(preset, colorCount)
        draft.selectedSchemeIndex = 0
        draft.updatedAt = System.currentTimeMillis()
        return ValidationResult(true, null)
    }

    fun beginManualPaletteEditing(player: Player, colorCount: Int? = null): ValidationResult {
        val draft = activeDraft(player) ?: return ValidationResult(false, "custom-title-no-session")
        val preset = config.customTitleSettings.presets[draft.presetId]
            ?: return ValidationResult(false, "custom-title-invalid-preset")
        if (!preset.allowManualColors) {
            return ValidationResult(false, "custom-title-manual-disabled")
        }

        val targetLibrary = colorCount
            ?: draft.selectedPaletteLibrary
            ?: defaultPaletteLibrary(draft, preset)
            ?: return ValidationResult(false, "custom-title-library-unavailable", "可用")

        if (targetLibrary !in paletteLibraries(preset)) {
            return ValidationResult(false, "custom-title-library-unavailable", paletteLibraryName(targetLibrary))
        }
        if (targetLibrary !in allowedPaletteLibraries(draft, preset)) {
            return ValidationResult(
                false,
                "custom-title-library-locked",
                arrayOf(paletteLibraryName(targetLibrary), economyBridge.displayName(draft.currencyType ?: CurrencyType.POINTS)),
            )
        }

        draft.selectedPaletteLibrary = targetLibrary
        draft.randomSchemes = generatePreviewSchemes(preset, targetLibrary)
        draft.selectedSchemeIndex = 0
        if (availableManualColors(preset, targetLibrary).isEmpty()) {
            return ValidationResult(false, "custom-title-library-unavailable", paletteLibraryName(targetLibrary))
        }
        draft.manualColorTarget = targetLibrary
        draft.manualColorPage = 0
        draft.manualColors.clear()
        draft.hexBuffer = ""
        draft.updatedAt = System.currentTimeMillis()
        return ValidationResult(true, null)
    }

    fun manualColorChoices(player: Player): List<String> {
        val draft = activeDraft(player) ?: return emptyList()
        val preset = config.customTitleSettings.presets[draft.presetId] ?: return emptyList()
        val library = draft.manualColorTarget ?: draft.selectedPaletteLibrary ?: return emptyList()
        return availableManualColors(preset, library)
    }

    fun manualColorPage(player: Player): ManualColorPage {
        val draft = activeDraft(player)
        val colors = manualColorChoices(player)
        val totalPages = if (colors.isEmpty()) 1 else ((colors.size - 1) / manualColorsPerPage) + 1
        val pageIndex = draft?.manualColorPage?.coerceIn(0, totalPages - 1) ?: 0
        if (draft != null) {
            draft.manualColorPage = pageIndex
        }
        val pageOffset = pageIndex * manualColorsPerPage
        return ManualColorPage(
            colors = colors.drop(pageOffset).take(manualColorsPerPage),
            pageIndex = pageIndex,
            totalPages = totalPages,
            pageOffset = pageOffset,
        )
    }

    fun changeManualColorPage(player: Player, delta: Int): ValidationResult {
        val draft = activeDraft(player) ?: return ValidationResult(false, "custom-title-no-session")
        val target = draft.manualColorTarget ?: return ValidationResult(false, "custom-title-preview-help")
        val totalChoices = manualColorChoices(player).size
        val totalPages = if (totalChoices == 0) 1 else ((totalChoices - 1) / manualColorsPerPage) + 1
        draft.manualColorPage = (draft.manualColorPage + delta).coerceIn(0, totalPages - 1)
        draft.updatedAt = System.currentTimeMillis()
        return ValidationResult(true, null)
    }

    fun selectManualColor(player: Player, colorIndex: Int): ValidationResult {
        val draft = activeDraft(player) ?: return ValidationResult(false, "custom-title-no-session")
        val preset = config.customTitleSettings.presets[draft.presetId]
            ?: return ValidationResult(false, "custom-title-invalid-preset")
        val target = draft.manualColorTarget ?: return ValidationResult(false, "custom-title-preview-help")
        val choices = availableManualColors(preset, target)
        val color = choices.getOrNull(colorIndex)
            ?: return ValidationResult(false, "custom-title-library-unavailable", paletteLibraryName(target))
        if (draft.manualColors.size >= target) {
            return ValidationResult(false, "custom-title-manual-limit", target)
        }
        draft.manualColors += color
        draft.updatedAt = System.currentTimeMillis()
        return ValidationResult(true, null)
    }

    fun removeLastManualColor(player: Player): ValidationResult {
        val draft = activeDraft(player) ?: return ValidationResult(false, "custom-title-no-session")
        if (draft.manualColorTarget == null) {
            return ValidationResult(false, "custom-title-preview-help")
        }
        if (draft.manualColors.isNotEmpty()) {
            draft.manualColors.removeAt(draft.manualColors.lastIndex)
        }
        draft.updatedAt = System.currentTimeMillis()
        return ValidationResult(true, null)
    }

    fun clearManualColors(player: Player): ValidationResult {
        val draft = activeDraft(player) ?: return ValidationResult(false, "custom-title-no-session")
        if (draft.manualColorTarget == null) {
            return ValidationResult(false, "custom-title-preview-help")
        }
        draft.manualColors.clear()
        draft.hexBuffer = ""
        draft.manualColorPage = 0
        draft.updatedAt = System.currentTimeMillis()
        return ValidationResult(true, null)
    }

    fun finishManualPaletteEditing(player: Player): ValidationResult {
        val draft = activeDraft(player) ?: return ValidationResult(false, "custom-title-no-session")
        val target = draft.manualColorTarget ?: return ValidationResult(false, "custom-title-preview-help")
        if (draft.manualColors.size != target) {
            return ValidationResult(false, "custom-title-manual-count-mismatch", arrayOf(target, draft.manualColors.size))
        }
        draft.selectedPaletteLibrary = target
        draft.manualColorTarget = null
        draft.hexBuffer = ""
        draft.manualColorPage = 0
        draft.updatedAt = System.currentTimeMillis()
        return ValidationResult(true, null)
    }

    fun cancelManualPaletteEditing(player: Player): ValidationResult {
        val draft = activeDraft(player) ?: return ValidationResult(false, "custom-title-no-session")
        draft.manualColorTarget = null
        draft.manualColors.clear()
        draft.hexBuffer = ""
        draft.manualColorPage = 0
        draft.updatedAt = System.currentTimeMillis()
        return ValidationResult(true, null)
    }

    fun autoComposePalette(player: Player): ValidationResult {
        val draft = activeDraft(player) ?: return ValidationResult(false, "custom-title-no-session")
        val preset = config.customTitleSettings.presets[draft.presetId]
            ?: return ValidationResult(false, "custom-title-invalid-preset")
        val allowedLibraries = allowedPaletteLibraries(draft, preset)
        if (allowedLibraries.isEmpty()) {
            return ValidationResult(false, "custom-title-library-unavailable", "可用")
        }

        val libraryCandidates = if (allowedLibraries.size > 1) {
            allowedLibraries.filter { it != draft.selectedPaletteLibrary }.ifEmpty { allowedLibraries }
        } else {
            allowedLibraries
        }
        val targetLibrary = libraryCandidates.random()
        draft.selectedPaletteLibrary = targetLibrary
        draft.manualColorTarget = null
        draft.manualColorPage = 0
        draft.manualColors.clear()
        draft.hexBuffer = ""
        draft.randomSchemes = generatePreviewSchemes(preset, targetLibrary)
        draft.selectedSchemeIndex = if (draft.randomSchemes.size <= 1) {
            0
        } else {
            draft.randomSchemes.indices
                .filter { it != draft.selectedSchemeIndex }
                .ifEmpty { draft.randomSchemes.indices.toList() }
                .random()
        }
        draft.updatedAt = System.currentTimeMillis()
        return ValidationResult(true, null)
    }

    fun paletteLibraryName(colorCount: Int): String {
        return when (colorCount) {
            1 -> "单色"
            2 -> "双色"
            3 -> "三色"
            4 -> "四色"
            else -> "${colorCount}色"
        }
    }

    fun manualColorsAllowed(player: Player): Boolean {
        val draft = activeDraft(player) ?: return false
        return config.customTitleSettings.presets[draft.presetId]?.allowManualColors == true
    }

    private fun previewTextFromDraft(draft: CustomTitleDraft): String {
        if (draft.rawText.isBlank()) {
            return ""
        }
        return Support.renderPaletteText(draft.rawText, effectiveDraftColors(draft))
    }

    private fun effectiveDraftColors(draft: CustomTitleDraft): List<String> {
        val manualPalette = draft.manualColors.mapNotNull(Support::normalizeHex)
        if (manualPalette.isNotEmpty()) {
            return manualPalette
        }
        if (draft.randomSchemes.isEmpty()) {
            val preset = config.customTitleSettings.presets[draft.presetId]
            if (preset != null) {
                draft.randomSchemes = generatePreviewSchemes(preset, draft.selectedPaletteLibrary)
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
        if (economyBridge.balance(player, currencyType) < currencyAmount || !economyBridge.withdraw(player, currencyType, currencyAmount)) {
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

        val data = repository.get(player.uniqueId)
        data.customTitles[titleId] = CustomTitleData(
            id = titleId,
            rawText = draft.rawText,
            presetId = draft.presetId,
            groupId = groupId,
            manualColors = draft.manualColors.mapNotNull(Support::normalizeHex).toMutableList(),
            randomSchemes = randomSchemes,
            selectedSchemeIndex = draft.selectedSchemeIndex.coerceAtLeast(0),
            createdAt = System.currentTimeMillis(),
        )
        data.tagProgress.putIfAbsent(titleId, TagProgress())
        if (preset.equipAfterConfirm) {
            data.equippedCustomTitleId = titleId
            data.equippedTagId = null
        }
        repository.saveAsync(data)
        drafts.remove(player.uniqueId)
        return ValidationResult(true, "custom-title-confirmed", previewTextFromDraft(draft))
    }

    fun applyManualColors(draft: CustomTitleDraft, colors: List<String>) {
        val normalized = colors.mapNotNull(Support::normalizeHex)
        draft.manualColors.clear()
        draft.manualColors.addAll(normalized)
        draft.manualColorTarget = normalized.size.takeIf { it > 0 }
        draft.selectedPaletteLibrary = normalized.size.takeIf { it in 1..4 } ?: draft.selectedPaletteLibrary
        draft.hexBuffer = ""
        draft.updatedAt = System.currentTimeMillis()
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
            draft.randomSchemes = generatePreviewSchemes(preset, draft.selectedPaletteLibrary)
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
        val data = repository.get(player.uniqueId)
        data.titleCoinBalance += amount
        repository.saveAsync(data)
        return data.titleCoinBalance
    }

    fun takeTitleCoins(player: Player, amount: Double): Boolean {
        val data = repository.get(player.uniqueId)
        if (data.titleCoinBalance < amount) return false
        data.titleCoinBalance -= amount
        repository.saveAsync(data)
        return true
    }

    fun titleCoins(player: Player): Double = repository.get(player.uniqueId).titleCoinBalance

    fun preparePlayer(player: Player) {
        val data = repository.get(player.uniqueId)
        if (!data.titleCoinInitialized && config.customTitleSettings.defaultTitleCoinBalance > 0.0) {
            data.titleCoinBalance = config.customTitleSettings.defaultTitleCoinBalance
            data.titleCoinInitialized = true
            repository.saveAsync(data)
        }
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
        return Support.renderPaletteText(customTitle.rawText, colors)
    }

    private fun generatePreviewSchemes(preset: CustomTitlePreset, selectedLibrary: Int? = null): MutableList<MutableList<String>> {
        val schemes = mutableListOf<MutableList<String>>()
        val colorsPerScheme = maxOf(1, preset.colorsPerScheme)
        val normalizedPalettes = preset.palettes
            .map { palette -> palette.mapNotNull(Support::normalizeHex).toMutableList() }
            .filter { it.isNotEmpty() }
            .filter { selectedLibrary == null || it.size == selectedLibrary }
        if (normalizedPalettes.isNotEmpty()) {
            normalizedPalettes.forEach { schemes += it.toMutableList() }
            return schemes
        }

        val randomPool = preset.randomColorPool
            .mapNotNull(Support::normalizeHex)
        if (randomPool.isNotEmpty()) {
            val targetColors = selectedLibrary ?: colorsPerScheme
            repeat(maxOf(1, preset.maxSchemes)) {
                schemes += randomSchemeFromPool(randomPool, targetColors)
            }
            return schemes
        }

        val targetCount = maxOf(1, preset.maxSchemes)
        val targetColors = selectedLibrary ?: colorsPerScheme
        while (schemes.size < targetCount) {
            val randomScheme = mutableListOf<String>()
            repeat(targetColors) {
                randomScheme += randomHexColor()
            }
            schemes += randomScheme
        }
        return schemes
    }

    private fun paletteLibraries(preset: CustomTitlePreset): List<Int> {
        val configured = configuredPaletteLibraries(preset)
        val fallback = if (preset.randomColorPool.isNotEmpty()) listOf(1, 2, 3, 4) else emptyList()
        return (configured + fallback).distinct().sorted()
            .ifEmpty { listOf(colorsPerSchemeOf(preset).coerceIn(1, 4)) }
    }

    private fun configuredPaletteLibraries(preset: CustomTitlePreset): List<Int> {
        val fromPalettes = preset.palettes
            .map { it.mapNotNull(Support::normalizeHex).size }
            .filter { it in 1..4 }
        return fromPalettes.distinct().sorted()
    }

    private fun availableManualColors(preset: CustomTitlePreset, library: Int): List<String> {
        val colors = LinkedHashSet<String>()
        preset.palettes
            .filter { it.size == library }
            .forEach { palette ->
                palette.mapNotNull(Support::normalizeHex).forEach(colors::add)
            }
        preset.randomColorPool.mapNotNull(Support::normalizeHex).forEach(colors::add)
        knownRpgColors.forEach(colors::add)
        return colors.toList()
    }

    private fun defaultPaletteLibrary(draft: CustomTitleDraft, preset: CustomTitlePreset): Int? {
        val allowed = allowedPaletteLibraries(draft, preset)
        if (allowed.isNotEmpty()) {
            return allowed.first()
        }
        val configured = configuredPaletteLibraries(preset)
        if (configured.isNotEmpty()) {
            return configured.first()
        }
        val libraries = paletteLibraries(preset)
        return libraries.firstOrNull { it == 1 } ?: libraries.firstOrNull()
    }

    private fun allowedPaletteLibraries(draft: CustomTitleDraft, preset: CustomTitlePreset): List<Int> {
        val maxColors = currencyPaletteLimit(draft.currencyType)
        return paletteLibraries(preset).filter { it <= maxColors }
    }

    private fun currencyPaletteLimit(currencyType: CurrencyType?): Int {
        return when (currencyType) {
            CurrencyType.MONEY -> 2
            CurrencyType.TITLE_COIN -> 3
            CurrencyType.POINTS, null -> 4
        }
    }

    private fun colorsPerSchemeOf(preset: CustomTitlePreset): Int = maxOf(1, preset.colorsPerScheme)

    private fun randomSchemeFromPool(pool: List<String>, colorsPerScheme: Int): MutableList<String> {
        if (pool.size <= 1) {
            return MutableList(colorsPerScheme) { pool.first() }
        }
        val scheme = mutableListOf<String>()
        repeat(colorsPerScheme) {
            val next = generateSequence { pool.random() }
                .first { candidate -> scheme.isEmpty() || scheme.last() != candidate || pool.distinct().size == 1 }
            scheme += next
        }
        return scheme
    }

    private fun randomHexColor(): String {
        val value = Random.nextInt(0x000000, 0x1000000)
        return "#" + value.toString(16).padStart(6, '0').uppercase(Locale.ROOT)
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
