package cn.aing.uptags.service

import cn.aing.uptags.Support
import cn.aing.uptags.config.ConfigRegistry
import cn.aing.uptags.config.MessageService
import cn.aing.uptags.model.config.CustomTitlePreset
import cn.aing.uptags.model.runtime.CustomTitleData
import cn.aing.uptags.model.runtime.PlayerTagData
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
    private val messageService: MessageService,
) {
    private val previewSessions = ConcurrentHashMap<UUID, CustomTitleDraft>()

    fun startDraft(player: Player, presetId: String): Boolean {
        val preset = config.customTitleSettings.presets[presetId] ?: return false
        previewSessions[player.uniqueId] = CustomTitleDraft(presetId = preset.id, updatedAt = System.currentTimeMillis())
        messageService.send(player, "custom-title-input")
        return true
    }

    fun cancelDraft(player: Player, notify: Boolean = true) {
        previewSessions.remove(player.uniqueId)
        if (notify) {
            messageService.send(player, "custom-title-cancelled")
        }
    }

    fun activeDraft(player: Player): CustomTitleDraft? {
        val draft = previewSessions[player.uniqueId] ?: return null
        val timeout = config.customTitleSettings.sessionTimeoutSeconds * 1000
        if (System.currentTimeMillis() - draft.updatedAt > timeout) {
            previewSessions.remove(player.uniqueId)
            return null
        }
        return draft
    }

    fun submitText(player: Player, input: String): ValidationResult {
        val draft = activeDraft(player) ?: return ValidationResult(false, "custom-title-no-session")
        if (input.equals("cancel", true)) {
            cancelDraft(player)
            return ValidationResult(false, null)
        }
        val preset = config.customTitleSettings.presets[draft.presetId] ?: return ValidationResult(false, "custom-title-invalid-preset")
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
        draft.randomSchemes = generateRandomSchemes(preset)
        draft.selectedSchemeIndex = 0
        draft.updatedAt = System.currentTimeMillis()
        return ValidationResult(true, null)
    }

    fun cycleScheme(player: Player, step: Int): String? {
        val draft = activeDraft(player) ?: return null
        if (draft.randomSchemes.isEmpty()) return null
        val size = draft.randomSchemes.size
        draft.selectedSchemeIndex = (draft.selectedSchemeIndex + step).floorMod(size)
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

    fun applyManualColors(player: Player, colors: List<String>): ValidationResult {
        val draft = activeDraft(player) ?: return ValidationResult(false, "custom-title-no-session")
        val preset = config.customTitleSettings.presets[draft.presetId] ?: return ValidationResult(false, "custom-title-invalid-preset")
        if (!preset.allowManualColors) return ValidationResult(false, "custom-title-manual-disabled")
        val normalized = colors.map { normalizeHex(it) ?: return ValidationResult(false, "custom-title-invalid-color", it) }
        draft.manualColors = normalized.toMutableList()
        draft.updatedAt = System.currentTimeMillis()
        return ValidationResult(true, null)
    }

    fun previewText(player: Player): String? {
        val draft = activeDraft(player) ?: return null
        val preset = config.customTitleSettings.presets[draft.presetId] ?: return null
        val colors = currentColors(draft)
        if (draft.rawText.isBlank()) return null
        return Support.apply(preset.previewTemplate, mapOf("title" to colorize(draft.rawText, colors)))
    }

    fun confirm(player: Player): String? {
        val draft = activeDraft(player) ?: return null
        val preset = config.customTitleSettings.presets[draft.presetId] ?: return null
        if (draft.rawText.isBlank()) return null
        val data = repository.get(player.uniqueId)
        val customId = "custom-${System.currentTimeMillis()}"
        val custom = CustomTitleData(
            id = customId,
            rawText = draft.rawText,
            presetId = draft.presetId,
            manualColors = draft.manualColors.toMutableList(),
            randomSchemes = draft.randomSchemes.map { it.toMutableList() }.toMutableList(),
            selectedSchemeIndex = draft.selectedSchemeIndex,
            createdAt = System.currentTimeMillis(),
        )
        data.customTitles[customId] = custom
        if (preset.equipAfterConfirm) {
            data.equippedCustomTitleId = customId
        }
        repository.saveAsync(data)
        previewSessions.remove(player.uniqueId)
        return render(custom)
    }

    fun currentDisplay(player: Player): String? {
        val id = repository.get(player.uniqueId).equippedCustomTitleId ?: return null
        val custom = repository.get(player.uniqueId).customTitles[id] ?: return null
        return render(custom)
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
        if (data.titleCoinBalance <= 0.0 && config.customTitleSettings.defaultTitleCoinBalance > 0.0) {
            data.titleCoinBalance = config.customTitleSettings.defaultTitleCoinBalance
            repository.saveAsync(data)
        }
    }

    private fun generateRandomSchemes(preset: CustomTitlePreset): MutableList<MutableList<String>> {
        if (preset.palettes.isEmpty()) {
            return mutableListOf(mutableListOf("#FFFFFF"))
        }
        val result = mutableListOf<MutableList<String>>()
        repeat(preset.maxSchemes) {
            val palette = preset.palettes.random(Random(System.nanoTime()))
            val colors = mutableListOf<String>()
            repeat(min(preset.colorsPerScheme, palette.size)) { index ->
                colors += normalizeHex(palette[index]) ?: "#FFFFFF"
            }
            if (colors.isEmpty()) colors += "#FFFFFF"
            result += colors
        }
        return result
    }

    private fun currentColors(draft: CustomTitleDraft): List<String> {
        if (draft.manualColors.isNotEmpty()) return draft.manualColors
        return draft.randomSchemes.getOrNull(draft.selectedSchemeIndex) ?: listOf("#FFFFFF")
    }

    private fun render(custom: CustomTitleData): String {
        val colors = if (custom.manualColors.isNotEmpty()) custom.manualColors else custom.randomSchemes.getOrNull(custom.selectedSchemeIndex).orEmpty()
        return colorize(custom.rawText, if (colors.isEmpty()) listOf("#FFFFFF") else colors)
    }

    private fun colorize(text: String, colors: List<String>): String {
        if (text.isBlank()) return text
        val palette = if (colors.isEmpty()) listOf("#FFFFFF") else colors
        val builder = StringBuilder()
        text.forEachIndexed { index, char ->
            val color = palette[index % palette.size]
            builder.append('&').append(color).append(char)
        }
        return Support.color(builder.toString())
    }

    private fun normalizeHex(input: String): String? {
        val raw = input.trim().removePrefix("&").removePrefix("#")
        return if (raw.matches(Regex("[A-Fa-f0-9]{6}"))) "#${raw.uppercase(Locale.ROOT)}" else null
    }

    private fun Int.floorMod(mod: Int): Int = ((this % mod) + mod) % mod
}

data class CustomTitleDraft(
    val presetId: String,
    var rawText: String = "",
    var manualColors: MutableList<String> = mutableListOf(),
    var randomSchemes: MutableList<MutableList<String>> = mutableListOf(),
    var selectedSchemeIndex: Int = 0,
    var updatedAt: Long = System.currentTimeMillis(),
)

data class ValidationResult(
    val success: Boolean,
    val messageKey: String?,
    val args: Any? = null,
)
