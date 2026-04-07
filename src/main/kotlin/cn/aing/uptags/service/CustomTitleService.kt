package cn.aing.uptags.service

import cn.aing.uptags.Support
import cn.aing.uptags.config.ConfigRegistry
import cn.aing.uptags.config.MessageService
import cn.aing.uptags.model.config.CurrencyType
import cn.aing.uptags.model.config.CustomTitlePreset
import cn.aing.uptags.model.runtime.CustomTitleData
import cn.aing.uptags.repository.PlayerDataRepository
import org.bukkit.entity.Player
import java.util.Locale
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.min
import kotlin.random.Random

class CustomTitleService(
    private val config: ConfigRegistry,
    private val repository: PlayerDataRepository,
    private val economyBridge: EconomyBridge,
    private val messageService: MessageService,
) {
    private val drafts = ConcurrentHashMap<UUID, CustomTitleDraft>()
    private val customTagPrefix = "custom-"

    fun startDraft(player: Player, presetId: String): Boolean {
        val preset = config.customTitleSettings.presets[presetId] ?: return false
        drafts[player.uniqueId] = CustomTitleDraft(
            presetId = preset.id,
            stage = CustomTitleStage.CHOOSE_CURRENCY,
            updatedAt = System.currentTimeMillis(),
        )
        messageService.send(player, "custom-title-choose-currency")
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
        val preset = config.customTitleSettings.presets[draft.presetId] ?: return ValidationResult(false, "custom-title-invalid-preset")
        draft.updatedAt = System.currentTimeMillis()
        return when (draft.stage) {
            CustomTitleStage.CHOOSE_CURRENCY -> handleCurrencyChoice(player, draft, input)
            CustomTitleStage.INPUT_NAME -> handleNameInput(draft, preset, input)
            CustomTitleStage.CHOOSE_GROUP -> handleGroupChoice(player, draft, input)
            CustomTitleStage.PREVIEW -> ValidationResult(false, "custom-title-preview-help")
        }
    }

    private fun handleCurrencyChoice(player: Player, draft: CustomTitleDraft, input: String): ValidationResult {
        val normalized = input.trim().lowercase(Locale.ROOT)
        val (currency, amount) = when (normalized) {
            "money", "金币", "gold" -> CurrencyType.MONEY to 888888.0
            "title_coin", "称号币", "coin" -> CurrencyType.TITLE_COIN to 100.0
            "points", "点券" -> CurrencyType.POINTS to 30.0
            else -> return ValidationResult(false, "custom-title-invalid-currency")
        }
        if (!economyBridge.isAvailable(currency)) {
            return ValidationResult(false, "economy-unavailable", economyBridge.displayName(currency))
        }
        if (economyBridge.balance(player, currency) < amount || !economyBridge.withdraw(player, currency, amount)) {
            return ValidationResult(false, "not-enough", arrayOf(Support.formatDouble(amount), economyBridge.displayName(currency)))
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
            return ValidationResult(false, "custom-title-too-long", preset.maxLength)
        preset.allowedPattern?.toRegex()?.let { regex ->
            if (!regex.matches(visible)) return ValidationResult(false, "custom-title-invalid-pattern")
        }
        val lower = visible.lowercase(Locale.ROOT)
        if (preset.blockedWords.any { it in lower }) return ValidationResult(false, "custom-title-blocked-word")
        if (preset.blockedPatterns.any { runCatching { Regex(it).containsMatchIn(visible) }.getOrDefault(false) }) {
            return ValidationResult(false, "custom-title-blocked-word")
        }
        draft.rawText = visible
        draft.randomSchemes = generateRandomSchemes(preset)
        draft.selectedSchemeIndex = 0
        draft.stage = CustomTitleStage.PREVIEW
        return ValidationResult(true, null)
    }

    private fun handleGroupChoice(player: Player, draft: CustomTitleDraft, input: String): ValidationResult {
        val group = input.trim()
        if (!config.hasUpgradeGroup(group)) {
            return ValidationResult(false, "custom-title-invalid-group")
        }
        draft.groupId = group
        val titleId = "$customTagPrefix${System.currentTimeMillis()}"
        val created = config.createTag(titleId)
        created.display = previewText(player) ?: draft.rawText
        created.description = listOf("&7玩家自定义称号")
        created.upgradeGroups = mutableListOf(group)
        created.permission = null
        config.saveTags()

        val data = repository.get(player.uniqueId)
        data.ownedTags.add(titleId)
        data.equippedTagId = titleId
        data.equippedCustomTitleId = null
        data.customTitles[titleId] = CustomTitleData(
            id = titleId,
            rawText = draft.rawText,
            presetId = draft.presetId,
            manualColors = draft.manualColors.toMutableList(),
            randomSchemes = draft.randomSchemes.map { it.toMutableList() }.toMutableList(),
            selectedSchemeIndex = draft.selectedSchemeIndex,
            createdAt = System.currentTimeMillis(),
        )
        repository.saveAsync(data)
        drafts.remove(player.uniqueId)
        return ValidationResult(true, "custom-title-confirmed", previewTextFromDraft(draft))
    }

    fun cycleScheme(player: Player, step: Int): String? {
        val draft = activeDraft(player) ?: return null
        if (draft.randomSchemes.isEmpty()) return null
        val size = draft.randomSchemes.size
        draft.selectedSchemeIndex = ((draft.selectedSchemeIndex + step) % size + size) % size
        draft.updatedAt = System.currentTimeMillis()
        return previewText(player)
    }

    fun rerollSchemes(player: Player): String? {
        val draft = activeDraft(player) ?: return null
        val preset = config.customTitleSettings.presets[draft.presetId] ?: return null
        draft.randomSchemes = generateRandomSchemes(preset)
        draft.selectedSchemeIndex = 0
        draft.updatedAt = System.currentTimeMillis()
        return previewText(player)
    }

    fun previewText(player: Player): String? {
        val draft = activeDraft(player) ?: return null
        return previewTextFromDraft(draft)
    }

    private fun previewTextFromDraft(draft: CustomTitleDraft): String {
        val preset = config.customTitleSettings.presets[draft.presetId] ?: return draft.rawText
        val colors = if (draft.manualColors.isNotEmpty()) draft.manualColors else draft.randomSchemes.getOrNull(draft.selectedSchemeIndex).orEmpty()
        return Support.apply(preset.previewTemplate, mapOf("title" to colorize(draft.rawText, colors)))
    }

    fun confirm(player: Player): String? {
        val draft = activeDraft(player) ?: return null
        draft.stage = CustomTitleStage.CHOOSE_GROUP
        messageService.send(player, "custom-title-choose-group", config.allUpgradeGroups().joinToString(", "))
        return previewText(player)
    }

    fun currentDisplay(player: Player): String? {
        val id = repository.get(player.uniqueId).equippedCustomTitleId ?: return null
        val custom = repository.get(player.uniqueId).customTitles[id] ?: return null
        val colors = if (custom.manualColors.isNotEmpty()) custom.manualColors else custom.randomSchemes.getOrNull(custom.selectedSchemeIndex).orEmpty()
        return colorize(custom.rawText, colors)
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

    private fun generateRandomSchemes(preset: CustomTitlePreset): MutableList<MutableList<String>> {
        if (preset.palettes.isEmpty()) {
            return mutableListOf(mutableListOf("#FFFFFF"))
        }
        val result = mutableListOf<MutableList<String>>()
        repeat(maxOf(8, preset.maxSchemes)) {
            val palette = preset.palettes.random(Random(System.nanoTime()))
            val colors = mutableListOf<String>()
            repeat(min(maxOf(3, preset.colorsPerScheme), palette.size)) { index ->
                colors += normalizeHex(palette[index]) ?: "#FFFFFF"
            }
            if (colors.isEmpty()) colors += "#FFFFFF"
            result += colors
        }
        return result
    }

    private fun colorize(text: String, colors: List<String>): String {
        if (text.isBlank()) return text
        val palette = if (colors.isEmpty()) listOf("#FFFFFF") else colors
        val builder = StringBuilder()
        text.forEachIndexed { index, char ->
            builder.append('&').append(palette[index % palette.size]).append(char)
        }
        return Support.color(builder.toString())
    }

    private fun normalizeHex(input: String): String? {
        val raw = input.trim().removePrefix("&").removePrefix("#")
        return if (raw.matches(Regex("[A-Fa-f0-9]{6}"))) "#${raw.uppercase(Locale.ROOT)}" else null
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
    var groupId: String? = null,
    var updatedAt: Long = System.currentTimeMillis(),
)

data class ValidationResult(
    val success: Boolean,
    val messageKey: String?,
    val args: Any? = null,
)
