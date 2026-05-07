package cn.aing.uptags.service

import cn.aing.uptags.Support
import cn.aing.uptags.model.config.CurrencyType
import cn.aing.uptags.model.config.CustomTitlePreset

internal class CustomTitleManualPaletteService(
    private val economyBridge: EconomyBridge,
    private val palettes: CustomTitlePaletteService,
    private val manualColorsPerPage: Int = 12,
) {
    fun selectPaletteLibrary(
        draft: CustomTitleDraft,
        preset: CustomTitlePreset,
        colorCount: Int,
    ): ValidationResult {
        if (colorCount !in palettes.paletteLibraries(preset)) {
            return ValidationResult(false, "custom-title-library-unavailable", palettes.paletteLibraryName(colorCount))
        }
        if (colorCount !in palettes.allowedPaletteLibraries(draft, preset)) {
            return ValidationResult(
                false,
                "custom-title-library-locked",
                arrayOf(palettes.paletteLibraryName(colorCount), economyBridge.displayName(draft.currencyType ?: CurrencyType.POINTS)),
            )
        }
        draft.selectedPaletteLibrary = colorCount
        draft.manualColorTarget = null
        draft.manualColorPage = 0
        draft.manualColors.clear()
        draft.hexBuffer = ""
        draft.randomSchemes = palettes.generatePreviewSchemes(preset, colorCount)
        draft.selectedSchemeIndex = 0
        draft.updatedAt = System.currentTimeMillis()
        return ValidationResult(true, null)
    }

    fun beginManualPaletteEditing(
        draft: CustomTitleDraft,
        preset: CustomTitlePreset,
        colorCount: Int? = null,
    ): ValidationResult {
        if (!preset.allowManualColors) {
            return ValidationResult(false, "custom-title-manual-disabled")
        }

        val targetLibrary = colorCount
            ?: draft.selectedPaletteLibrary
            ?: palettes.defaultPaletteLibrary(draft, preset)
            ?: return ValidationResult(false, "custom-title-library-unavailable", "可用")

        if (targetLibrary !in palettes.paletteLibraries(preset)) {
            return ValidationResult(false, "custom-title-library-unavailable", palettes.paletteLibraryName(targetLibrary))
        }
        if (targetLibrary !in palettes.allowedPaletteLibraries(draft, preset)) {
            return ValidationResult(
                false,
                "custom-title-library-locked",
                arrayOf(palettes.paletteLibraryName(targetLibrary), economyBridge.displayName(draft.currencyType ?: CurrencyType.POINTS)),
            )
        }

        draft.selectedPaletteLibrary = targetLibrary
        draft.randomSchemes = palettes.generatePreviewSchemes(preset, targetLibrary)
        draft.selectedSchemeIndex = 0
        if (palettes.availableManualColors(preset, targetLibrary).isEmpty()) {
            return ValidationResult(false, "custom-title-library-unavailable", palettes.paletteLibraryName(targetLibrary))
        }
        draft.manualColorTarget = targetLibrary
        draft.manualColorPage = 0
        draft.manualColors.clear()
        draft.hexBuffer = ""
        draft.updatedAt = System.currentTimeMillis()
        return ValidationResult(true, null)
    }

    fun manualColorChoices(draft: CustomTitleDraft, preset: CustomTitlePreset): List<String> {
        val library = draft.manualColorTarget ?: draft.selectedPaletteLibrary ?: return emptyList()
        return palettes.availableManualColors(preset, library)
    }

    fun manualColorPage(draft: CustomTitleDraft, preset: CustomTitlePreset): ManualColorPage {
        val colors = manualColorChoices(draft, preset)
        val totalPages = if (colors.isEmpty()) 1 else ((colors.size - 1) / manualColorsPerPage) + 1
        val pageIndex = draft.manualColorPage.coerceIn(0, totalPages - 1)
        draft.manualColorPage = pageIndex
        val pageOffset = pageIndex * manualColorsPerPage
        return ManualColorPage(
            colors = colors.drop(pageOffset).take(manualColorsPerPage),
            pageIndex = pageIndex,
            totalPages = totalPages,
            pageOffset = pageOffset,
        )
    }

    fun emptyPage(): ManualColorPage {
        return ManualColorPage(emptyList(), pageIndex = 0, totalPages = 1, pageOffset = 0)
    }

    fun changeManualColorPage(draft: CustomTitleDraft, preset: CustomTitlePreset, delta: Int): ValidationResult {
        draft.manualColorTarget ?: return ValidationResult(false, "custom-title-preview-help")
        val totalChoices = manualColorChoices(draft, preset).size
        val totalPages = if (totalChoices == 0) 1 else ((totalChoices - 1) / manualColorsPerPage) + 1
        draft.manualColorPage = (draft.manualColorPage + delta).coerceIn(0, totalPages - 1)
        draft.updatedAt = System.currentTimeMillis()
        return ValidationResult(true, null)
    }

    fun selectManualColor(
        draft: CustomTitleDraft,
        preset: CustomTitlePreset,
        colorIndex: Int,
    ): ValidationResult {
        val target = draft.manualColorTarget ?: return ValidationResult(false, "custom-title-preview-help")
        val choices = palettes.availableManualColors(preset, target)
        val color = choices.getOrNull(colorIndex)
            ?: return ValidationResult(false, "custom-title-library-unavailable", palettes.paletteLibraryName(target))
        if (draft.manualColors.size >= target) {
            return ValidationResult(false, "custom-title-manual-limit", target)
        }
        draft.manualColors += color
        draft.updatedAt = System.currentTimeMillis()
        return ValidationResult(true, null)
    }

    fun removeLastManualColor(draft: CustomTitleDraft): ValidationResult {
        if (draft.manualColorTarget == null) {
            return ValidationResult(false, "custom-title-preview-help")
        }
        if (draft.manualColors.isNotEmpty()) {
            draft.manualColors.removeAt(draft.manualColors.lastIndex)
        }
        draft.updatedAt = System.currentTimeMillis()
        return ValidationResult(true, null)
    }

    fun clearManualColors(draft: CustomTitleDraft): ValidationResult {
        if (draft.manualColorTarget == null) {
            return ValidationResult(false, "custom-title-preview-help")
        }
        draft.manualColors.clear()
        draft.hexBuffer = ""
        draft.manualColorPage = 0
        draft.updatedAt = System.currentTimeMillis()
        return ValidationResult(true, null)
    }

    fun finishManualPaletteEditing(draft: CustomTitleDraft): ValidationResult {
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

    fun cancelManualPaletteEditing(draft: CustomTitleDraft): ValidationResult {
        draft.manualColorTarget = null
        draft.manualColors.clear()
        draft.hexBuffer = ""
        draft.manualColorPage = 0
        draft.updatedAt = System.currentTimeMillis()
        return ValidationResult(true, null)
    }

    fun autoComposePalette(draft: CustomTitleDraft, preset: CustomTitlePreset): ValidationResult {
        val allowedLibraries = palettes.allowedPaletteLibraries(draft, preset)
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
        draft.randomSchemes = palettes.generatePreviewSchemes(preset, targetLibrary)
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

    fun applyManualColors(draft: CustomTitleDraft, colors: List<String>) {
        val normalized = colors.mapNotNull(Support::normalizeHex)
        draft.manualColors.clear()
        draft.manualColors.addAll(normalized)
        draft.manualColorTarget = normalized.size.takeIf { it > 0 }
        draft.selectedPaletteLibrary = normalized.size.takeIf { it in 1..4 } ?: draft.selectedPaletteLibrary
        draft.hexBuffer = ""
        draft.updatedAt = System.currentTimeMillis()
    }
}
