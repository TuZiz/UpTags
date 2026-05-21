package cn.aing.uptags.listener

import cn.aing.uptags.service.shop.ChallengeProgressService
import org.bukkit.entity.Monster
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.event.block.BlockBreakEvent
import org.bukkit.event.entity.EntityDeathEvent
import org.bukkit.event.player.PlayerAdvancementDoneEvent
import org.bukkit.event.player.PlayerChangedWorldEvent
import org.bukkit.event.player.PlayerMoveEvent
import org.bukkit.event.player.PlayerStatisticIncrementEvent

class ChallengeProgressListener(
    private val challengeProgressService: ChallengeProgressService,
) : Listener {
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onStatistic(event: PlayerStatisticIncrementEvent) {
        challengeProgressService.recordStatistic(
            event.player,
            event.statistic,
            event.newValue,
            material = runCatching { event.material }.getOrNull(),
            entityType = runCatching { event.entityType }.getOrNull(),
        )
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onBlockBreak(event: BlockBreakEvent) {
        challengeProgressService.recordBlockBreak(event.player, event.block.type)
    }

    @EventHandler(priority = EventPriority.MONITOR)
    fun onEntityDeath(event: EntityDeathEvent) {
        val killer = event.entity.killer ?: return
        if (event.entity is Monster || event.entity.type.isAlive) {
            challengeProgressService.recordKill(killer, event.entityType)
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    fun onChangedWorld(event: PlayerChangedWorldEvent) {
        challengeProgressService.recordWorld(event.player, event.player.world)
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onMove(event: PlayerMoveEvent) {
        val to = event.to ?: return
        val from = event.from
        if (from.blockX == to.blockX && from.blockY == to.blockY && from.blockZ == to.blockZ && from.world == to.world) {
            return
        }
        challengeProgressService.recordMoveSample(event.player, to)
    }

    @EventHandler(priority = EventPriority.MONITOR)
    fun onAdvancement(event: PlayerAdvancementDoneEvent) {
        challengeProgressService.recordAdvancement(event)
    }
}
