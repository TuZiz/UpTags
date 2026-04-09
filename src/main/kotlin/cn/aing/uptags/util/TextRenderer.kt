package cn.aing.uptags.util

import kotlin.math.pow
import kotlin.math.roundToInt
import net.md_5.bungee.api.ChatColor as BungeeChatColor
import org.bukkit.ChatColor

object TextRenderer {
    private val hexPattern = Regex("&?#([A-Fa-f0-9]{6})")
    private val rgbPattern = Regex("[A-Fa-f0-9]{6}")
    private const val resetCode = "§r"

    fun color(value: String?): String = translate(value, forceNonItalic = false)

    fun noItalic(value: String?): String = translate(value, forceNonItalic = true)

    fun stripColor(value: String?): String = ChatColor.stripColor(color(value)) ?: ""

    fun colorLines(lines: List<String>): List<String> = lines.map(::color)

    fun noItalicLines(lines: List<String>): List<String> = lines.map(::noItalic)

    fun renderPaletteText(text: String?, palette: List<String>): String {
        val visible = stripColor(text).trim()
        if (visible.isEmpty()) {
            return ""
        }
        val normalizedPalette = palette.mapNotNull(::normalizeHex).ifEmpty { listOf("#FFFFFF") }
        if (normalizedPalette.size == 1 || visible.length == 1) {
            return color(buildString {
                visible.forEach { char ->
                    append('&').append(normalizedPalette.first()).append(char)
                }
            })
        }

        val stops = normalizedPalette.mapNotNull(::hexToRgb)
        val builder = StringBuilder()
        visible.forEachIndexed { index, char ->
            builder.append('&').append(interpolatedHex(stops, index, visible.lastIndex)).append(char)
        }
        return color(builder.toString())
    }

    fun normalizeHex(value: String?): String? {
        val raw = value?.trim()?.removePrefix("&")?.removePrefix("#") ?: return null
        return if (raw.matches(rgbPattern)) "#${raw.uppercase()}" else null
    }

    private fun interpolatedHex(stops: List<RgbColor>, index: Int, lastIndex: Int): String {
        if (stops.isEmpty()) {
            return "#FFFFFF"
        }
        if (stops.size == 1 || lastIndex <= 0) {
            return stops.first().toHex()
        }

        val segmentCount = stops.size - 1
        val progress = index.toDouble() / lastIndex.toDouble()
        val scaled = progress * segmentCount
        val segmentIndex = scaled.toInt().coerceIn(0, segmentCount - 1)
        val localProgress = smoothStep(scaled - segmentIndex)
        return stops[segmentIndex].mix(stops[segmentIndex + 1], localProgress).toHex()
    }

    private fun smoothStep(progress: Double): Double {
        val clamped = progress.coerceIn(0.0, 1.0)
        return clamped * clamped * (3.0 - 2.0 * clamped)
    }

    private fun hexToRgb(value: String): RgbColor? {
        val normalized = normalizeHex(value)?.removePrefix("#") ?: return null
        return RgbColor(
            red = normalized.substring(0, 2).toInt(16),
            green = normalized.substring(2, 4).toInt(16),
            blue = normalized.substring(4, 6).toInt(16),
        )
    }

    private fun translate(value: String?, forceNonItalic: Boolean): String {
        if (value.isNullOrEmpty()) {
            return ""
        }
        val withHex = hexPattern.replace(value) { match ->
            BungeeChatColor.of("#${match.groupValues[1]}").toString()
        }
        val translated = ChatColor.translateAlternateColorCodes('&', withHex)
        return if (forceNonItalic && !translated.startsWith(resetCode)) "$resetCode$translated" else translated
    }

    private data class RgbColor(
        val red: Int,
        val green: Int,
        val blue: Int,
    ) {
        fun mix(other: RgbColor, progress: Double): RgbColor {
            fun interpolate(start: Int, end: Int): Int {
                val startLinear = start.toLinearChannel()
                val endLinear = end.toLinearChannel()
                val mixed = startLinear + ((endLinear - startLinear) * progress)
                return mixed.toSrgbChannel()
            }
            return RgbColor(
                red = interpolate(red, other.red),
                green = interpolate(green, other.green),
                blue = interpolate(blue, other.blue),
            )
        }

        fun toHex(): String = "#%02X%02X%02X".format(red, green, blue)
    }

    private fun Int.toLinearChannel(): Double {
        val srgb = this.coerceIn(0, 255) / 255.0
        return if (srgb <= 0.04045) {
            srgb / 12.92
        } else {
            ((srgb + 0.055) / 1.055).pow(2.4)
        }
    }

    private fun Double.toSrgbChannel(): Int {
        val clamped = coerceIn(0.0, 1.0)
        val srgb = if (clamped <= 0.0031308) {
            clamped * 12.92
        } else {
            1.055 * clamped.pow(1.0 / 2.4) - 0.055
        }
        return (srgb * 255.0).roundToInt().coerceIn(0, 255)
    }
}
