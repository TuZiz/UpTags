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

    fun copyDeep(): PlayerTagData {
        val copy = PlayerTagData(uniqueId)
        copy.ownedTags.addAll(ownedTags)
        copy.equippedTagId = equippedTagId
        tagProgress.forEach { (key, value) -> copy.tagProgress[key] = value.copyDeep() }
        return copy
    }
}
