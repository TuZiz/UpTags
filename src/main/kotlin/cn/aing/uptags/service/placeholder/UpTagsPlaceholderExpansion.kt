package cn.aing.uptags.service.placeholder

import cn.aing.uptags.service.tag.TagService

import cn.aing.uptags.Support
import cn.aing.uptags.UpTagsPlugin
import me.clip.placeholderapi.expansion.PlaceholderExpansion
import org.bukkit.OfflinePlayer
import org.bukkit.entity.Player
import java.util.UUID

class UpTagsPlaceholderExpansion(
    private val plugin: UpTagsPlugin,
    private val tagService: TagService,
) : PlaceholderExpansion() {
    override fun getIdentifier(): String = "tags"

    override fun getAuthor(): String = "Codex"

    override fun getVersion(): String = plugin.description.version

    override fun persist(): Boolean = true

    override fun onRequest(offlinePlayer: OfflinePlayer, params: String): String {
        val uniqueId = offlinePlayer.uniqueId ?: return ""
        if (!tagService.isDataLoaded(uniqueId)) {
            return if (offlinePlayer.player != null) "加载中" else ""
        }
        val player = offlinePlayer.player
        return when {
            params.equals("current", true) -> resolveCurrent(uniqueId, player)
            params.equals("current_id", true) -> resolveCurrentId(uniqueId, player)
            params.equals("collected", true) -> resolveCollected(uniqueId, player).toString()
            params.equals("total", true) -> tagService.totalCount().toString()
            params.equals("progress", true) -> resolveProgress(uniqueId, player)
            params.equals("active_buffs", true) -> resolveActiveBuffs(uniqueId, player)
            params.equals("active_particle", true) -> resolveActiveParticle(uniqueId, player)
            params.equals("active_particle_id", true) -> resolveActiveParticleId(uniqueId, player)
            params.equals("points", true) -> player?.let { Support.formatDouble(tagService.points(it)) } ?: ""
            params.equals("title_coin", true) -> Support.formatDouble(resolveTitleCoins(uniqueId, player))
            params.equals("can_upgrade", true) -> if (resolveCanUpgrade(uniqueId, player)) "是" else "否"
            params.startsWith("tag_owned_") -> if (resolveOwned(uniqueId, player, params.removePrefix("tag_owned_"))) "已拥有" else "未拥有"
            params.startsWith("tag_buff_count_") -> resolveTagBuffCount(uniqueId, player, params.removePrefix("tag_buff_count_")).toString()
            params.startsWith("tag_first_buff_") -> resolveTagFirstBuff(uniqueId, player, params.removePrefix("tag_first_buff_"))
            params.startsWith("tag_buffs_") -> resolveTagBuffs(uniqueId, player, params.removePrefix("tag_buffs_"))
            params.startsWith("tag_particle_count_") -> resolveTagParticleCount(uniqueId, player, params.removePrefix("tag_particle_count_")).toString()
            params.startsWith("tag_particle_") -> resolveTagParticle(uniqueId, player, params.removePrefix("tag_particle_"))
            params.startsWith("tag_particles_") -> resolveTagParticles(uniqueId, player, params.removePrefix("tag_particles_"))
            params.startsWith("track_") -> resolveTrackLevel(uniqueId, player, params.removePrefix("track_"))
            params.startsWith("group_level_") -> resolveGroupLevel(uniqueId, player, params.removePrefix("group_level_"))
            params.startsWith("group_name_") -> tagService.groupName(params.removePrefix("group_name_"))
            else -> ""
        }
    }

    private fun resolveCurrent(uniqueId: UUID, player: Player?): String =
        player?.let { tagService.currentTagDisplay(it) } ?: tagService.currentTagDisplay(uniqueId)

    private fun resolveCurrentId(uniqueId: UUID, player: Player?): String =
        player?.let { tagService.currentTagId(it) } ?: tagService.currentTagId(uniqueId)

    private fun resolveCollected(uniqueId: UUID, player: Player?): Int =
        player?.let { tagService.collectedCount(it) } ?: tagService.collectedCount(uniqueId)

    private fun resolveProgress(uniqueId: UUID, player: Player?): String =
        player?.let { tagService.progress(it) } ?: tagService.progress(uniqueId)

    private fun resolveActiveBuffs(uniqueId: UUID, player: Player?): String =
        player?.let { tagService.activeBuffsDisplay(it) } ?: tagService.activeBuffsDisplay(uniqueId)

    private fun resolveActiveParticle(uniqueId: UUID, player: Player?): String =
        player?.let { tagService.particleDisplay(it) } ?: tagService.particleDisplay(uniqueId)

    private fun resolveActiveParticleId(uniqueId: UUID, player: Player?): String =
        player?.let { tagService.particleId(it) } ?: tagService.particleId(uniqueId)

    private fun resolveTitleCoins(uniqueId: UUID, player: Player?): Double =
        player?.let { tagService.titleCoins(it) } ?: tagService.titleCoins(uniqueId)

    private fun resolveCanUpgrade(uniqueId: UUID, player: Player?): Boolean =
        player?.let { tagService.canUpgrade(it) } ?: tagService.canUpgrade(uniqueId)

    private fun resolveOwned(uniqueId: UUID, player: Player?, tagId: String): Boolean =
        player?.let { tagService.isOwned(it, tagId) } ?: tagService.isOwned(uniqueId, tagId)

    private fun resolveTagBuffCount(uniqueId: UUID, player: Player?, tagId: String): Int =
        player?.let { tagService.tagBuffCount(it, tagId) } ?: tagService.tagBuffCount(uniqueId, tagId)

    private fun resolveTagFirstBuff(uniqueId: UUID, player: Player?, tagId: String): String =
        player?.let { tagService.tagFirstBuff(it, tagId) } ?: tagService.tagFirstBuff(uniqueId, tagId)

    private fun resolveTagBuffs(uniqueId: UUID, player: Player?, tagId: String): String =
        player?.let { tagService.tagBuffsDisplay(it, tagId) } ?: tagService.tagBuffsDisplay(uniqueId, tagId)

    private fun resolveTagParticleCount(uniqueId: UUID, player: Player?, tagId: String): Int =
        player?.let { tagService.tagParticleCount(it, tagId) } ?: tagService.tagParticleCount(uniqueId, tagId)

    private fun resolveTagParticle(uniqueId: UUID, player: Player?, tagId: String): String =
        player?.let { tagService.tagSelectedParticle(it, tagId) } ?: tagService.tagSelectedParticle(uniqueId, tagId)

    private fun resolveTagParticles(uniqueId: UUID, player: Player?, tagId: String): String =
        player?.let { tagService.tagParticlesDisplay(it, tagId) } ?: tagService.tagParticlesDisplay(uniqueId, tagId)

    private fun resolveTrackLevel(uniqueId: UUID, player: Player?, trackId: String): String =
        player?.let { tagService.trackLevel(it, trackId) } ?: tagService.trackLevel(uniqueId, trackId)

    private fun resolveGroupLevel(uniqueId: UUID, player: Player?, groupId: String): String =
        player?.let { tagService.groupLevel(it, groupId) } ?: tagService.groupLevel(uniqueId, groupId)
}
