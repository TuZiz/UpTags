package cn.aing.uptags.service.tag

import org.bukkit.Bukkit
import org.bukkit.entity.Player
import java.util.regex.Pattern

internal class TagConditionChecker {
    private val conditionPattern = Pattern.compile("(.+?)(==|!=|>=|<=|>|<)(.+)")

    fun check(player: Player, conditions: List<String>): Boolean {
        if (conditions.isEmpty()) return true
        for (condition in conditions) {
            val rendered = PlaceholderHook.apply(player, condition)
            val matcher = conditionPattern.matcher(rendered)
            if (!matcher.matches()) {
                Bukkit.getLogger().warning("Invalid UpTags condition '$condition' for ${player.uniqueId}; failing closed.")
                return false
            }
            val left = matcher.group(1).trim()
            val operator = matcher.group(2)
            val right = matcher.group(3).trim()
            if (containsUnresolvedPlaceholder(left) || containsUnresolvedPlaceholder(right)) {
                Bukkit.getLogger().warning("Unresolved PlaceholderAPI variable in UpTags condition '$condition' for ${player.uniqueId}; failing closed.")
                return false
            }
            if (!compare(left, operator, right)) {
                return false
            }
        }
        return true
    }

    private fun compare(left: String, operator: String, right: String): Boolean {
        return try {
            val leftNumber = left.toDouble()
            val rightNumber = right.toDouble()
            when (operator) {
                "==" -> leftNumber == rightNumber
                "!=" -> leftNumber != rightNumber
                ">=" -> leftNumber >= rightNumber
                "<=" -> leftNumber <= rightNumber
                ">" -> leftNumber > rightNumber
                "<" -> leftNumber < rightNumber
                else -> false
            }
        } catch (_: NumberFormatException) {
            when (operator) {
                "==" -> left.equals(right, ignoreCase = true)
                "!=" -> !left.equals(right, ignoreCase = true)
                else -> false
            }
        }
    }

    companion object {
        private val unresolvedPlaceholderPattern = Pattern.compile("%[^%\\s]+%")

        internal fun containsUnresolvedPlaceholder(value: String): Boolean =
            unresolvedPlaceholderPattern.matcher(value).find()
    }
}

private object PlaceholderHook {
    private var available = true

    fun apply(player: Player, text: String): String {
        if (!available || Bukkit.getPluginManager().getPlugin("PlaceholderAPI")?.isEnabled != true) {
            return text
        }
        return try {
            val clazz = Class.forName("me.clip.placeholderapi.PlaceholderAPI")
            val method = clazz.getMethod("setPlaceholders", Player::class.java, String::class.java)
            method.invoke(null, player, text) as? String ?: text
        } catch (_: Throwable) {
            available = false
            text
        }
    }
}
