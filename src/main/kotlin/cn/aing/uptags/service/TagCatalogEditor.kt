package cn.aing.uptags.service

import cn.aing.uptags.config.ConfigRegistry
import cn.aing.uptags.model.config.TagDefinition
import cn.aing.uptags.model.runtime.PlayerTagData
import cn.aing.uptags.repository.PlayerDataRepository
import org.bukkit.Bukkit
import org.bukkit.entity.Player
import java.util.Locale

internal class TagCatalogEditor(
    private val config: ConfigRegistry,
    private val repository: PlayerDataRepository,
    private val enforceDefaultTag: (Player, PlayerTagData) -> Unit,
) {
    fun updateDisplay(tagId: String, display: String): Boolean {
        val definition = config.tags[tagId] ?: return false
        definition.display = display
        config.saveTags()
        return true
    }

    fun updateRarity(tagId: String, rarity: String): Boolean {
        val definition = config.tags[tagId] ?: return false
        val normalized = rarity.uppercase(Locale.ROOT)
        if (normalized !in config.allRarities()) return false
        definition.rarity = normalized
        definition.upgradeGroups = config.defaultGroupsForRarity(normalized).toMutableList()
        config.saveTags()
        return true
    }

    fun updateGroups(tagId: String, groups: List<String>): Boolean {
        val definition = config.tags[tagId] ?: return false
        val valid = groups.map { it.trim() }.filter { it.isNotEmpty() && config.upgradeGroups.containsKey(it) }.distinct()
        if (valid.isEmpty()) return false
        definition.upgradeGroups = valid.toMutableList()
        config.saveTags()
        return true
    }

    fun updateDefaultUnlocked(tagId: String, value: Boolean): Boolean {
        val definition = config.tags[tagId] ?: return false
        definition.defaultUnlocked = value
        config.saveTags()
        return true
    }

    fun create(id: String): Boolean {
        if (id.isBlank() || config.tags.containsKey(id)) return false
        config.createTag(id)
        return true
    }

    fun createQuick(tagId: String, permission: String, buffGroup: String, particleGroup: String): Boolean {
        val normalizedId = tagId.trim()
        val normalizedPermission = permission.trim()
        if (normalizedId.isBlank() || normalizedPermission.isBlank()) return false
        if (config.tags.containsKey(normalizedId)) return false
        if (!config.hasUpgradeGroup(buffGroup) || !config.hasUpgradeGroup(particleGroup)) return false

        val created: TagDefinition = config.createTag(normalizedId)
        created.permission = normalizedPermission
        created.display = "&#FFFFFF[&#AAAAAA$normalizedId&#FFFFFF]"
        created.upgradeGroups = linkedSetOf(buffGroup, particleGroup).toMutableList()
        config.saveTags()
        return true
    }

    fun delete(tagId: String): Boolean {
        if (!config.tags.containsKey(tagId)) return false
        config.deleteTag(tagId)
        for (online in Bukkit.getOnlinePlayers()) {
            val data = repository.get(online.uniqueId)
            data.ownedTags.remove(tagId)
            data.tagProgress.remove(tagId)
            if (data.equippedTagId == tagId) {
                data.equippedTagId = null
                enforceDefaultTag(online, data)
            }
            repository.saveAsync(data)
        }
        return true
    }
}
