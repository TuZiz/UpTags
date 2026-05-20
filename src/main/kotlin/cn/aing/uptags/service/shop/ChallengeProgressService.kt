package cn.aing.uptags.service.shop

import cn.aing.uptags.repository.PlayerDataRepository
import org.bukkit.Location
import org.bukkit.Material
import org.bukkit.Statistic
import org.bukkit.World
import org.bukkit.block.Biome
import org.bukkit.entity.EntityType
import org.bukkit.entity.Player
import org.bukkit.event.player.PlayerAdvancementDoneEvent
import java.util.Locale
import java.util.UUID

class ChallengeProgressService(
    private val repository: PlayerDataRepository,
    private val moveSampleIntervalMillis: Long = 1_000L,
) {
    fun increment(player: Player, key: String, amount: Long = 1L) {
        mutate(player.uniqueId) { data ->
            data.challengeProgress.values.merge(key.normalized(), amount.coerceAtLeast(0L), Long::plus)
        }
    }

    fun set(player: Player, key: String, value: Long) {
        mutate(player.uniqueId) { data ->
            val normalized = key.normalized()
            val current = data.challengeProgress.values[normalized] ?: 0L
            if (value > current) {
                data.challengeProgress.values[normalized] = value
            }
        }
    }

    fun recordStatistic(player: Player, statistic: Statistic, newValue: Int) {
        set(player, "challenge:stat:${statistic.name.lowercase(Locale.ROOT)}", newValue.toLong())
    }

    fun recordBlockBreak(player: Player, material: Material) {
        val materialKey = material.name.lowercase(Locale.ROOT)
        increment(player, "challenge:mine:$materialKey")
        increment(player, "challenge:collect:$materialKey")
    }

    fun recordKill(player: Player, entityType: EntityType) {
        increment(player, "challenge:kill:${entityType.name.lowercase(Locale.ROOT)}")
    }

    fun recordWorld(player: Player, world: World) {
        set(player, "challenge:world:${worldKey(world)}", 1L)
    }

    fun recordAdvancement(event: PlayerAdvancementDoneEvent) {
        val key = event.advancement.key
        set(event.player, "challenge:advancement:${key.namespace}:${key.key}", 1L)
    }

    fun recordMoveSample(player: Player, to: Location) {
        val data = repository.requireLoaded(player) ?: return
        val now = System.currentTimeMillis()
        val progress = data.challengeProgress
        if (now - progress.lastMoveSampleAt < moveSampleIntervalMillis) {
            return
        }
        val world = to.world ?: return
        val worldName = worldKey(world)
        set(player, "challenge:biome:${biomeKey(to.block.biome)}", 1L)
        set(player, "challenge:height:$worldName", to.blockY.toLong())
        if (biomeKey(to.block.biome) == "deep_dark" || to.blockY <= -45) {
            increment(player, "challenge:deep_dark_stay", ((now - progress.lastMoveSampleAt).coerceAtLeast(0L) / 1000L).coerceAtLeast(1L))
        }
        val lastWorld = progress.lastWorld
        val lastX = progress.lastX
        val lastY = progress.lastY
        val lastZ = progress.lastZ
        if (lastWorld == world.name && lastX != null && lastY != null && lastZ != null) {
            val distance = to.distance(Location(world, lastX, lastY, lastZ))
            if (distance.isFinite() && distance > 0.0 && distance < 128.0) {
                increment(player, "challenge:distance:$worldName", distance.toLong())
            }
        }
        progress.lastMoveSampleAt = now
        progress.lastWorld = world.name
        progress.lastX = to.x
        progress.lastY = to.y
        progress.lastZ = to.z
        repository.saveAsync(data)
    }

    fun canClaim(player: Player, conditions: List<String>): Boolean {
        val data = repository.requireLoaded(player) ?: return false
        return conditions.filter { it.startsWith("challenge:", ignoreCase = true) }
            .all { condition -> evaluate(data.challengeProgress.values, condition) }
    }

    fun progress(player: Player, key: String): Long {
        return repository.requireLoaded(player)?.challengeProgress?.values?.get(key.normalized()) ?: 0L
    }

    private fun mutate(uniqueId: UUID, block: (cn.aing.uptags.model.runtime.PlayerTagData) -> Unit) {
        val data = repository.getCached(uniqueId)?.takeIf { repository.isLoaded(uniqueId) } ?: return
        block(data)
        repository.saveAsync(data)
    }

    private fun evaluate(values: Map<String, Long>, rawCondition: String): Boolean {
        val parts = rawCondition.split(':')
        if (parts.size < 4 || !parts[0].equals("challenge", ignoreCase = true)) {
            return false
        }
        val required = parts.last().toLongOrNull() ?: return false
        val key = parts.dropLast(1).joinToString(":").normalized()
        return values.getOrDefault(key, 0L) >= required
    }

    private fun worldKey(world: World): String {
        return when (world.environment) {
            World.Environment.NORMAL -> "overworld"
            World.Environment.NETHER -> "the_nether"
            World.Environment.THE_END -> "the_end"
            else -> world.name.lowercase(Locale.ROOT)
        }
    }

    private fun biomeKey(biome: Biome): String = biome.name.lowercase(Locale.ROOT)

    private fun String.normalized(): String = trim().lowercase(Locale.ROOT)
}
