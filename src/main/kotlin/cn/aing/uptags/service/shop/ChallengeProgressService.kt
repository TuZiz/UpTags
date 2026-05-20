package cn.aing.uptags.service.shop

import org.bukkit.entity.Player
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

class ChallengeProgressService {
    private val stats = ConcurrentHashMap<UUID, MutableMap<String, Long>>()

    fun increment(player: Player, key: String, amount: Long = 1L) {
        stats.computeIfAbsent(player.uniqueId) { ConcurrentHashMap() }
            .merge(key.normalized(), amount.coerceAtLeast(0L), Long::plus)
    }

    fun set(player: Player, key: String, value: Long) {
        stats.computeIfAbsent(player.uniqueId) { ConcurrentHashMap() }[key.normalized()] = value.coerceAtLeast(0L)
    }

    fun canClaim(player: Player, conditions: List<String>): Boolean {
        return conditions.all { condition -> evaluate(player, condition) }
    }

    fun evaluate(player: Player, rawCondition: String): Boolean {
        val parts = rawCondition.split(':')
        if (parts.size < 3 || !parts[0].equals("challenge", ignoreCase = true)) {
            return false
        }
        val key = parts.dropLast(1).joinToString(":").normalized()
        val required = parts.last().toLongOrNull() ?: return false
        return stats[player.uniqueId]?.getOrDefault(key, 0L).orZero() >= required
    }

    private fun String.normalized(): String = trim().lowercase()

    private fun Long?.orZero(): Long = this ?: 0L
}
