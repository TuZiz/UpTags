package cn.aing.uptags.service

import cn.aing.uptags.Support
import cn.aing.uptags.config.ConfigRegistry
import cn.aing.uptags.model.config.TagDefinition
import cn.aing.uptags.model.runtime.PlayerTagData
import cn.aing.uptags.model.runtime.ScrollKind
import cn.aing.uptags.model.runtime.TagProgress
import cn.aing.uptags.repository.PlayerDataRepository
import org.bukkit.entity.Player
import org.bukkit.inventory.ItemStack
import java.util.LinkedHashSet
import java.util.UUID

internal class TagAdminActions(
    private val config: ConfigRegistry,
    private val repository: PlayerDataRepository,
    private val createDetachScroll: (ScrollKind, String, Int) -> ItemStack?,
    private val hasInventorySpace: (Player, ItemStack) -> Boolean,
) {
    fun equipTitle(uniqueId: UUID, titleId: String): AdminActionResult {
        val data = data(uniqueId)
        val resolved = resolveKnownTitleId(data, titleId) ?: return failure("admin-invalid-title", titleId)
        if (!isOwnedTitle(data, resolved)) {
            return failure("not-owned")
        }
        ensureProgress(resolved, data)
        if (resolved in data.customTitles) {
            data.equippedCustomTitleId = resolved
            data.equippedTagId = null
        } else {
            data.equippedTagId = resolved
            data.equippedCustomTitleId = null
        }
        repository.saveAsync(data)
        return success("equip $resolved")
    }

    fun unequipTitle(uniqueId: UUID): AdminActionResult {
        val data = data(uniqueId)
        data.equippedCustomTitleId = null
        if (config.settings.forceDefaultTag) {
            val forcedTag = config.tags[config.settings.forcedTagId]
            if (forcedTag != null) {
                data.ownedTags += forcedTag.id
                ensureProgress(forcedTag, data)
                data.equippedTagId = forcedTag.id
            } else {
                data.equippedTagId = null
            }
        } else {
            data.equippedTagId = null
        }
        repository.saveAsync(data)
        return success("unequip")
    }

    fun setBuffLevel(uniqueId: UUID, titleId: String, buffId: String, level: Int): AdminActionResult {
        val data = data(uniqueId)
        val resolved = resolveKnownTitleId(data, titleId) ?: return failure("admin-invalid-title", titleId)
        if (!isOwnedTitle(data, resolved)) {
            return failure("not-owned")
        }
        val buff = config.buffs[buffId] ?: return failure("admin-invalid-buff", buffId)
        if (buffId !in allowedBuffIds(titleGroups(data, resolved))) {
            return failure("condition-failed")
        }
        if (level < 0 || level > buff.maxLevel) {
            return failure("admin-invalid-level", 0, buff.maxLevel)
        }
        val progress = ensureProgress(resolved, data)
        if (level <= 0) {
            progress.buffLevels.remove(buffId)
            progress.activeBuffs.remove(buffId)
        } else {
            progress.buffLevels[buffId] = level
            progress.activeBuffs += buffId
        }
        repository.saveAsync(data)
        return success("buff $buffId = $level")
    }

    fun setBuffEnabled(uniqueId: UUID, titleId: String, buffId: String, enabled: Boolean): AdminActionResult {
        val data = data(uniqueId)
        val resolved = resolveKnownTitleId(data, titleId) ?: return failure("admin-invalid-title", titleId)
        if (!isOwnedTitle(data, resolved)) {
            return failure("not-owned")
        }
        if (!config.buffs.containsKey(buffId)) {
            return failure("admin-invalid-buff", buffId)
        }
        val progress = data.tagProgress[resolved] ?: return failure("admin-no-effect")
        if (progress.buffLevels.getOrDefault(buffId, 0) <= 0) {
            return failure("admin-no-effect")
        }
        if (enabled) {
            progress.activeBuffs += buffId
        } else {
            progress.activeBuffs.remove(buffId)
        }
        repository.saveAsync(data)
        return success("buff $buffId ${if (enabled) "enabled" else "disabled"}")
    }

    fun detachBuff(uniqueId: UUID, titleId: String, buffId: String, receiver: Player): AdminActionResult {
        val data = data(uniqueId)
        val resolved = resolveKnownTitleId(data, titleId) ?: return failure("admin-invalid-title", titleId)
        if (!isOwnedTitle(data, resolved)) {
            return failure("not-owned")
        }
        val buff = config.buffs[buffId] ?: return failure("admin-invalid-buff", buffId)
        val progress = data.tagProgress[resolved] ?: return failure("admin-no-effect")
        val currentLevel = progress.buffLevels.getOrDefault(buffId, 0)
        if (currentLevel <= 0) {
            return failure("admin-no-effect")
        }
        val scroll = createDetachScroll(ScrollKind.BUFF, buffId, currentLevel)
            ?: return failure("admin-detach-no-scroll", buffId)
        if (!hasInventorySpace(receiver, scroll)) {
            return failure("admin-detach-inventory-full")
        }
        progress.buffLevels.remove(buffId)
        progress.activeBuffs.remove(buffId)
        receiver.inventory.addItem(scroll)
        repository.saveAsync(data)
        return success("detach buff ${Support.stripColor(buff.display)} ${Support.roman(currentLevel)}")
    }

    fun giveParticle(uniqueId: UUID, titleId: String, particleId: String): AdminActionResult {
        val data = data(uniqueId)
        val resolved = resolveKnownTitleId(data, titleId) ?: return failure("admin-invalid-title", titleId)
        if (!isOwnedTitle(data, resolved)) {
            return failure("not-owned")
        }
        if (!config.particles.containsKey(particleId)) {
            return failure("admin-invalid-particle", particleId)
        }
        if (particleId !in allowedParticleIds(titleGroups(data, resolved))) {
            return failure("condition-failed")
        }
        val progress = ensureProgress(resolved, data)
        progress.ownedParticles += particleId
        if (progress.selectedParticleId.isNullOrBlank()) {
            progress.selectedParticleId = particleId
        }
        repository.saveAsync(data)
        return success("particle $particleId")
    }

    fun takeParticle(uniqueId: UUID, titleId: String, particleId: String): AdminActionResult {
        val data = data(uniqueId)
        val resolved = resolveKnownTitleId(data, titleId) ?: return failure("admin-invalid-title", titleId)
        if (!isOwnedTitle(data, resolved)) {
            return failure("not-owned")
        }
        if (!config.particles.containsKey(particleId)) {
            return failure("admin-invalid-particle", particleId)
        }
        val progress = data.tagProgress[resolved] ?: return failure("admin-no-effect")
        if (!progress.ownedParticles.remove(particleId)) {
            return failure("admin-no-effect")
        }
        if (progress.selectedParticleId == particleId) {
            progress.selectedParticleId = null
        }
        repository.saveAsync(data)
        return success("particle $particleId removed")
    }

    fun selectParticle(uniqueId: UUID, titleId: String, particleId: String): AdminActionResult {
        val data = data(uniqueId)
        val resolved = resolveKnownTitleId(data, titleId) ?: return failure("admin-invalid-title", titleId)
        if (!isOwnedTitle(data, resolved)) {
            return failure("not-owned")
        }
        if (!config.particles.containsKey(particleId)) {
            return failure("admin-invalid-particle", particleId)
        }
        val progress = data.tagProgress[resolved] ?: return failure("admin-no-effect")
        if (particleId !in progress.ownedParticles) {
            return failure("admin-no-effect")
        }
        progress.selectedParticleId = particleId
        repository.saveAsync(data)
        return success("particle $particleId selected")
    }

    fun clearParticle(uniqueId: UUID, titleId: String): AdminActionResult {
        val data = data(uniqueId)
        val resolved = resolveKnownTitleId(data, titleId) ?: return failure("admin-invalid-title", titleId)
        if (!isOwnedTitle(data, resolved)) {
            return failure("not-owned")
        }
        val progress = data.tagProgress[resolved] ?: return failure("admin-no-effect")
        progress.selectedParticleId = null
        repository.saveAsync(data)
        return success("particle cleared")
    }

    fun detachParticle(uniqueId: UUID, titleId: String, particleId: String, receiver: Player): AdminActionResult {
        val data = data(uniqueId)
        val resolved = resolveKnownTitleId(data, titleId) ?: return failure("admin-invalid-title", titleId)
        if (!isOwnedTitle(data, resolved)) {
            return failure("not-owned")
        }
        val particle = config.particles[particleId] ?: return failure("admin-invalid-particle", particleId)
        val progress = data.tagProgress[resolved] ?: return failure("admin-no-effect")
        if (particleId !in progress.ownedParticles) {
            return failure("admin-no-effect")
        }
        val scroll = createDetachScroll(ScrollKind.PARTICLE, particleId, 1)
            ?: return failure("admin-detach-no-scroll", particleId)
        if (!hasInventorySpace(receiver, scroll)) {
            return failure("admin-detach-inventory-full")
        }
        progress.ownedParticles.remove(particleId)
        if (progress.selectedParticleId == particleId) {
            progress.selectedParticleId = null
        }
        receiver.inventory.addItem(scroll)
        repository.saveAsync(data)
        return success("detach particle ${Support.stripColor(particle.display)}")
    }

    fun equipCustomTitle(uniqueId: UUID, customId: String): AdminActionResult {
        val data = data(uniqueId)
        if (customId !in data.customTitles) {
            return failure("custom-title-not-found")
        }
        ensureProgress(customId, data)
        data.equippedCustomTitleId = customId
        data.equippedTagId = null
        repository.saveAsync(data)
        return success("custom equip $customId")
    }

    fun deleteCustomTitle(uniqueId: UUID, customId: String): AdminActionResult {
        val data = data(uniqueId)
        if (data.customTitles.remove(customId) == null) {
            return failure("custom-title-not-found")
        }
        data.tagProgress.remove(customId)
        if (data.equippedCustomTitleId == customId) {
            data.equippedCustomTitleId = null
        }
        repository.saveAsync(data)
        return success("custom delete $customId")
    }

    private fun data(uniqueId: UUID): PlayerTagData = repository.get(uniqueId)

    private fun ensureProgress(definition: TagDefinition, data: PlayerTagData): TagProgress = ensureProgress(definition.id, data)

    private fun ensureProgress(titleId: String, data: PlayerTagData): TagProgress {
        val progress = data.tagProgress.computeIfAbsent(titleId) { TagProgress() }
        if (progress.selectedParticleId != null && progress.selectedParticleId !in progress.ownedParticles) {
            progress.selectedParticleId = null
        }
        return progress
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

    private fun titleGroups(data: PlayerTagData, titleId: String): List<String> {
        config.tags[titleId]?.let { return effectiveGroups(it) }
        return data.customTitles[titleId]?.groupId?.takeIf(config::hasUpgradeGroup)?.let(::listOf).orEmpty()
    }

    private fun resolveKnownTitleId(data: PlayerTagData, input: String): String? {
        config.tags[input]?.let { return it.id }
        data.customTitles[input]?.let { return it.id }
        return resolveTag(input)?.id
    }

    private fun resolveTag(input: String?): TagDefinition? {
        if (input.isNullOrBlank()) {
            return null
        }
        config.tags[input]?.let { return it }
        val normalized = Support.stripColor(input).trim()
        return config.tags.values.firstOrNull { Support.stripColor(it.display).equals(normalized, ignoreCase = true) }
    }

    private fun isOwnedTitle(data: PlayerTagData, titleId: String): Boolean {
        return titleId in data.ownedTags || titleId in data.customTitles
    }

    private fun success(action: String): AdminActionResult = AdminActionResult(
        success = true,
        messageKey = "admin-operation-success",
        args = listOf(action),
    )

    private fun failure(messageKey: String, vararg args: Any?): AdminActionResult = AdminActionResult(
        success = false,
        messageKey = messageKey,
        args = args.toList(),
    )
}
