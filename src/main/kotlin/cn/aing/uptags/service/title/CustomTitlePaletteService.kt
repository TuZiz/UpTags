package cn.aing.uptags.service.title

import cn.aing.uptags.Support
import cn.aing.uptags.model.config.CurrencyType
import cn.aing.uptags.model.config.CustomTitlePreset
import java.util.LinkedHashSet
import java.util.Locale
import kotlin.random.Random

internal class CustomTitlePaletteService {
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

    fun generatePreviewSchemes(preset: CustomTitlePreset, selectedLibrary: Int? = null): MutableList<MutableList<String>> {
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

        val randomPool = preset.randomColorPool.mapNotNull(Support::normalizeHex)
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

    fun paletteLibraries(preset: CustomTitlePreset): List<Int> {
        val configured = configuredPaletteLibraries(preset)
        val fallback = if (preset.randomColorPool.isNotEmpty()) listOf(1, 2, 3, 4) else emptyList()
        return (configured + fallback).distinct().sorted()
            .ifEmpty { listOf(colorsPerSchemeOf(preset).coerceIn(1, 4)) }
    }

    fun configuredPaletteLibraries(preset: CustomTitlePreset): List<Int> {
        val fromPalettes = preset.palettes
            .map { it.mapNotNull(Support::normalizeHex).size }
            .filter { it in 1..4 }
        return fromPalettes.distinct().sorted()
    }

    fun availableManualColors(preset: CustomTitlePreset, library: Int): List<String> {
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

    fun defaultPaletteLibrary(draft: CustomTitleDraft, preset: CustomTitlePreset): Int? {
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

    fun allowedPaletteLibraries(draft: CustomTitleDraft, preset: CustomTitlePreset): List<Int> {
        val maxColors = currencyPaletteLimit(draft.currencyType)
        return paletteLibraries(preset).filter { it <= maxColors }
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
