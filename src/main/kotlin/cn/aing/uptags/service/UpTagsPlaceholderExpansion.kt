package cn.aing.uptags.service

import cn.aing.uptags.Support
import cn.aing.uptags.UpTagsPlugin
import me.clip.placeholderapi.expansion.PlaceholderExpansion
import org.bukkit.OfflinePlayer
import org.bukkit.entity.Player

class UpTagsPlaceholderExpansion(
    private val plugin: UpTagsPlugin,
    private val tagService: TagService,
) : PlaceholderExpansion() {
    override fun getIdentifier(): String = "tags"

    override fun getAuthor(): String = "Codex"

    override fun getVersion(): String = plugin.description.version

    override fun persist(): Boolean = true

    override fun onRequest(offlinePlayer: OfflinePlayer, params: String): String {
        val player = offlinePlayer as? Player ?: return ""
        return when {
            params.equals("current", true) -> tagService.currentTagDisplay(player)
            params.equals("current_id", true) -> tagService.currentTagId(player)
            params.equals("collected", true) -> tagService.collectedCount(player).toString()
            params.equals("total", true) -> tagService.totalCount().toString()
            params.equals("progress", true) -> tagService.progress(player)
            params.equals("active_buffs", true) -> tagService.activeBuffsDisplay(player)
            params.equals("active_particle", true) -> tagService.particleDisplay(player)
            params.equals("active_particle_id", true) -> tagService.particleId(player)
            params.equals("points", true) -> Support.formatDouble(tagService.points(player))
            params.equals("title_coin", true) -> Support.formatDouble(tagService.titleCoins(player))
            params.equals("can_upgrade", true) -> if (tagService.canUpgrade(player)) "是" else "否"
            params.startsWith("tag_owned_") -> if (tagService.isOwned(player, params.removePrefix("tag_owned_"))) "已拥有" else "未拥有"
            params.startsWith("tag_buff_count_") -> tagService.tagBuffCount(player, params.removePrefix("tag_buff_count_")).toString()
            params.startsWith("tag_first_buff_") -> tagService.tagFirstBuff(player, params.removePrefix("tag_first_buff_"))
            params.startsWith("tag_buffs_") -> tagService.tagBuffsDisplay(player, params.removePrefix("tag_buffs_"))
            params.startsWith("tag_particle_count_") -> tagService.tagParticleCount(player, params.removePrefix("tag_particle_count_")).toString()
            params.startsWith("tag_particle_") -> tagService.tagSelectedParticle(player, params.removePrefix("tag_particle_"))
            params.startsWith("tag_particles_") -> tagService.tagParticlesDisplay(player, params.removePrefix("tag_particles_"))
            params.startsWith("track_") -> tagService.trackLevel(player, params.removePrefix("track_"))
            params.startsWith("group_level_") -> tagService.groupLevel(player, params.removePrefix("group_level_"))
            params.startsWith("group_name_") -> tagService.groupName(params.removePrefix("group_name_"))
            else -> ""
        }
    }
}
