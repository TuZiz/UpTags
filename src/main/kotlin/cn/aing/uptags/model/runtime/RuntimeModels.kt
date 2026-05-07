package cn.aing.uptags.model.runtime

import java.util.LinkedHashMap
import java.util.LinkedHashSet
import java.util.UUID
import org.bukkit.inventory.EquipmentSlot

enum class ScrollKind {
    BUFF,
    PARTICLE;

    companion object {
        fun from(raw: String?): ScrollKind? {
            if (raw.isNullOrBlank()) {
                return null
            }
            return entries.firstOrNull { it.name.equals(raw.trim(), ignoreCase = true) }
        }
    }
}

enum class TitleKind {
    TAG,
    CUSTOM,
}

data class TitleEntry(
    val id: String,
    val display: String,
    val description: List<String>,
    val rarityDisplay: String,
    val owned: Boolean,
    val kind: TitleKind,
)

data class ScrollSelectionContext(
    val scrollKey: String,
    val kind: ScrollKind,
    val targetId: String,
    val hand: EquipmentSlot,
    val level: Int = 1,
)

data class CustomTitleData(
    val id: String,
    var rawText: String,
    var presetId: String,
    var groupId: String? = null,
    var manualColors: MutableList<String> = mutableListOf(),
    var randomSchemes: MutableList<MutableList<String>> = mutableListOf(),
    var selectedSchemeIndex: Int = 0,
    var createdAt: Long = System.currentTimeMillis(),
) {
    fun copyDeep(): CustomTitleData = CustomTitleData(
        id = id,
        rawText = rawText,
        presetId = presetId,
        groupId = groupId,
        manualColors = manualColors.toMutableList(),
        randomSchemes = randomSchemes.map { it.toMutableList() }.toMutableList(),
        selectedSchemeIndex = selectedSchemeIndex,
        createdAt = createdAt,
    )
}

data class TagColorProfile(
    val tagId: String,
    var palette: MutableList<String> = mutableListOf(),
    var updatedAt: Long = System.currentTimeMillis(),
) {
    fun copyDeep(): TagColorProfile = TagColorProfile(
        tagId = tagId,
        palette = palette.toMutableList(),
        updatedAt = updatedAt,
    )
}

class TagProgress {
    val buffLevels: MutableMap<String, Int> = LinkedHashMap()
    val activeBuffs: MutableSet<String> = LinkedHashSet()
    val ownedParticles: MutableSet<String> = LinkedHashSet()
    var selectedParticleId: String? = null

    fun copyDeep(): TagProgress {
        val copy = TagProgress()
        copy.buffLevels.putAll(buffLevels)
        copy.activeBuffs.addAll(activeBuffs)
        copy.ownedParticles.addAll(ownedParticles)
        copy.selectedParticleId = selectedParticleId
        return copy
    }
}

class PlayerTagData(val uniqueId: UUID) {
    val ownedTags: MutableSet<String> = LinkedHashSet()
    val tagProgress: MutableMap<String, TagProgress> = LinkedHashMap()
    val tagColorOverrides: MutableMap<String, TagColorProfile> = LinkedHashMap()
    var equippedTagId: String? = null
    var titleCoinBalance: Double = 0.0
    var titleCoinInitialized: Boolean = false
    val customTitles: MutableMap<String, CustomTitleData> = LinkedHashMap()
    var equippedCustomTitleId: String? = null

    fun copyDeep(): PlayerTagData {
        val copy = PlayerTagData(uniqueId)
        copy.ownedTags.addAll(ownedTags)
        copy.equippedTagId = equippedTagId
        copy.titleCoinBalance = titleCoinBalance
        copy.titleCoinInitialized = titleCoinInitialized
        copy.equippedCustomTitleId = equippedCustomTitleId
        tagProgress.forEach { (key, value) -> copy.tagProgress[key] = value.copyDeep() }
        tagColorOverrides.forEach { (key, value) -> copy.tagColorOverrides[key] = value.copyDeep() }
        customTitles.forEach { (key, value) -> copy.customTitles[key] = value.copyDeep() }
        return copy
    }
}
