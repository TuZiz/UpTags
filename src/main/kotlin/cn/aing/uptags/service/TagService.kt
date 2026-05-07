package cn.aing.uptags.service

import cn.aing.uptags.Support
import cn.aing.uptags.UpTagsPlugin
import cn.aing.uptags.config.ConfigRegistry
import cn.aing.uptags.config.MessageService
import cn.aing.uptags.model.config.BuffDefinition
import cn.aing.uptags.model.config.CurrencyType
import cn.aing.uptags.model.config.ParticleDefinition
import cn.aing.uptags.model.config.TagDefinition
import cn.aing.uptags.model.runtime.CustomTitleData
import cn.aing.uptags.model.runtime.PlayerTagData
import cn.aing.uptags.model.runtime.ScrollKind
import cn.aing.uptags.model.runtime.TagProgress
import cn.aing.uptags.model.runtime.TitleEntry
import cn.aing.uptags.model.runtime.TitleKind
import cn.aing.uptags.repository.PlayerDataRepository
import org.bukkit.OfflinePlayer
import org.bukkit.entity.Player
import org.bukkit.inventory.ItemStack
import java.util.UUID

data class AdminActionResult(
    val success: Boolean,
    val messageKey: String,
    val args: List<Any?> = emptyList(),
)

class TagService(
    private val plugin: UpTagsPlugin,
    private val config: ConfigRegistry,
    private val repository: PlayerDataRepository,
    private val economyBridge: EconomyBridge,
    private val messageService: MessageService,
) {
    private val legacyCustomTagPrefix = "custom-"
    private var scrollFactory: ((String, Int) -> ItemStack)? = null
    private val conditionChecker = TagConditionChecker()
    private val readModel = TagReadModel(
        config = config,
        repository = repository,
        preparePlayer = ::preparePlayer,
    )
    private val catalogEditor = TagCatalogEditor(
        config = config,
        repository = repository,
        enforceDefaultTag = ::enforceDefaultTag,
    )
    private val adminActions = TagAdminActions(
        config = config,
        repository = repository,
        createDetachScroll = ::createDetachScroll,
        hasInventorySpace = ::hasInventorySpace,
    )
    private val upgradeActions = TagUpgradeActions(
        config = config,
        repository = repository,
        economyBridge = economyBridge,
        messageService = messageService,
        conditionChecker = conditionChecker,
        preparePlayer = ::preparePlayer,
        createDetachScroll = ::createDetachScroll,
        hasInventorySpace = ::hasInventorySpace,
    )

    fun attachScrollFactory(factory: (String, Int) -> ItemStack) {
        scrollFactory = factory
    }

    fun preparePlayer(player: Player, announce: Boolean) {
        val data = repository.get(player.uniqueId)
        migrateLegacyCustomTitles(data)
        if (data.tagColorOverrides.isNotEmpty()) {
            data.tagColorOverrides.clear()
        }
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
        val equippedTagId = data.equippedTagId
        val equippedTagValid = equippedTagId != null && equippedTagId in data.ownedTags && equippedTagId in config.tags
        if (data.equippedCustomTitleId == null && !equippedTagValid) {
            data.equippedTagId = forcedTag.id
        }
    }

    private fun ensureProgress(definition: TagDefinition, data: PlayerTagData): TagProgress = ensureProgress(definition.id, data)

    private fun ensureProgress(titleId: String, data: PlayerTagData): TagProgress {
        val progress = data.tagProgress.computeIfAbsent(titleId) { TagProgress() }
        if (progress.selectedParticleId != null && progress.selectedParticleId !in progress.ownedParticles) {
            progress.selectedParticleId = null
        }
        return progress
    }

    private fun migrateLegacyCustomTitles(data: PlayerTagData) {
        val legacyOwned = data.ownedTags.filter { it.startsWith(legacyCustomTagPrefix, ignoreCase = true) && it in data.customTitles }
        if (legacyOwned.isNotEmpty()) {
            data.ownedTags.removeAll(legacyOwned.toSet())
        }
        val legacyEquipped = data.equippedTagId?.takeIf {
            it.startsWith(legacyCustomTagPrefix, ignoreCase = true) && it in data.customTitles
        }
        if (legacyEquipped != null) {
            data.equippedCustomTitleId = legacyEquipped
            data.equippedTagId = null
        }
    }

    fun equippedTag(player: Player): TagDefinition? {
        val id = data(player).equippedTagId ?: return null
        return config.tags[id]
    }

    fun equippedCustomTitle(player: Player): CustomTitleData? {
        val id = data(player).equippedCustomTitleId ?: return null
        return data(player).customTitles[id]
    }

    fun currentTagId(player: Player): String = readModel.currentTagId(player)

    fun currentTagId(uniqueId: UUID): String = readModel.currentTagId(uniqueId)

    fun currentTagDisplay(player: Player): String = readModel.currentTagDisplay(player)

    fun currentTagDisplay(uniqueId: UUID): String = readModel.currentTagDisplay(uniqueId)

    fun renderedTagDisplay(player: Player, tagId: String): String = readModel.renderedTagDisplay(player, tagId)

    fun renderCustomTitle(customTitle: CustomTitleData): String = readModel.renderCustomTitle(customTitle)

    fun resolveTag(input: String?): TagDefinition? = readModel.resolveTag(input)

    fun resolveTitleId(player: Player, input: String?): String? = readModel.resolveTitleId(player, input)

    fun titleExists(player: Player, titleId: String): Boolean = readModel.titleExists(player, titleId)

    fun tagName(tagId: String): String = readModel.tagName(tagId)

    fun titleName(player: Player, titleId: String): String = readModel.titleName(player, titleId)

    fun titleName(uniqueId: UUID, titleId: String): String = readModel.titleName(uniqueId, titleId)

    fun visibleTags(player: Player): List<TagDefinition> = readModel.visibleTags(player)

    fun visibleTitles(player: Player): List<TitleEntry> = readModel.visibleTitles(player)

    fun visibleTitles(uniqueId: UUID): List<TitleEntry> = readModel.visibleTitles(uniqueId)

    fun isOwned(player: Player, tagId: String): Boolean {
        preparePlayer(player, false)
        val data = data(player)
        return tagId in data.ownedTags || tagId in data.customTitles
    }

    fun isOwned(uniqueId: UUID, tagId: String): Boolean {
        val data = data(uniqueId)
        return tagId in data.ownedTags || tagId in data.customTitles
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
        data.equippedCustomTitleId = null
        data.equippedTagId = definition.id
        repository.saveAsync(data)
        messageService.send(player, "tag-equipped", Support.stripColor(definition.display))
        return true
    }

    fun equipCustomTitle(player: Player, customId: String): Boolean {
        val data = data(player)
        if (customId !in data.customTitles) {
            messageService.send(player, "custom-title-not-found")
            return false
        }
        ensureProgress(customId, data)
        data.equippedCustomTitleId = customId
        data.equippedTagId = null
        repository.saveAsync(data)
        messageService.send(player, "custom-title-equipped", currentTagDisplay(player))
        return true
    }

    fun unequipTag(player: Player): Boolean {
        val data = data(player)
        data.equippedCustomTitleId = null
        if (config.settings.forceDefaultTag) {
            val forcedTag = config.tags[config.settings.forcedTagId] ?: return false
            data.equippedTagId = forcedTag.id
            repository.saveAsync(data)
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

    fun adminEquipTitle(uniqueId: UUID, titleId: String): AdminActionResult = adminActions.equipTitle(uniqueId, titleId)

    fun adminUnequipTitle(uniqueId: UUID): AdminActionResult = adminActions.unequipTitle(uniqueId)

    fun adminSetBuffLevel(uniqueId: UUID, titleId: String, buffId: String, level: Int): AdminActionResult =
        adminActions.setBuffLevel(uniqueId, titleId, buffId, level)

    fun adminSetBuffEnabled(uniqueId: UUID, titleId: String, buffId: String, enabled: Boolean): AdminActionResult =
        adminActions.setBuffEnabled(uniqueId, titleId, buffId, enabled)

    fun adminDetachBuff(uniqueId: UUID, titleId: String, buffId: String, receiver: Player): AdminActionResult =
        adminActions.detachBuff(uniqueId, titleId, buffId, receiver)

    fun adminGiveParticle(uniqueId: UUID, titleId: String, particleId: String): AdminActionResult =
        adminActions.giveParticle(uniqueId, titleId, particleId)

    fun adminTakeParticle(uniqueId: UUID, titleId: String, particleId: String): AdminActionResult =
        adminActions.takeParticle(uniqueId, titleId, particleId)

    fun adminSelectParticle(uniqueId: UUID, titleId: String, particleId: String): AdminActionResult =
        adminActions.selectParticle(uniqueId, titleId, particleId)

    fun adminClearParticle(uniqueId: UUID, titleId: String): AdminActionResult =
        adminActions.clearParticle(uniqueId, titleId)

    fun adminDetachParticle(uniqueId: UUID, titleId: String, particleId: String, receiver: Player): AdminActionResult =
        adminActions.detachParticle(uniqueId, titleId, particleId, receiver)

    fun adminEquipCustomTitle(uniqueId: UUID, customId: String): AdminActionResult =
        adminActions.equipCustomTitle(uniqueId, customId)

    fun adminDeleteCustomTitle(uniqueId: UUID, customId: String): AdminActionResult =
        adminActions.deleteCustomTitle(uniqueId, customId)

    fun buffLevel(player: Player, tagId: String, buffId: String): Int = readModel.buffLevel(player, tagId, buffId)

    fun buffLevel(uniqueId: UUID, tagId: String, buffId: String): Int = readModel.buffLevel(uniqueId, tagId, buffId)

    fun isBuffEnabled(player: Player, tagId: String, buffId: String): Boolean = readModel.isBuffEnabled(player, tagId, buffId)

    fun isBuffEnabled(uniqueId: UUID, tagId: String, buffId: String): Boolean = readModel.isBuffEnabled(uniqueId, tagId, buffId)

    fun isParticleOwned(player: Player, tagId: String, particleId: String): Boolean = readModel.isParticleOwned(player, tagId, particleId)

    fun isParticleOwned(uniqueId: UUID, tagId: String, particleId: String): Boolean = readModel.isParticleOwned(uniqueId, tagId, particleId)

    fun isParticleSelected(player: Player, tagId: String, particleId: String): Boolean = readModel.isParticleSelected(player, tagId, particleId)

    fun isParticleSelected(uniqueId: UUID, tagId: String, particleId: String): Boolean = readModel.isParticleSelected(uniqueId, tagId, particleId)

    fun allowedBuffIds(tag: TagDefinition): List<String> = readModel.allowedBuffIds(tag)

    fun allowedParticleIds(tag: TagDefinition): List<String> = readModel.allowedParticleIds(tag)

    fun allowedBuffIds(player: Player, titleId: String): List<String> = readModel.allowedBuffIds(player, titleId)

    fun allowedParticleIds(player: Player, titleId: String): List<String> = readModel.allowedParticleIds(player, titleId)

    fun allowedBuffIds(uniqueId: UUID, titleId: String): List<String> = readModel.allowedBuffIds(uniqueId, titleId)

    fun allowedParticleIds(uniqueId: UUID, titleId: String): List<String> = readModel.allowedParticleIds(uniqueId, titleId)

    fun upgradeBuff(player: Player, tagId: String, buffId: String): Boolean =
        upgradeActions.upgradeBuff(player, tagId, buffId)

    fun canUpgradeBuff(tagId: String, buffId: String, player: Player, levels: Int = 1): Boolean =
        upgradeActions.canUpgradeBuff(tagId, buffId, player, levels)

    fun grantBuffUpgrade(player: Player, tagId: String, buffId: String, levels: Int = 1): Boolean =
        upgradeActions.grantBuffUpgrade(player, tagId, buffId, levels)

    fun toggleBuff(player: Player, tagId: String, buffId: String): Boolean =
        upgradeActions.toggleBuff(player, tagId, buffId)

    fun buyParticle(player: Player, tagId: String, particleId: String): Boolean =
        upgradeActions.buyParticle(player, tagId, particleId)

    fun canUnlockParticle(tagId: String, particleId: String, player: Player): Boolean =
        upgradeActions.canUnlockParticle(tagId, particleId, player)

    fun grantParticle(player: Player, tagId: String, particleId: String): Boolean =
        upgradeActions.grantParticle(player, tagId, particleId)

    fun detachBuff(player: Player, tagId: String, buffId: String, currency: CurrencyType): Boolean =
        upgradeActions.detachBuff(player, tagId, buffId, currency)

    fun detachParticle(player: Player, tagId: String, particleId: String, currency: CurrencyType): Boolean =
        upgradeActions.detachParticle(player, tagId, particleId, currency)

    fun selectParticle(player: Player, tagId: String, particleId: String): Boolean =
        upgradeActions.selectParticle(player, tagId, particleId)

    private fun createDetachScroll(kind: ScrollKind, targetId: String, level: Int): ItemStack? {
        val scrollKey = config.scrolls.values.firstOrNull { definition ->
            definition.kind == kind && definition.targetId == targetId
        }?.key
        if (scrollKey == null) {
            return null
        }
        val factory = scrollFactory
        if (factory == null) {
            return null
        }
        return factory(scrollKey, level.coerceAtLeast(1))
    }

    private fun hasInventorySpace(player: Player, item: ItemStack): Boolean {
        var remaining = item.amount.coerceAtLeast(1)
        for (stored in player.inventory.storageContents) {
            if (stored == null || stored.type.isAir) {
                return true
            }
            if (stored.isSimilar(item)) {
                remaining -= (stored.maxStackSize - stored.amount).coerceAtLeast(0)
                if (remaining <= 0) {
                    return true
                }
            }
        }
        return false
    }

    fun activeBuffs(player: Player): Collection<BuffDefinition> = readModel.activeBuffs(player)

    fun activeBuffLevel(player: Player, buffId: String): Int = readModel.activeBuffLevel(player, buffId)

    fun selectedParticle(player: Player): ParticleDefinition? = readModel.selectedParticle(player)

    fun checkConditions(player: Player, conditions: List<String>): Boolean = conditionChecker.check(player, conditions)

    fun activeBuffsDisplay(player: Player): String = readModel.activeBuffsDisplay(player)

    fun activeBuffsDisplay(uniqueId: UUID): String = readModel.activeBuffsDisplay(uniqueId)

    fun particleDisplay(player: Player): String = readModel.particleDisplay(player)

    fun particleDisplay(uniqueId: UUID): String = readModel.particleDisplay(uniqueId)

    fun particleId(player: Player): String = readModel.particleId(player)

    fun particleId(uniqueId: UUID): String = readModel.particleId(uniqueId)

    fun collectedCount(player: Player): Int = readModel.collectedCount(player)

    fun collectedCount(uniqueId: UUID): Int = readModel.collectedCount(uniqueId)

    fun totalCount(): Int = readModel.totalCount()

    fun progress(player: Player): String = readModel.progress(player)

    fun progress(uniqueId: UUID): String = readModel.progress(uniqueId)

    fun tagBuffsDisplay(player: Player, tagId: String): String = readModel.tagBuffsDisplay(player, tagId)

    fun tagBuffsDisplay(uniqueId: UUID, tagId: String): String = readModel.tagBuffsDisplay(uniqueId, tagId)

    fun tagFirstBuff(player: Player, tagId: String): String = readModel.tagFirstBuff(player, tagId)

    fun tagFirstBuff(uniqueId: UUID, tagId: String): String = readModel.tagFirstBuff(uniqueId, tagId)

    fun tagBuffCount(player: Player, tagId: String): Int = readModel.tagBuffCount(player, tagId)

    fun tagBuffCount(uniqueId: UUID, tagId: String): Int = readModel.tagBuffCount(uniqueId, tagId)

    fun tagParticlesDisplay(player: Player, tagId: String): String = readModel.tagParticlesDisplay(player, tagId)

    fun tagParticlesDisplay(uniqueId: UUID, tagId: String): String = readModel.tagParticlesDisplay(uniqueId, tagId)

    fun tagParticleCount(player: Player, tagId: String): Int = readModel.tagParticleCount(player, tagId)

    fun tagParticleCount(uniqueId: UUID, tagId: String): Int = readModel.tagParticleCount(uniqueId, tagId)

    fun tagSelectedParticle(player: Player, tagId: String): String = readModel.tagSelectedParticle(player, tagId)

    fun tagSelectedParticle(uniqueId: UUID, tagId: String): String = readModel.tagSelectedParticle(uniqueId, tagId)

    fun groupLevel(player: Player, groupId: String): String = readModel.groupLevel(player, groupId)

    fun groupLevel(uniqueId: UUID, groupId: String): String = readModel.groupLevel(uniqueId, groupId)

    fun groupName(groupId: String): String = readModel.groupName(groupId)

    fun trackLevel(player: Player, trackId: String): String = readModel.trackLevel(player, trackId)

    fun trackLevel(uniqueId: UUID, trackId: String): String = readModel.trackLevel(uniqueId, trackId)

    fun canUpgrade(player: Player): Boolean = readModel.canUpgrade(player)

    fun canUpgrade(uniqueId: UUID): Boolean = readModel.canUpgrade(uniqueId)

    fun points(player: Player): Double = economyBridge.balance(player, CurrencyType.POINTS)


    fun titleCoins(player: Player): Double = economyBridge.balance(player, CurrencyType.TITLE_COIN)

    fun titleCoins(uniqueId: UUID): Double = data(uniqueId).titleCoinBalance

    fun rarityDisplay(rarity: String): String = readModel.rarityDisplay(rarity)

    fun updateTagDisplay(tagId: String, display: String): Boolean = catalogEditor.updateDisplay(tagId, display)

    fun updateTagRarity(tagId: String, rarity: String): Boolean = catalogEditor.updateRarity(tagId, rarity)

    fun updateTagGroups(tagId: String, groups: List<String>): Boolean = catalogEditor.updateGroups(tagId, groups)

    fun updateTagDefaultUnlocked(tagId: String, value: Boolean): Boolean = catalogEditor.updateDefaultUnlocked(tagId, value)

    fun createTag(id: String): Boolean = catalogEditor.create(id)

    fun createTagQuick(tagId: String, permission: String, buffGroup: String, particleGroup: String): Boolean =
        catalogEditor.createQuick(tagId, permission, buffGroup, particleGroup)

    fun deleteTag(tagId: String): Boolean = catalogEditor.delete(tagId)
}
