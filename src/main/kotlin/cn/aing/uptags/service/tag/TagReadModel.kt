package cn.aing.uptags.service.tag

import cn.aing.uptags.Support
import cn.aing.uptags.config.ConfigRegistry
import cn.aing.uptags.model.config.BuffDefinition
import cn.aing.uptags.model.config.ParticleDefinition
import cn.aing.uptags.model.config.TagDefinition
import cn.aing.uptags.model.runtime.CustomTitleData
import cn.aing.uptags.model.runtime.PlayerTagData
import cn.aing.uptags.model.runtime.TitleEntry
import cn.aing.uptags.model.runtime.TitleKind
import cn.aing.uptags.repository.PlayerDataRepository
import org.bukkit.entity.Player
import java.util.LinkedHashSet
import java.util.UUID

internal class TagReadModel(
    private val config: ConfigRegistry,
    private val repository: PlayerDataRepository,
    private val preparePlayer: (Player, Boolean) -> Unit,
) {
    fun currentTagId(player: Player): String = equippedTitleId(player) ?: ""

    fun currentTagId(uniqueId: UUID): String = equippedTitleId(uniqueId) ?: ""

    fun currentTagDisplay(player: Player): String {
        equippedCustomTitle(player)?.let { return renderCustomTitle(it) }
        return equippedTag(player)?.let { renderedTagDisplay(player, it.id) } ?: "无"
    }

    fun currentTagDisplay(uniqueId: UUID): String {
        equippedCustomTitle(uniqueId)?.let { return renderCustomTitle(it) }
        return equippedTag(uniqueId)?.let { Support.color(it.display) } ?: "无"
    }

    fun renderedTagDisplay(player: Player, tagId: String): String {
        val tag = config.tags[tagId] ?: return tagId
        return Support.color(tag.display)
    }

    fun renderCustomTitle(customTitle: CustomTitleData): String {
        val colors = if (customTitle.manualColors.isNotEmpty()) {
            customTitle.manualColors
        } else {
            customTitle.randomSchemes.getOrNull(customTitle.selectedSchemeIndex).orEmpty()
        }
        if (customTitle.rawText.isBlank()) {
            return "无"
        }
        return Support.renderPaletteText(Support.decorateCustomTitle(customTitle.rawText), colors)
    }

    fun resolveTag(input: String?): TagDefinition? {
        if (input.isNullOrBlank()) {
            return null
        }
        config.tags[input]?.let { return it }
        val normalized = Support.stripColor(input).trim()
        return config.tags.values.firstOrNull { Support.stripColor(it.display).equals(normalized, ignoreCase = true) }
    }

    fun resolveTitleId(player: Player, input: String?): String? {
        if (input.isNullOrBlank()) {
            return null
        }
        config.tags[input]?.let { return it.id }
        data(player).customTitles[input]?.let { return it.id }
        val normalized = Support.stripColor(input).trim()
        config.tags.values.firstOrNull { Support.stripColor(it.display).equals(normalized, ignoreCase = true) }?.let { return it.id }
        return data(player).customTitles.values.firstOrNull { custom ->
            custom.id.equals(normalized, ignoreCase = true) ||
                custom.rawText.equals(normalized, ignoreCase = true) ||
                Support.stripColor(renderCustomTitle(custom)).equals(normalized, ignoreCase = true)
        }?.id
    }

    fun titleExists(player: Player, titleId: String): Boolean = titleId in config.tags || titleId in data(player).customTitles

    fun tagName(tagId: String): String = config.tags[tagId]?.let { Support.stripColor(it.display) } ?: tagId

    fun titleName(player: Player, titleId: String): String {
        return data(player).customTitles[titleId]?.let { Support.stripColor(renderCustomTitle(it)) } ?: tagName(titleId)
    }

    fun titleName(uniqueId: UUID, titleId: String): String {
        return data(uniqueId).customTitles[titleId]?.let { Support.stripColor(renderCustomTitle(it)) } ?: tagName(titleId)
    }

    fun visibleTags(player: Player): List<TagDefinition> {
        preparePlayer(player, false)
        return config.tags.values.toList()
    }

    fun visibleTitles(player: Player): List<TitleEntry> {
        preparePlayer(player, false)
        return visibleTitles(data(player)) { tag -> renderedTagDisplay(player, tag.id) }
    }

    fun visibleTitles(uniqueId: UUID): List<TitleEntry> {
        return visibleTitles(data(uniqueId)) { tag -> Support.color(tag.display) }
    }

    fun buffLevel(player: Player, tagId: String, buffId: String): Int {
        return data(player).tagProgress[tagId]?.buffLevels?.getOrDefault(buffId, 0) ?: 0
    }

    fun buffLevel(uniqueId: UUID, tagId: String, buffId: String): Int {
        return data(uniqueId).tagProgress[tagId]?.buffLevels?.getOrDefault(buffId, 0) ?: 0
    }

    fun isBuffEnabled(player: Player, tagId: String, buffId: String): Boolean {
        val progress = data(player).tagProgress[tagId] ?: return false
        return buffId in progress.activeBuffs && progress.buffLevels.getOrDefault(buffId, 0) > 0
    }

    fun isBuffEnabled(uniqueId: UUID, tagId: String, buffId: String): Boolean {
        val progress = data(uniqueId).tagProgress[tagId] ?: return false
        return buffId in progress.activeBuffs && progress.buffLevels.getOrDefault(buffId, 0) > 0
    }

    fun isParticleOwned(player: Player, tagId: String, particleId: String): Boolean {
        return particleId in (data(player).tagProgress[tagId]?.ownedParticles ?: emptySet())
    }

    fun isParticleOwned(uniqueId: UUID, tagId: String, particleId: String): Boolean {
        return particleId in (data(uniqueId).tagProgress[tagId]?.ownedParticles ?: emptySet())
    }

    fun isParticleSelected(player: Player, tagId: String, particleId: String): Boolean {
        return data(player).tagProgress[tagId]?.selectedParticleId == particleId
    }

    fun isParticleSelected(uniqueId: UUID, tagId: String, particleId: String): Boolean {
        return data(uniqueId).tagProgress[tagId]?.selectedParticleId == particleId
    }

    fun allowedBuffIds(tag: TagDefinition): List<String> = allowedBuffIds(effectiveGroups(tag))

    fun allowedParticleIds(tag: TagDefinition): List<String> = allowedParticleIds(effectiveGroups(tag))

    fun allowedBuffIds(player: Player, titleId: String): List<String> = allowedBuffIds(titleGroups(player, titleId))

    fun allowedParticleIds(player: Player, titleId: String): List<String> = allowedParticleIds(titleGroups(player, titleId))

    fun allowedBuffIds(uniqueId: UUID, titleId: String): List<String> = allowedBuffIds(titleGroups(data(uniqueId), titleId))

    fun allowedParticleIds(uniqueId: UUID, titleId: String): List<String> = allowedParticleIds(titleGroups(data(uniqueId), titleId))

    fun activeBuffs(player: Player): Collection<BuffDefinition> {
        val titleId = equippedTitleId(player) ?: return emptyList()
        val progress = data(player).tagProgress[titleId] ?: return emptyList()
        return progress.activeBuffs.mapNotNull { buffId ->
            val level = progress.buffLevels.getOrDefault(buffId, 0)
            if (level <= 0) null else config.buffs[buffId]
        }
    }

    fun activeBuffLevel(player: Player, buffId: String): Int {
        val titleId = equippedTitleId(player) ?: return 0
        val progress = data(player).tagProgress[titleId] ?: return 0
        return if (buffId in progress.activeBuffs) progress.buffLevels.getOrDefault(buffId, 0) else 0
    }

    fun selectedParticle(player: Player): ParticleDefinition? {
        val titleId = equippedTitleId(player) ?: return null
        val progress = data(player).tagProgress[titleId] ?: return null
        return progress.selectedParticleId?.let { config.particles[it] }
    }

    fun activeBuffsDisplay(player: Player): String {
        val titleId = equippedTitleId(player) ?: return "无"
        val progress = data(player).tagProgress[titleId] ?: return "无"
        val values = progress.activeBuffs.mapNotNull { buffId ->
            val level = progress.buffLevels.getOrDefault(buffId, 0)
            val buff = config.buffs[buffId] ?: return@mapNotNull null
            if (level <= 0) null else Support.color(buff.display) + " " + Support.roman(level)
        }
        return Support.joinDisplay(values)
    }

    fun activeBuffsDisplay(uniqueId: UUID): String {
        val titleId = equippedTitleId(uniqueId) ?: return "无"
        val progress = data(uniqueId).tagProgress[titleId] ?: return "无"
        val values = progress.activeBuffs.mapNotNull { buffId ->
            val level = progress.buffLevels.getOrDefault(buffId, 0)
            val buff = config.buffs[buffId] ?: return@mapNotNull null
            if (level <= 0) null else Support.color(buff.display) + " " + Support.roman(level)
        }
        return Support.joinDisplay(values)
    }

    fun particleDisplay(player: Player): String = selectedParticle(player)?.let { Support.color(it.display) } ?: "无"

    fun particleDisplay(uniqueId: UUID): String {
        val titleId = equippedTitleId(uniqueId) ?: return "无"
        val selectedId = data(uniqueId).tagProgress[titleId]?.selectedParticleId ?: return "无"
        return config.particles[selectedId]?.let { Support.color(it.display) } ?: "无"
    }

    fun particleId(player: Player): String = selectedParticle(player)?.id ?: ""

    fun particleId(uniqueId: UUID): String {
        val titleId = equippedTitleId(uniqueId) ?: return ""
        return data(uniqueId).tagProgress[titleId]?.selectedParticleId ?: ""
    }

    fun collectedCount(player: Player): Int {
        preparePlayer(player, false)
        return data(player).ownedTags.size + data(player).customTitles.size
    }

    fun collectedCount(uniqueId: UUID): Int = data(uniqueId).ownedTags.size + data(uniqueId).customTitles.size

    fun totalCount(): Int = config.tags.size

    fun progress(player: Player): String {
        val total = maxOf(1, totalCount() + data(player).customTitles.size)
        return Support.formatDouble(collectedCount(player) * 100.0 / total)
    }

    fun progress(uniqueId: UUID): String {
        val total = maxOf(1, totalCount() + data(uniqueId).customTitles.size)
        return Support.formatDouble(collectedCount(uniqueId) * 100.0 / total)
    }

    fun tagBuffsDisplay(player: Player, tagId: String): String {
        val progress = data(player).tagProgress[tagId] ?: return "无"
        val values = progress.buffLevels.entries.mapNotNull { (buffId, level) ->
            if (level <= 0) return@mapNotNull null
            val buff = config.buffs[buffId] ?: return@mapNotNull null
            Support.color(buff.display) + " " + Support.roman(level)
        }
        return Support.joinDisplay(values)
    }

    fun tagBuffsDisplay(uniqueId: UUID, tagId: String): String {
        val progress = data(uniqueId).tagProgress[tagId] ?: return "无"
        val values = progress.buffLevels.entries.mapNotNull { (buffId, level) ->
            if (level <= 0) return@mapNotNull null
            val buff = config.buffs[buffId] ?: return@mapNotNull null
            Support.color(buff.display) + " " + Support.roman(level)
        }
        return Support.joinDisplay(values)
    }

    fun tagFirstBuff(player: Player, tagId: String): String {
        val progress = data(player).tagProgress[tagId] ?: return "无"
        for ((buffId, level) in progress.buffLevels) {
            if (level <= 0) continue
            val buff = config.buffs[buffId] ?: continue
            return Support.color(buff.display) + " " + Support.roman(level)
        }
        return "无"
    }

    fun tagFirstBuff(uniqueId: UUID, tagId: String): String {
        val progress = data(uniqueId).tagProgress[tagId] ?: return "无"
        for ((buffId, level) in progress.buffLevels) {
            if (level <= 0) continue
            val buff = config.buffs[buffId] ?: continue
            return Support.color(buff.display) + " " + Support.roman(level)
        }
        return "无"
    }

    fun tagBuffCount(player: Player, tagId: String): Int = data(player).tagProgress[tagId]?.buffLevels?.values?.count { it > 0 } ?: 0

    fun tagBuffCount(uniqueId: UUID, tagId: String): Int = data(uniqueId).tagProgress[tagId]?.buffLevels?.values?.count { it > 0 } ?: 0

    fun tagParticlesDisplay(player: Player, tagId: String): String {
        val progress = data(player).tagProgress[tagId] ?: return "无"
        val values = progress.ownedParticles.mapNotNull { id -> config.particles[id]?.let { Support.color(it.display) } }
        return Support.joinDisplay(values)
    }

    fun tagParticlesDisplay(uniqueId: UUID, tagId: String): String {
        val progress = data(uniqueId).tagProgress[tagId] ?: return "无"
        val values = progress.ownedParticles.mapNotNull { id -> config.particles[id]?.let { Support.color(it.display) } }
        return Support.joinDisplay(values)
    }

    fun tagParticleCount(player: Player, tagId: String): Int = data(player).tagProgress[tagId]?.ownedParticles?.size ?: 0

    fun tagParticleCount(uniqueId: UUID, tagId: String): Int = data(uniqueId).tagProgress[tagId]?.ownedParticles?.size ?: 0

    fun tagSelectedParticle(player: Player, tagId: String): String {
        val id = data(player).tagProgress[tagId]?.selectedParticleId ?: return "无"
        return config.particles[id]?.let { Support.color(it.display) } ?: "无"
    }

    fun tagSelectedParticle(uniqueId: UUID, tagId: String): String {
        val id = data(uniqueId).tagProgress[tagId]?.selectedParticleId ?: return "无"
        return config.particles[id]?.let { Support.color(it.display) } ?: "无"
    }

    fun groupLevel(player: Player, groupId: String): String {
        val titleId = equippedTitleId(player) ?: return "0"
        if (groupId !in titleGroups(player, titleId)) return "0"
        val progress = data(player).tagProgress[titleId] ?: return "0"
        val group = config.upgradeGroups[groupId] ?: return "0"
        var score = 0
        group.buffs.forEach { score += progress.buffLevels.getOrDefault(it, 0) }
        group.particles.forEach { if (it in progress.ownedParticles) score++ }
        return score.toString()
    }

    fun groupLevel(uniqueId: UUID, groupId: String): String {
        val titleId = equippedTitleId(uniqueId) ?: return "0"
        val title = config.tags[titleId]
        val groups = when {
            title != null -> title.upgradeGroups
            titleId in data(uniqueId).customTitles -> data(uniqueId).customTitles[titleId]?.groupId?.let(::listOf).orEmpty()
            else -> emptyList()
        }
        if (groupId !in groups) return "0"
        val progress = data(uniqueId).tagProgress[titleId] ?: return "0"
        val group = config.upgradeGroups[groupId] ?: return "0"
        var score = 0
        group.buffs.forEach { score += progress.buffLevels.getOrDefault(it, 0) }
        group.particles.forEach { if (it in progress.ownedParticles) score++ }
        return score.toString()
    }

    fun groupName(groupId: String): String = config.upgradeGroups[groupId]?.let { Support.color(it.name) } ?: ""

    fun trackLevel(player: Player, trackId: String): String = groupLevel(player, trackId)

    fun trackLevel(uniqueId: UUID, trackId: String): String = groupLevel(uniqueId, trackId)

    fun canUpgrade(player: Player): Boolean {
        val titleId = equippedTitleId(player) ?: return false
        val progress = data(player).tagProgress[titleId]
        val buffIds = allowedBuffIds(player, titleId)
        val particleIds = allowedParticleIds(player, titleId)
        if (progress == null) {
            return buffIds.isNotEmpty() || particleIds.isNotEmpty()
        }
        if (buffIds.any { buffId ->
                val buff = config.buffs[buffId] ?: return@any false
                progress.buffLevels.getOrDefault(buffId, 0) < buff.maxLevel
            }
        ) return true
        return particleIds.any { it !in progress.ownedParticles }
    }

    fun canUpgrade(uniqueId: UUID): Boolean {
        val titleId = equippedTitleId(uniqueId) ?: return false
        val title = config.tags[titleId]
        val buffIds = when {
            title != null -> allowedBuffIds(title)
            titleId in data(uniqueId).customTitles -> {
                val groupId = data(uniqueId).customTitles[titleId]?.groupId
                if (groupId == null) emptySet() else config.upgradeGroups[groupId]?.buffs ?: emptySet()
            }
            else -> emptySet()
        }
        val particleIds = when {
            title != null -> allowedParticleIds(title)
            titleId in data(uniqueId).customTitles -> {
                val groupId = data(uniqueId).customTitles[titleId]?.groupId
                if (groupId == null) emptySet() else config.upgradeGroups[groupId]?.particles ?: emptySet()
            }
            else -> emptySet()
        }
        val progress = data(uniqueId).tagProgress[titleId]
        if (progress == null) {
            return buffIds.isNotEmpty() || particleIds.isNotEmpty()
        }
        if (buffIds.any { buffId ->
                val buff = config.buffs[buffId] ?: return@any false
                progress.buffLevels.getOrDefault(buffId, 0) < buff.maxLevel
            }
        ) return true
        return particleIds.any { it !in progress.ownedParticles }
    }

    fun rarityDisplay(rarity: String): String = Support.color(config.rarityDisplay(rarity))

    private fun visibleTitles(
        data: PlayerTagData,
        tagDisplay: (TagDefinition) -> String,
    ): List<TitleEntry> {
        val entries = ArrayList<TitleEntry>(config.tags.size + data.customTitles.size)
        config.tags.values.forEach { tag ->
            entries += TitleEntry(
                id = tag.id,
                display = tagDisplay(tag),
                description = tag.description,
                rarityDisplay = rarityDisplay(tag.rarity),
                owned = tag.id in data.ownedTags,
                kind = TitleKind.TAG,
            )
        }
        data.customTitles.values.sortedBy { it.createdAt }.forEach { custom ->
            val groupName = custom.groupId?.let { config.upgradeGroups[it]?.display }?.let(Support::color) ?: Support.color("&#94A3B8未绑定")
            entries += TitleEntry(
                id = custom.id,
                display = renderCustomTitle(custom),
                description = listOf(
                    "&#E2E8F0玩家自定义称号",
                    "&#E2E8F0原始文本: &#F8FAFC${custom.rawText}",
                    "&#E2E8F0升级组: $groupName",
                ),
                rarityDisplay = Support.color("&#FF8FD8自定义"),
                owned = true,
                kind = TitleKind.CUSTOM,
            )
        }
        return entries
    }

    private fun data(uniqueId: UUID): PlayerTagData = repository.get(uniqueId)

    private fun data(player: Player): PlayerTagData = repository.get(player.uniqueId)

    private fun equippedTitleId(player: Player): String? = data(player).equippedCustomTitleId ?: data(player).equippedTagId

    private fun equippedTitleId(uniqueId: UUID): String? = data(uniqueId).equippedCustomTitleId ?: data(uniqueId).equippedTagId

    private fun equippedTag(player: Player): TagDefinition? {
        val id = data(player).equippedTagId ?: return null
        return config.tags[id]
    }

    private fun equippedTag(uniqueId: UUID): TagDefinition? {
        val id = data(uniqueId).equippedTagId ?: return null
        return config.tags[id]
    }

    private fun equippedCustomTitle(player: Player): CustomTitleData? {
        val id = data(player).equippedCustomTitleId ?: return null
        return data(player).customTitles[id]
    }

    private fun equippedCustomTitle(uniqueId: UUID): CustomTitleData? {
        val id = data(uniqueId).equippedCustomTitleId ?: return null
        return data(uniqueId).customTitles[id]
    }

    private fun allowedBuffIds(groups: List<String>): List<String> {
        val ids = LinkedHashSet<String>()
        for (groupId in groups) {
            config.upgradeGroups[groupId]?.let { ids += it.buffs }
        }
        return ids.toList()
    }

    private fun allowedParticleIds(groups: List<String>): List<String> {
        val ids = LinkedHashSet<String>()
        for (groupId in groups) {
            config.upgradeGroups[groupId]?.let { ids += it.particles }
        }
        return ids.toList()
    }

    private fun effectiveGroups(tag: TagDefinition): List<String> {
        return if (tag.upgradeGroups.isNotEmpty()) tag.upgradeGroups else config.defaultGroupsForRarity(tag.rarity)
    }

    private fun titleGroups(player: Player, titleId: String): List<String> {
        config.tags[titleId]?.let { return effectiveGroups(it) }
        return data(player).customTitles[titleId]?.groupId?.takeIf(config::hasUpgradeGroup)?.let(::listOf).orEmpty()
    }

    private fun titleGroups(data: PlayerTagData, titleId: String): List<String> {
        config.tags[titleId]?.let { return effectiveGroups(it) }
        return data.customTitles[titleId]?.groupId?.takeIf(config::hasUpgradeGroup)?.let(::listOf).orEmpty()
    }
}
