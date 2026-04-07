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

data class ScrollSelectionContext(
    val scrollKey: String,
    val kind: ScrollKind,
    val targetId: String,
    val hand: EquipmentSlot,
)

data class CustomTitleData(
    val id: String,
    var rawText: String,
    var presetId: String,
    var manualColors: MutableList<String> = mutableListOf(),
    var randomSchemes: MutableList<MutableList<String>> = mutableListOf(),
    var selectedSchemeIndex: Int = 0,
    var createdAt: Long = System.currentTimeMillis(),
) {
    fun copyDeep(): CustomTitleData = CustomTitleData(
        id = id,
        rawText = rawText,
        presetId = presetId,
        manualColors = manualColors.toMutableList(),
        randomSchemes = randomSchemes.map { it.toMutableList() }.toMutableList(),
        selectedSchemeIndex = selectedSchemeIndex,
        createdAt = createdAt,
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
        customTitles.forEach { (key, value) -> copy.customTitles[key] = value.copyDeep() }
        return copy
    }
}
