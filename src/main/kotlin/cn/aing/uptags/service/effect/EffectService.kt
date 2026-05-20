package cn.aing.uptags.service.effect

import cn.aing.uptags.service.tag.TagService

import cn.aing.uptags.config.ConfigRegistry
import cn.aing.uptags.compat.PlatformScheduler
import cn.aing.uptags.compat.TaskHandle
import org.bukkit.Color
import org.bukkit.Location
import org.bukkit.Particle
import org.bukkit.entity.Player
import org.bukkit.plugin.java.JavaPlugin
import org.bukkit.potion.PotionEffect
import java.util.UUID
import kotlin.math.cos
import kotlin.math.sin

class EffectService(
    private val plugin: JavaPlugin,
    private val scheduler: PlatformScheduler,
    private val config: ConfigRegistry,
    private val tagService: TagService,
) {
    private val playerTasks = LinkedHashMap<UUID, TaskHandle>()
    private var pulse: Long = 0

    fun startPlayer(player: Player) {
        stopPlayer(player.uniqueId)
        val handle = scheduler.runPlayerRepeating(player, config.settings.effectTickInterval, config.settings.effectTickInterval) {
            pulse++
            applyBuffs(player)
            spawnParticles(player)
        }
        if (handle != null) {
            playerTasks[player.uniqueId] = handle
        }
    }

    fun stopPlayer(uniqueId: UUID) {
        playerTasks.remove(uniqueId)?.cancel()
    }

    fun restartAll() {
        stopAll()
        plugin.server.onlinePlayers.forEach(::startPlayer)
    }

    fun stopAll() {
        playerTasks.values.forEach(TaskHandle::cancel)
        playerTasks.clear()
    }

    private fun applyBuffs(player: Player) {
        tagService.activeBuffs(player).forEach { buff ->
            val amplifier = (tagService.activeBuffLevel(player, buff.id) - 1).coerceAtLeast(0)
            player.addPotionEffect(PotionEffect(buff.type, maxOf(buff.duration, config.settings.effectTickInterval.toInt() + 40), amplifier, true, false, true))
        }
    }

    private fun spawnParticles(player: Player) {
        val particle = tagService.selectedParticle(player) ?: return
        val base = player.location.clone()
        when (particle.pattern.lowercase()) {
            "halo" -> {
                ring(base.clone().add(0.0, 2.2, 0.0), 0.65, Particle.END_ROD, 10)
                dustRing(base.clone().add(0.0, 2.18, 0.0), 0.5, 8, Color.fromRGB(255, 220, 120), 1.1f)
            }
            "aura" -> {
                sphere(base.clone().add(0.0, 1.0, 0.0), 0.7, Particle.HAPPY_VILLAGER, 6)
                dustBurst(base.clone().add(0.0, 1.0, 0.0), 4, Color.fromRGB(120, 255, 180), 0.9f)
            }
            "helix" -> {
                helix(base.clone().add(0.0, 0.2, 0.0), 0.55, Particle.ENCHANT)
                dustRing(base.clone().add(0.0, 1.8, 0.0), 0.35, 6, Color.fromRGB(150, 210, 255), 0.8f)
            }
            "spiral" -> {
                spiral(base.clone().add(0.0, 0.2, 0.0), 0.5, Particle.WITCH)
                dustBurst(base.clone().add(0.0, 1.5, 0.0), 3, Color.fromRGB(190, 120, 255), 1.0f)
            }
            "ring" -> {
                ring(base.clone().add(0.0, 1.1, 0.0), 1.0, Particle.CRIT, 14)
                dustRing(base.clone().add(0.0, 1.1, 0.0), 0.82, 10, Color.fromRGB(120, 220, 255), 0.85f)
            }
            "crown" -> {
                crown(base.clone().add(0.0, 2.15, 0.0), Particle.TOTEM_OF_UNDYING)
                dustRing(base.clone().add(0.0, 2.25, 0.0), 0.36, 5, Color.fromRGB(255, 210, 60), 1.2f)
            }
            "orbit" -> orbit(base.clone().add(0.0, 1.3, 0.0), Particle.SOUL_FIRE_FLAME)
            "pulse" -> {
                pulse(base.clone().add(0.0, 0.1, 0.0), Particle.CRIT)
                dustRing(base.clone().add(0.0, 0.1, 0.0), 0.25 + (pulse % 8) * 0.08, 12, Color.fromRGB(255, 80, 80), 1.0f)
            }
            "wings" -> wings(base.clone().add(0.0, 1.2, 0.0), Particle.END_ROD)
            "feet" -> ring(base.clone().add(0.0, 0.15, 0.0), 0.45, Particle.CLOUD, 8)
            "flame" -> {
                ring(base.clone().add(0.0, 0.2, 0.0), 0.55, Particle.FLAME, 10)
                ring(base.clone().add(0.0, 0.25, 0.0), 0.45, Particle.SMOKE, 8)
            }
            "totem" -> {
                sphere(base.clone().add(0.0, 1.3, 0.0), 0.45, Particle.TOTEM_OF_UNDYING, 5)
                dustBurst(base.clone().add(0.0, 1.5, 0.0), 4, Color.fromRGB(180, 255, 120), 1.0f)
            }
            "spark" -> sphere(base.clone().add(0.0, 1.1, 0.0), 0.9, Particle.FIREWORK, 9)
            "comet" -> comet(base.clone().add(0.0, 1.0, 0.0), Particle.ELECTRIC_SPARK)
            "rain" -> rain(base.clone().add(0.0, 2.2, 0.0), Particle.WAX_ON)
            "shield" -> {
                shield(base.clone().add(0.0, 1.2, 0.0), Particle.ENCHANT)
                dustShield(base.clone().add(0.0, 1.2, 0.0), Color.fromRGB(90, 180, 255), 1.0f)
            }
            else -> ring(base.clone().add(0.0, 2.0, 0.0), 0.65, Particle.END_ROD, 10)
        }
    }

    private fun ring(center: Location, radius: Double, particle: Particle, points: Int) {
        val world = center.world ?: return
        repeat(points) { index ->
            val angle = ((Math.PI * 2) / points) * index + (pulse * 0.12)
            world.spawnParticle(particle, center.x + cos(angle) * radius, center.y, center.z + sin(angle) * radius, 1, 0.0, 0.0, 0.0, 0.0)
        }
    }

    private fun sphere(center: Location, radius: Double, particle: Particle, points: Int) {
        val world = center.world ?: return
        repeat(points) { index ->
            val angle = pulse * 0.17 + (Math.PI * 2 / points) * index
            val x = cos(angle) * radius
            val z = sin(angle) * radius
            val y = sin(angle * 1.4) * 0.35
            world.spawnParticle(particle, center.x + x, center.y + y, center.z + z, 1, 0.0, 0.0, 0.0, 0.0)
        }
    }

    private fun helix(center: Location, radius: Double, particle: Particle) {
        val world = center.world ?: return
        repeat(8) { index ->
            val angle = pulse * 0.18 + index * 0.65
            val y = (index * 0.15) % 1.6
            world.spawnParticle(particle, center.x + cos(angle) * radius, center.y + y, center.z + sin(angle) * radius, 1, 0.0, 0.0, 0.0, 0.0)
            world.spawnParticle(particle, center.x - cos(angle) * radius, center.y + y, center.z - sin(angle) * radius, 1, 0.0, 0.0, 0.0, 0.0)
        }
    }

    private fun spiral(center: Location, radius: Double, particle: Particle) {
        val world = center.world ?: return
        repeat(10) { index ->
            val angle = pulse * 0.2 + index * 0.45
            val stepRadius = radius + index * 0.03
            world.spawnParticle(particle, center.x + cos(angle) * stepRadius, center.y + index * 0.12, center.z + sin(angle) * stepRadius, 1, 0.0, 0.0, 0.0, 0.0)
        }
    }

    private fun crown(center: Location, particle: Particle) {
        ring(center, 0.45, particle, 6)
        center.world?.spawnParticle(particle, center.x, center.y + 0.2, center.z, 1, 0.0, 0.0, 0.0, 0.0)
    }

    private fun orbit(center: Location, particle: Particle) {
        val world = center.world ?: return
        val angle = pulse * 0.16
        world.spawnParticle(particle, center.x + cos(angle) * 0.8, center.y + 0.25, center.z + sin(angle) * 0.8, 1, 0.0, 0.0, 0.0, 0.0)
        world.spawnParticle(particle, center.x - cos(angle) * 0.8, center.y - 0.05, center.z - sin(angle) * 0.8, 1, 0.0, 0.0, 0.0, 0.0)
    }

    private fun pulse(center: Location, particle: Particle) = ring(center, 0.35 + (pulse % 8) * 0.08, particle, 12)

    private fun wings(center: Location, particle: Particle) {
        val world = center.world ?: return
        repeat(5) { index ->
            val offset = 0.15 * index
            world.spawnParticle(particle, center.x + 0.25 + offset, center.y + 0.2 + offset * 0.3, center.z - 0.2, 1, 0.0, 0.0, 0.0, 0.0)
            world.spawnParticle(particle, center.x - 0.25 - offset, center.y + 0.2 + offset * 0.3, center.z - 0.2, 1, 0.0, 0.0, 0.0, 0.0)
        }
    }

    private fun comet(center: Location, particle: Particle) {
        val world = center.world ?: return
        val angle = pulse * 0.18
        repeat(4) { index ->
            val distance = 0.3 * index
            world.spawnParticle(particle, center.x + cos(angle) * distance, center.y + 0.1 * index, center.z + sin(angle) * distance, 1, 0.0, 0.0, 0.0, 0.0)
        }
    }

    private fun rain(center: Location, particle: Particle) {
        val world = center.world ?: return
        repeat(6) { index ->
            val angle = (Math.PI * 2 / 6) * index
            world.spawnParticle(particle, center.x + cos(angle) * 0.5, center.y - (pulse % 5) * 0.12, center.z + sin(angle) * 0.5, 1, 0.0, 0.0, 0.0, 0.0)
        }
    }

    private fun shield(center: Location, particle: Particle) {
        val world = center.world ?: return
        for (index in -2..2) {
            val y = index * 0.22
            val width = 0.65 - kotlin.math.abs(index) * 0.12
            world.spawnParticle(particle, center.x + width, center.y + y, center.z, 1, 0.0, 0.0, 0.0, 0.0)
            world.spawnParticle(particle, center.x - width, center.y + y, center.z, 1, 0.0, 0.0, 0.0, 0.0)
        }
    }

    private fun dustRing(center: Location, radius: Double, points: Int, color: Color, size: Float) {
        val world = center.world ?: return
        val dust = Particle.DustOptions(color, size)
        repeat(points) { index ->
            val angle = ((Math.PI * 2) / points) * index + (pulse * 0.1)
            world.spawnParticle(Particle.DUST, center.x + cos(angle) * radius, center.y, center.z + sin(angle) * radius, 1, 0.0, 0.0, 0.0, 0.0, dust)
        }
    }

    private fun dustBurst(center: Location, points: Int, color: Color, size: Float) {
        val world = center.world ?: return
        val dust = Particle.DustOptions(color, size)
        repeat(points) { index ->
            val angle = pulse * 0.15 + (Math.PI * 2 / points) * index
            world.spawnParticle(Particle.DUST, center.x + cos(angle) * 0.35, center.y + 0.15 * index / points, center.z + sin(angle) * 0.35, 1, 0.0, 0.0, 0.0, 0.0, dust)
        }
    }

    private fun dustShield(center: Location, color: Color, size: Float) {
        val world = center.world ?: return
        val dust = Particle.DustOptions(color, size)
        for (index in -2..2) {
            val y = index * 0.22
            val width = 0.55 - kotlin.math.abs(index) * 0.1
            world.spawnParticle(Particle.DUST, center.x + width, center.y + y, center.z, 1, 0.0, 0.0, 0.0, 0.0, dust)
            world.spawnParticle(Particle.DUST, center.x - width, center.y + y, center.z, 1, 0.0, 0.0, 0.0, 0.0, dust)
        }
    }
}
