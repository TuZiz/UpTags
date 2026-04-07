package cn.aing.uptags.service

import cn.aing.uptags.Support
import cn.aing.uptags.UpTagsPlugin
import cn.aing.uptags.config.ConfigRegistry
import cn.aing.uptags.config.MessageService
import cn.aing.uptags.model.config.BuffDefinition
import cn.aing.uptags.model.config.CurrencyType
import cn.aing.uptags.model.config.ParticleDefinition
import cn.aing.uptags.model.config.TagDefinition
import cn.aing.uptags.model.config.UpgradeGroupDefinition
import cn.aing.uptags.model.runtime.PlayerTagData
import cn.aing.uptags.model.runtime.TagProgress
import cn.aing.uptags.repository.PlayerDataRepository
import org.bukkit.Bukkit
import org.bukkit.OfflinePlayer
import org.bukkit.entity.Player
import java.util.LinkedHashSet
import java.util.Locale
import java.util.UUID
import java.util.regex.Pattern

class TagService(
    private val plugin: UpTagsPlugin,
    private val config: ConfigRegistry,
    private val repository: PlayerDataRepository,
    private val economyBridge: EconomyBridge,
    private val messageService: MessageService,
) {
    private val conditionPattern = Pattern.compile("(.+?)(==|!=|>=|<=|>|<)(.+)")

    fun preparePlayer(player: Player, announce: Boolean) {
        val data = repository.get(player.uniqueId)
        val unlocked = syncAutoUnlocks(player, data)
        if (announce && unlocked > 0) {
            messageService.send(player, "default-unlocked", unlocked)
        }
        enforceDefaultTag(player, data)
        repository.saveAsync(data)
    }

    fun data(uniqueId: UUID): PlayerTagData = repository.get(uniqueId)

    fun data(player: Player): PlayerTagData = repository.get(player.uniqueId)

    fun syncAutoUnlocks(player: Player, data: PlayerTagData): Int {
        var unlocked = 0
        for (definition in config.tags.values) {
            if (!definition.defaultUnlocked && !hasPermissionTag(player, definition)) {
                continue
            }
            if (data.ownedTags.add(definition.id)) {
                unlocked++
            }
            ensureProgress(definition, data)
        }
        return unlocked
    }

    private fun hasPermissionTag(player: Player, definition: TagDefinition): Boolean {
        val permission = definition.permission?.takeIf { it.isNotBlank() }
        return if (permission != null) {
            player.hasPermission("uptags.tag.*") || player.hasPermission(permission)
        } else {
            player.hasPermission("uptags.tag.*") ||
                player.hasPermission("uptags.tag.${definition.id}") ||
                player.hasPermission("uptags.unlock.${definition.id}")
        }
    }

    private fun enforceDefaultTag(player: Player, data: PlayerTagData) {
        val settings = config.settings
        if (!settings.forceDefaultTag) {
            return
        }
        val forcedTag = config.tags[settings.forcedTagId] ?: return
        data.ownedTags += forcedTag.id
        ensureProgress(forcedTag, data)
        if (data.equippedTagId != forcedTag.id) {
            data.equippedTagId = forcedTag.id
        }
    }

    private fun ensureProgress(definition: TagDefinition, data: PlayerTagData): TagProgress {
        val progress = data.tagProgress.computeIfAbsent(definition.id) { TagProgress() }
        if (progress.selectedParticleId != null && progress.selectedParticleId !in progress.ownedParticles) {
            progress.selectedParticleId = null
        }
        return progress
    }

    fun equippedTag(player: Player): TagDefinition? {
        val id = data(player).equippedTagId ?: return null
        return config.tags[id]
    }

    fun currentTagId(player: Player): String = equippedTag(player)?.id ?: ""

    fun currentTagDisplay(player: Player): String = equippedTag(player)?.let { Support.color(it.display) } ?: "无"

    fun resolveTag(input: String?): TagDefinition? {
        if (input.isNullOrBlank()) {
            return null
        }
        config.tags[input]?.let { return it }
        val normalized = Support.stripColor(input).trim()
        return config.tags.values.firstOrNull { Support.stripColor(it.display).equals(normalized, ignoreCase = true) }
    }

    fun tagName(tagId: String): String = config.tags[tagId]?.let { Support.stripColor(it.display) } ?: tagId

    fun visibleTags(player: Player): List<TagDefinition> {
        preparePlayer(player, false)
        return config.tags.values.toList()
    }

    fun isOwned(player: Player, tagId: String): Boolean {
        preparePlayer(player, false)
        return tagId in data(player).ownedTags
    }

    fun equipTag(player: Player, tagId: String): Boolean {
        preparePlayer(player, false)
        val definition = resolveTag(tagId) ?: run {
            messageService.send(player, "tag-not-found", tagId)
            return false
        }
        val data = data(player)
        if (definition.id !in data.ownedTags) {
            messageService.send(player, "not-owned")
            return false
        }
        ensureProgress(definition, data)
        data.equippedTagId = definition.id
        repository.saveAsync(data)
        messageService.send(player, "tag-equipped", Support.stripColor(definition.display))
        return true
    }

    fun unequipTag(player: Player): Boolean {
        val data = data(player)
        if (config.settings.forceDefaultTag) {
            messageService.send(player, "force-default-block")
            return false
        }
        data.equippedTagId = null
        repository.saveAsync(data)
        messageService.send(player, "tag-unequipped")
        return true
    }

    fun giveTag(target: OfflinePlayer, tagId: String): Boolean {
        val definition = resolveTag(tagId) ?: return false
        val data = data(target.uniqueId)
        val added = data.ownedTags.add(definition.id)
        ensureProgress(definition, data)
        target.player?.let { enforceDefaultTag(it, data) }
        repository.saveAsync(data)
        return added
    }

    fun takeTag(target: OfflinePlayer, tagId: String): Boolean {
        val definition = resolveTag(tagId) ?: return false
        val data = data(target.uniqueId)
        val removed = data.ownedTags.remove(definition.id)
        if (data.equippedTagId == definition.id) {
            data.equippedTagId = null
            target.player?.let { enforceDefaultTag(it, data) }
        }
        data.tagProgress.remove(definition.id)
        repository.saveAsync(data)
        return removed
    }

    fun buffLevel(player: Player, tagId: String, buffId: String): Int = data(player).tagProgress[tagId]?.buffLevels?.getOrDefault(buffId, 0) ?: 0

    fun isBuffEnabled(player: Player, tagId: String, buffId: String): Boolean {
        val progress = data(player).tagProgress[tagId] ?: return false
        return buffId in progress.activeBuffs && progress.buffLevels.getOrDefault(buffId, 0) > 0
    }

    fun isParticleOwned(player: Player, tagId: String, particleId: String): Boolean = particleId in (data(player).tagProgress[tagId]?.ownedParticles ?: emptySet())

    fun isParticleSelected(player: Player, tagId: String, particleId: String): Boolean = data(player).tagProgress[tagId]?.selectedParticleId == particleId

    fun allowedBuffIds(tag: TagDefinition): List<String> {
        val ids = LinkedHashSet<String>()
        for (groupId in effectiveGroups(tag)) {
            config.upgradeGroups[groupId]?.let { ids += it.buffs }
        }
        return ids.toList()
    }

    fun allowedParticleIds(tag: TagDefinition): List<String> {
        val ids = LinkedHashSet<String>()
        for (groupId in effectiveGroups(tag)) {
            config.upgradeGroups[groupId]?.let { ids += it.particles }
        }
        return ids.toList()
    }

    private fun effectiveGroups(tag: TagDefinition): List<String> = if (tag.upgradeGroups.isNotEmpty()) tag.upgradeGroups else config.defaultGroupsForRarity(tag.rarity)

    fun upgradeBuff(player: Player, tagId: String, buffId: String): Boolean {
        val tag = config.tags[tagId]
        val buff = config.buffs[buffId]
        if (tag == null || buff == null || !isOwned(player, tagId)) {
            messageService.send(player, "not-owned")
            return false
        }
        if (buffId !in allowedBuffIds(tag)) {
            messageService.send(player, "condition-failed")
            return false
        }
        val data = data(player)
        val progress = ensureProgress(tag, data)
        val currentLevel = progress.buffLevels.getOrDefault(buffId, 0)
        if (currentLevel >= buff.maxLevel) {
            return false
        }
        val nextLevel = currentLevel + 1
        val cost = buff.cost
        val price = cost.priceForLevel(nextLevel)
        if (!checkConditions(player, cost.conditions)) {
            messageService.send(player, "condition-failed")
            return false
        }
        if (!economyBridge.isAvailable(cost.type)) {
            messageService.send(player, "economy-unavailable", economyBridge.displayName(cost.type))
            return false
        }
        if (economyBridge.balance(player, cost.type) < price || !economyBridge.withdraw(player, cost.type, price)) {
            messageService.send(player, "not-enough", Support.formatDouble(price), economyBridge.displayName(cost.type))
            return false
        }
        progress.buffLevels[buffId] = nextLevel
        progress.activeBuffs += buffId
        repository.saveAsync(data)
        messageService.send(player, "buff-upgraded", Support.stripColor(buff.display), Support.roman(nextLevel))
        return true
    }

    fun canUpgradeBuff(tagId: String, buffId: String, player: Player): Boolean {
        val tag = config.tags[tagId] ?: return false
        val buff = config.buffs[buffId] ?: return false
        if (!isOwned(player, tagId)) return false
        return buffLevel(player, tagId, buffId) < buff.maxLevel
    }

    fun grantBuffUpgrade(player: Player, tagId: String, buffId: String): Boolean {
        if (!canUpgradeBuff(tagId, buffId, player)) {
            return false
        }
        val tag = config.tags[tagId] ?: return false
        val buff = config.buffs[buffId] ?: return false
        val data = data(player)
        val progress = ensureProgress(tag, data)
        val nextLevel = progress.buffLevels.getOrDefault(buffId, 0) + 1
        progress.buffLevels[buffId] = nextLevel
        progress.activeBuffs += buffId
        repository.saveAsync(data)
        messageService.send(player, "buff-upgraded", Support.stripColor(buff.display), Support.roman(nextLevel))
        return true
    }

    fun toggleBuff(player: Player, tagId: String, buffId: String): Boolean {
        val buff = config.buffs[buffId] ?: return false
        val progress = data(player).tagProgress[tagId] ?: return false
        if (progress.buffLevels.getOrDefault(buffId, 0) <= 0) {
            return false
        }
        if (!progress.activeBuffs.add(buffId)) {
            progress.activeBuffs.remove(buffId)
            messageService.send(player, "buff-disabled", Support.stripColor(buff.display))
        } else {
            messageService.send(player, "buff-enabled", Support.stripColor(buff.display))
        }
        repository.saveAsync(data(player))
        return true
    }

    fun buyParticle(player: Player, tagId: String, particleId: String): Boolean {
        val tag = config.tags[tagId]
        val particle = config.particles[particleId]
        if (tag == null || particle == null || !isOwned(player, tagId)) return false
        if (particleId !in allowedParticleIds(tag)) {
            messageService.send(player, "condition-failed")
            return false
        }
        val data = data(player)
        val progress = ensureProgress(tag, data)
        if (particleId in progress.ownedParticles) {
            return true
        }
        val cost = particle.cost
        val price = cost.priceForLevel(1)
        if (!checkConditions(player, cost.conditions)) {
            messageService.send(player, "condition-failed")
            return false
        }
        if (!economyBridge.isAvailable(cost.type)) {
            messageService.send(player, "economy-unavailable", economyBridge.displayName(cost.type))
            return false
        }
        if (economyBridge.balance(player, cost.type) < price || !economyBridge.withdraw(player, cost.type, price)) {
            messageService.send(player, "not-enough", Support.formatDouble(price), economyBridge.displayName(cost.type))
            return false
        }
        progress.ownedParticles += particleId
        if (progress.selectedParticleId.isNullOrBlank()) {
            progress.selectedParticleId = particleId
        }
        repository.saveAsync(data)
        messageService.send(player, "particle-bought", Support.stripColor(particle.display))
        return true
    }

    fun canUnlockParticle(tagId: String, particleId: String, player: Player): Boolean {
        val tag = config.tags[tagId] ?: return false
        val particle = config.particles[particleId] ?: return false
        if (!isOwned(player, tagId)) return false
        return particle.id !in (data(player).tagProgress[tagId]?.ownedParticles ?: emptySet())
    }

    fun grantParticle(player: Player, tagId: String, particleId: String): Boolean {
        if (!canUnlockParticle(tagId, particleId, player)) {
            return false
        }
        val tag = config.tags[tagId] ?: return false
        val particle = config.particles[particleId] ?: return false
        val data = data(player)
        val progress = ensureProgress(tag, data)
        progress.ownedParticles += particleId
        if (progress.selectedParticleId.isNullOrBlank()) {
            progress.selectedParticleId = particleId
        }
        repository.saveAsync(data)
        messageService.send(player, "particle-bought", Support.stripColor(particle.display))
        return true
    }

    fun selectParticle(player: Player, tagId: String, particleId: String): Boolean {
        val particle = config.particles[particleId] ?: return false
        val progress = data(player).tagProgress[tagId] ?: return false
        if (particleId !in progress.ownedParticles) {
            return false
        }
        if (progress.selectedParticleId == particleId) {
            progress.selectedParticleId = null
            messageService.send(player, "particle-cleared")
        } else {
            progress.selectedParticleId = particleId
            messageService.send(player, "particle-selected", Support.stripColor(particle.display))
        }
        repository.saveAsync(data(player))
        return true
    }

    fun activeBuffs(player: Player): Collection<BuffDefinition> {
        val tag = equippedTag(player) ?: return emptyList()
        val progress = data(player).tagProgress[tag.id] ?: return emptyList()
        return progress.activeBuffs.mapNotNull { buffId ->
            val level = progress.buffLevels.getOrDefault(buffId, 0)
            if (level <= 0) null else config.buffs[buffId]
        }
    }

    fun activeBuffLevel(player: Player, buffId: String): Int {
        val tag = equippedTag(player) ?: return 0
        val progress = data(player).tagProgress[tag.id] ?: return 0
        return if (buffId in progress.activeBuffs) progress.buffLevels.getOrDefault(buffId, 0) else 0
    }

    fun selectedParticle(player: Player): ParticleDefinition? {
        val tag = equippedTag(player) ?: return null
        val progress = data(player).tagProgress[tag.id] ?: return null
        return progress.selectedParticleId?.let { config.particles[it] }
    }

    fun checkConditions(player: Player, conditions: List<String>): Boolean {
        if (conditions.isEmpty()) return true
        for (condition in conditions) {
            val rendered = PlaceholderHook.apply(player, condition)
            val matcher = conditionPattern.matcher(rendered)
            if (!matcher.matches()) continue
            val left = matcher.group(1).trim()
            val operator = matcher.group(2)
            val right = matcher.group(3).trim()
            if (!compare(left, operator, right)) {
                return false
            }
        }
        return true
    }

    private fun compare(left: String, operator: String, right: String): Boolean {
        return try {
            val leftNumber = left.toDouble()
            val rightNumber = right.toDouble()
            when (operator) {
                "==" -> leftNumber == rightNumber
                "!=" -> leftNumber != rightNumber
                ">=" -> leftNumber >= rightNumber
                "<=" -> leftNumber <= rightNumber
                ">" -> leftNumber > rightNumber
                "<" -> leftNumber < rightNumber
                else -> false
            }
        } catch (_: NumberFormatException) {
            when (operator) {
                "==" -> left.equals(right, ignoreCase = true)
                "!=" -> !left.equals(right, ignoreCase = true)
                else -> false
            }
        }
    }

    fun activeBuffsDisplay(player: Player): String {
        val tag = equippedTag(player) ?: return "无"
        val progress = data(player).tagProgress[tag.id] ?: return "无"
        val values = progress.activeBuffs.mapNotNull { buffId ->
            val level = progress.buffLevels.getOrDefault(buffId, 0)
            val buff = config.buffs[buffId] ?: return@mapNotNull null
            if (level <= 0) null else Support.color(buff.display) + " " + Support.roman(level)
        }
        return Support.joinDisplay(values)
    }

    fun particleDisplay(player: Player): String = selectedParticle(player)?.let { Support.color(it.display) } ?: "无"

    fun particleId(player: Player): String = selectedParticle(player)?.id ?: ""

    fun collectedCount(player: Player): Int {
        preparePlayer(player, false)
        return data(player).ownedTags.size
    }

    fun totalCount(): Int = config.tags.size

    fun progress(player: Player): String {
        val total = totalCount().coerceAtLeast(1)
        return Support.formatDouble(collectedCount(player) * 100.0 / total)
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

    fun tagFirstBuff(player: Player, tagId: String): String {
        val progress = data(player).tagProgress[tagId] ?: return "无"
        for ((buffId, level) in progress.buffLevels) {
            if (level <= 0) continue
            val buff = config.buffs[buffId] ?: continue
            return Support.color(buff.display) + " " + Support.roman(level)
        }
        return "无"
    }

    fun tagBuffCount(player: Player, tagId: String): Int = data(player).tagProgress[tagId]?.buffLevels?.values?.count { it > 0 } ?: 0

    fun tagParticlesDisplay(player: Player, tagId: String): String {
        val progress = data(player).tagProgress[tagId] ?: return "无"
        val values = progress.ownedParticles.mapNotNull { id -> config.particles[id]?.let { Support.color(it.display) } }
        return Support.joinDisplay(values)
    }

    fun tagParticleCount(player: Player, tagId: String): Int = data(player).tagProgress[tagId]?.ownedParticles?.size ?: 0

    fun tagSelectedParticle(player: Player, tagId: String): String {
        val id = data(player).tagProgress[tagId]?.selectedParticleId ?: return "无"
        return config.particles[id]?.let { Support.color(it.display) } ?: "无"
    }

    fun groupLevel(player: Player, groupId: String): String {
        val tag = equippedTag(player) ?: return "0"
        if (groupId !in effectiveGroups(tag)) return "0"
        val progress = data(player).tagProgress[tag.id] ?: return "0"
        val group = config.upgradeGroups[groupId] ?: return "0"
        var score = 0
        group.buffs.forEach { score += progress.buffLevels.getOrDefault(it, 0) }
        group.particles.forEach { if (it in progress.ownedParticles) score++ }
        return score.toString()
    }

    fun groupName(groupId: String): String = config.upgradeGroups[groupId]?.let { Support.color(it.name) } ?: ""

    fun trackLevel(player: Player, trackId: String): String = groupLevel(player, trackId)

    fun canUpgrade(player: Player): Boolean {
        val tag = equippedTag(player) ?: return false
        val progress = data(player).tagProgress[tag.id]
        if (progress == null) {
            return allowedBuffIds(tag).isNotEmpty() || allowedParticleIds(tag).isNotEmpty()
        }
        if (allowedBuffIds(tag).any { buffId ->
                val buff = config.buffs[buffId] ?: return@any false
                progress.buffLevels.getOrDefault(buffId, 0) < buff.maxLevel
            }
        ) return true
        return allowedParticleIds(tag).any { it !in progress.ownedParticles }
    }

    fun points(player: Player): Double = economyBridge.balance(player, CurrencyType.POINTS)

    fun rarityDisplay(rarity: String): String = Support.color(config.rarityDisplay(rarity))

    fun updateTagDisplay(tagId: String, display: String): Boolean {
        val definition = config.tags[tagId] ?: return false
        definition.display = display
        config.saveTags()
        return true
    }

    fun updateTagRarity(tagId: String, rarity: String): Boolean {
        val definition = config.tags[tagId] ?: return false
        val normalized = rarity.uppercase(Locale.ROOT)
        if (normalized !in config.allRarities()) return false
        definition.rarity = normalized
        definition.upgradeGroups = config.defaultGroupsForRarity(normalized).toMutableList()
        config.saveTags()
        return true
    }

    fun updateTagGroups(tagId: String, groups: List<String>): Boolean {
        val definition = config.tags[tagId] ?: return false
        val valid = groups.map { it.trim() }.filter { it.isNotEmpty() && config.upgradeGroups.containsKey(it) }.distinct()
        if (valid.isEmpty()) return false
        definition.upgradeGroups = valid.toMutableList()
        config.saveTags()
        return true
    }

    fun updateTagDefaultUnlocked(tagId: String, value: Boolean): Boolean {
        val definition = config.tags[tagId] ?: return false
        definition.defaultUnlocked = value
        config.saveTags()
        return true
    }

    fun createTag(id: String): Boolean {
        if (id.isBlank() || config.tags.containsKey(id)) return false
        config.createTag(id)
        return true
    }

    fun createTagQuick(tagId: String, permission: String, buffGroup: String, particleGroup: String): Boolean {
        val normalizedId = tagId.trim()
        val normalizedPermission = permission.trim()
        if (normalizedId.isBlank() || normalizedPermission.isBlank()) return false
        if (config.tags.containsKey(normalizedId)) return false
        if (!config.hasUpgradeGroup(buffGroup) || !config.hasUpgradeGroup(particleGroup)) return false

        val created = config.createTag(normalizedId)
        created.permission = normalizedPermission
        created.display = "&#FFFFFF[&#AAAAAA$normalizedId&#FFFFFF]"
        created.upgradeGroups = linkedSetOf(buffGroup, particleGroup).toMutableList()
        config.saveTags()
        return true
    }

    fun deleteTag(tagId: String): Boolean {
        if (!config.tags.containsKey(tagId)) return false
        config.deleteTag(tagId)
        for (online in Bukkit.getOnlinePlayers()) {
            val data = data(online)
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

private object PlaceholderHook {
    private var available = true

    fun apply(player: Player, text: String): String {
        if (!available || Bukkit.getPluginManager().getPlugin("PlaceholderAPI")?.isEnabled != true) {
            return text
        }
        return try {
            val clazz = Class.forName("me.clip.placeholderapi.PlaceholderAPI")
            val method = clazz.getMethod("setPlaceholders", org.bukkit.entity.Player::class.java, String::class.java)
            method.invoke(null, player, text) as? String ?: text
        } catch (_: Throwable) {
            available = false
            text
        }
    }
}
