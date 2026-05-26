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
import org.bukkit.plugin.java.JavaPlugin
import java.util.Locale
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

class ChallengeProgressService(
    private val repository: PlayerDataRepository,
    private val moveSampleIntervalMillis: Long = 1_000L,
    private val progressSaveIntervalMillis: Long = 30_000L,
    private val plugin: JavaPlugin? = null,
) {
    private val lastProgressSaveAt = ConcurrentHashMap<UUID, Long>()

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

    fun recordStatistic(player: Player, statistic: Statistic, newValue: Int, material: Material? = null, entityType: EntityType? = null) {
        mutate(player.uniqueId) { data ->
            val value = newValue.toLong()
            val statisticKey = statistic.name.lowercase(Locale.ROOT)
            data.setMax("challenge:stat:$statisticKey", value)
            if (material != null) {
                data.setMax("challenge:stat:$statisticKey:${material.name.lowercase(Locale.ROOT)}", value)
            }
            if (entityType != null) {
                data.setMax("challenge:stat:$statisticKey:${entityType.name.lowercase(Locale.ROOT)}", value)
            }
        }
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
        val world = to.world ?: return
        mutate(player.uniqueId, saveWhen = { data ->
            System.currentTimeMillis() - data.challengeProgress.lastMoveSampleAt >= moveSampleIntervalMillis
        }) { data ->
            val now = System.currentTimeMillis()
            val progress = data.challengeProgress
            val worldName = worldKey(world)
            val biome = biomeKey(to.block.biome)
            data.setMax("challenge:biome:$biome", 1L)
            data.setMax("challenge:height:$worldName", to.blockY.toLong())
            if (biome == "deep_dark" || to.blockY <= -45) {
                data.challengeProgress.values.merge(
                    "challenge:deep_dark_stay",
                    ((now - progress.lastMoveSampleAt).coerceAtLeast(0L) / 1000L).coerceAtLeast(1L),
                    Long::plus,
                )
            }
            val lastWorld = progress.lastWorld
            val lastX = progress.lastX
            val lastY = progress.lastY
            val lastZ = progress.lastZ
            if (lastWorld == world.name && lastX != null && lastY != null && lastZ != null) {
                val distance = to.distance(Location(world, lastX, lastY, lastZ))
                if (distance.isFinite() && distance > 0.0 && distance < 128.0) {
                    data.challengeProgress.values.merge("challenge:distance:$worldName", distance.toLong(), Long::plus)
                }
            }
            progress.lastMoveSampleAt = now
            progress.lastWorld = world.name
            progress.lastX = to.x
            progress.lastY = to.y
            progress.lastZ = to.z
        }
    }

    fun canClaim(player: Player, conditions: List<String>, context: String = "challenge conditions"): Boolean {
        val data = repository.requireLoaded(player) ?: return false
        return conditions.filter { it.startsWith("challenge:", ignoreCase = true) }
            .all { condition -> evaluate(data.challengeProgress.values, condition, context) }
    }

    fun progress(player: Player, key: String): Long {
        return repository.requireLoaded(player)?.challengeProgress?.values?.get(key.normalized()) ?: 0L
    }

    private fun mutate(
        uniqueId: UUID,
        saveWhen: (cn.aing.uptags.model.runtime.PlayerTagData) -> Boolean = { true },
        block: (cn.aing.uptags.model.runtime.PlayerTagData) -> Unit,
    ) {
        val data = repository.getCached(uniqueId)?.takeIf { repository.isLoaded(uniqueId) } ?: return
        if (!saveWhen(data)) {
            return
        }
        block(data)
        repository.markDirty(data)
        saveProgressIfDue(data)
    }

    private fun saveProgressIfDue(data: cn.aing.uptags.model.runtime.PlayerTagData) {
        val interval = progressSaveIntervalMillis.coerceAtLeast(0L)
        val now = System.currentTimeMillis()
        val last = lastProgressSaveAt[data.uniqueId] ?: 0L
        if (now - last < interval) {
            return
        }
        lastProgressSaveAt[data.uniqueId] = now
        repository.saveAsync(data)
    }

    private fun evaluate(values: Map<String, Long>, rawCondition: String, context: String): Boolean {
        val parts = rawCondition.split(':')
        if (parts.size < 4 || !parts[0].equals("challenge", ignoreCase = true)) {
            warnInvalid(rawCondition, context, "format")
            return false
        }
        val required = parts.last().toLongOrNull()
        if (required == null) {
            warnInvalid(rawCondition, context, "required value is not a number")
            return false
        }
        val key = parts.dropLast(1).joinToString(":").normalized()
        return values.getOrDefault(key, 0L) >= required
    }

    private fun cn.aing.uptags.model.runtime.PlayerTagData.setMax(key: String, value: Long) {
        val normalized = key.normalized()
        val current = challengeProgress.values[normalized] ?: 0L
        if (value > current) {
            challengeProgress.values[normalized] = value
        }
    }

    private fun warnInvalid(condition: String, context: String, reason: String) {
        plugin?.logger?.warning("Invalid challenge condition at $context: '$condition' ($reason); fail-closed.")
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
