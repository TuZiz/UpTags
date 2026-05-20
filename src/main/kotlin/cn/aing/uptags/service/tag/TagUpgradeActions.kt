package cn.aing.uptags.service.tag

import cn.aing.uptags.service.economy.EconomyBridge

import cn.aing.uptags.Support
import cn.aing.uptags.config.ConfigRegistry
import cn.aing.uptags.config.MessageService
import cn.aing.uptags.model.config.CurrencyType
import cn.aing.uptags.model.config.TagDefinition
import cn.aing.uptags.model.runtime.PlayerTagData
import cn.aing.uptags.model.runtime.ScrollKind
import cn.aing.uptags.model.runtime.TagProgress
import cn.aing.uptags.repository.PlayerDataRepository
import org.bukkit.entity.Player
import org.bukkit.inventory.ItemStack
import java.util.LinkedHashSet

internal class TagUpgradeActions(
    private val config: ConfigRegistry,
    private val repository: PlayerDataRepository,
    private val economyBridge: EconomyBridge,
    private val messageService: MessageService,
    private val conditionChecker: TagConditionChecker,
    private val preparePlayer: (Player, Boolean) -> Unit,
    private val createDetachScroll: (ScrollKind, String, Int) -> ItemStack?,
    private val hasInventorySpace: (Player, ItemStack) -> Boolean,
) {
    fun upgradeBuff(player: Player, tagId: String, buffId: String): Boolean {
        val buff = config.buffs[buffId]
        if (buff == null || !isOwned(player, tagId)) {
            messageService.send(player, "not-owned")
            return false
        }
        if (buffId !in allowedBuffIds(player, tagId)) {
            messageService.send(player, "condition-failed")
            return false
        }
        val data = data(player)
        val progress = ensureProgress(tagId, data)
        val currentLevel = progress.buffLevels.getOrDefault(buffId, 0)
        if (currentLevel >= buff.maxLevel) {
            return false
        }
        val nextLevel = currentLevel + 1
        val cost = buff.cost
        val price = cost.priceForLevel(nextLevel)
        if (!conditionChecker.check(player, cost.conditions)) {
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

    fun canUpgradeBuff(tagId: String, buffId: String, player: Player, levels: Int = 1): Boolean {
        val buff = config.buffs[buffId] ?: return false
        if (!isOwned(player, tagId) || buffId !in allowedBuffIds(player, tagId)) return false
        return buffLevel(player, tagId, buffId) + levels.coerceAtLeast(1) <= buff.maxLevel
    }

    fun grantBuffUpgrade(player: Player, tagId: String, buffId: String, levels: Int = 1): Boolean {
        val levelDelta = levels.coerceAtLeast(1)
        if (!canUpgradeBuff(tagId, buffId, player, levelDelta)) {
            return false
        }
        val buff = config.buffs[buffId] ?: return false
        val data = data(player)
        val progress = ensureProgress(tagId, data)
        val nextLevel = progress.buffLevels.getOrDefault(buffId, 0) + levelDelta
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
        val particle = config.particles[particleId]
        if (particle == null || !isOwned(player, tagId)) return false
        if (particleId !in allowedParticleIds(player, tagId)) {
            messageService.send(player, "condition-failed")
            return false
        }
        val data = data(player)
        val progress = ensureProgress(tagId, data)
        if (particleId in progress.ownedParticles) {
            return true
        }
        val cost = particle.cost
        val price = cost.priceForLevel(1)
        if (!conditionChecker.check(player, cost.conditions)) {
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
        val particle = config.particles[particleId] ?: return false
        if (!isOwned(player, tagId) || particle.id !in allowedParticleIds(player, tagId)) return false
        return particle.id !in (data(player).tagProgress[tagId]?.ownedParticles ?: emptySet())
    }

    fun grantParticle(player: Player, tagId: String, particleId: String): Boolean {
        if (!canUnlockParticle(tagId, particleId, player)) {
            return false
        }
        val particle = config.particles[particleId] ?: return false
        val data = data(player)
        val progress = ensureProgress(tagId, data)
        progress.ownedParticles += particleId
        if (progress.selectedParticleId.isNullOrBlank()) {
            progress.selectedParticleId = particleId
        }
        repository.saveAsync(data)
        messageService.send(player, "particle-bought", Support.stripColor(particle.display))
        return true
    }

    fun detachBuff(player: Player, tagId: String, buffId: String, currency: CurrencyType): Boolean {
        val buff = config.buffs[buffId]
        if (buff == null || !isOwned(player, tagId)) {
            messageService.send(player, "not-owned")
            return false
        }
        if (!config.detach.enabled) {
            messageService.send(player, "detach-disabled")
            return false
        }
        val data = data(player)
        val progress = data.tagProgress[tagId] ?: run {
            messageService.send(player, "detach-no-effect")
            return false
        }
        val currentLevel = progress.buffLevels.getOrDefault(buffId, 0)
        if (currentLevel <= 0) {
            messageService.send(player, "detach-no-effect")
            return false
        }
        val scroll = createDetachScroll(ScrollKind.BUFF, buffId, currentLevel) ?: run {
            messageService.send(player, "detach-scroll-missing")
            return false
        }
        if (!hasInventorySpace(player, scroll)) {
            messageService.send(player, "detach-inventory-full")
            return false
        }
        val price = config.detach.buff.amount(currency) ?: run {
            messageService.send(player, "detach-invalid-currency")
            return false
        }
        if (!withdrawDetachCost(player, currency, price)) {
            return false
        }
        progress.buffLevels.remove(buffId)
        progress.activeBuffs.remove(buffId)
        player.inventory.addItem(scroll)
        repository.saveAsync(data)
        messageService.send(
            player,
            "buff-detached",
            Support.stripColor(buff.display),
            Support.roman(currentLevel),
            Support.formatDouble(price),
            economyBridge.displayName(currency),
        )
        return true
    }

    fun detachParticle(player: Player, tagId: String, particleId: String, currency: CurrencyType): Boolean {
        val particle = config.particles[particleId]
        if (particle == null || !isOwned(player, tagId)) {
            messageService.send(player, "not-owned")
            return false
        }
        if (!config.detach.enabled) {
            messageService.send(player, "detach-disabled")
            return false
        }
        val data = data(player)
        val progress = data.tagProgress[tagId] ?: run {
            messageService.send(player, "detach-no-effect")
            return false
        }
        if (particleId !in progress.ownedParticles) {
            messageService.send(player, "detach-no-effect")
            return false
        }
        val scroll = createDetachScroll(ScrollKind.PARTICLE, particleId, 1) ?: run {
            messageService.send(player, "detach-scroll-missing")
            return false
        }
        if (!hasInventorySpace(player, scroll)) {
            messageService.send(player, "detach-inventory-full")
            return false
        }
        val price = config.detach.particle.amount(currency) ?: run {
            messageService.send(player, "detach-invalid-currency")
            return false
        }
        if (!withdrawDetachCost(player, currency, price)) {
            return false
        }
        progress.ownedParticles.remove(particleId)
        if (progress.selectedParticleId == particleId) {
            progress.selectedParticleId = null
        }
        player.inventory.addItem(scroll)
        repository.saveAsync(data)
        messageService.send(
            player,
            "particle-detached",
            Support.stripColor(particle.display),
            Support.formatDouble(price),
            economyBridge.displayName(currency),
        )
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

    private fun data(player: Player): PlayerTagData = repository.get(player.uniqueId)

    private fun ensureProgress(titleId: String, data: PlayerTagData): TagProgress {
        val progress = data.tagProgress.computeIfAbsent(titleId) { TagProgress() }
        if (progress.selectedParticleId != null && progress.selectedParticleId !in progress.ownedParticles) {
            progress.selectedParticleId = null
        }
        return progress
    }

    private fun isOwned(player: Player, tagId: String): Boolean {
        preparePlayer(player, false)
        val data = data(player)
        return tagId in data.ownedTags || tagId in data.customTitles
    }

    private fun buffLevel(player: Player, tagId: String, buffId: String): Int {
        return data(player).tagProgress[tagId]?.buffLevels?.getOrDefault(buffId, 0) ?: 0
    }

    private fun allowedBuffIds(tag: TagDefinition): List<String> = allowedBuffIds(effectiveGroups(tag))

    private fun allowedParticleIds(tag: TagDefinition): List<String> = allowedParticleIds(effectiveGroups(tag))

    private fun allowedBuffIds(player: Player, titleId: String): List<String> = allowedBuffIds(titleGroups(player, titleId))

    private fun allowedParticleIds(player: Player, titleId: String): List<String> = allowedParticleIds(titleGroups(player, titleId))

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

    private fun withdrawDetachCost(player: Player, currency: CurrencyType, price: Double): Boolean {
        if (currency != CurrencyType.MONEY && currency != CurrencyType.POINTS) {
            messageService.send(player, "detach-invalid-currency")
            return false
        }
        if (price <= 0.0) {
            return true
        }
        if (!economyBridge.isAvailable(currency)) {
            messageService.send(player, "economy-unavailable", economyBridge.displayName(currency))
            return false
        }
        if (economyBridge.balance(player, currency) < price || !economyBridge.withdraw(player, currency, price)) {
            messageService.send(player, "not-enough", Support.formatDouble(price), economyBridge.displayName(currency))
            return false
        }
        return true
    }
}
