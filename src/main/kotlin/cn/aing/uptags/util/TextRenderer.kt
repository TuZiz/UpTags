package cn.aing.uptags.util

import net.md_5.bungee.api.ChatColor as BungeeChatColor
import org.bukkit.ChatColor

object TextRenderer {
    private val hexPattern = Regex("&?#([A-Fa-f0-9]{6})")
    private const val resetCode = "§r"

    fun color(value: String?): String = translate(value, forceNonItalic = false)

    fun noItalic(value: String?): String = translate(value, forceNonItalic = true)

    fun stripColor(value: String?): String = ChatColor.stripColor(color(value)) ?: ""

    fun colorLines(lines: List<String>): List<String> = lines.map(::color)

    fun noItalicLines(lines: List<String>): List<String> = lines.map(::noItalic)

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
}
