package cn.aing.uptags.model.runtime

import cn.aing.uptags.model.config.CurrencyType
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

enum class CustomTitleOrderStatus {
    PENDING,
    COMPLETED,
    FAILED,
    REFUND_PENDING,
    REFUNDED;

    companion object {
        fun from(raw: String?): CustomTitleOrderStatus {
            if (raw.isNullOrBlank()) {
                return PENDING
            }
            return entries.firstOrNull { it.name.equals(raw.trim(), ignoreCase = true) } ?: PENDING
        }
    }
}

enum class PurchaseOrderStatus {
    PENDING,
    ITEMS_TAKEN,
    PAID,
    GRANTING,
    GRANTED,
    FAILED,
    REFUND_PENDING,
    REFUNDED;

    companion object {
        fun from(raw: String?): PurchaseOrderStatus {
            if (raw.isNullOrBlank()) {
                return PENDING
            }
            return entries.firstOrNull { it.name.equals(raw.trim(), ignoreCase = true) } ?: PENDING
        }
    }
}

data class PurchaseOrderData(
    val orderId: String,
    val productId: String,
    val targetId: String,
    var status: PurchaseOrderStatus,
    var currencyType: CurrencyType,
    var currencyAmount: Double,
    var submittedItems: MutableList<String> = mutableListOf(),
    var compensatedItems: MutableList<String> = mutableListOf(),
    var createdAt: Long = System.currentTimeMillis(),
    var updatedAt: Long = createdAt,
    var failureReason: String? = null,
) {
    fun copyDeep(): PurchaseOrderData = copy(
        submittedItems = submittedItems.toMutableList(),
        compensatedItems = compensatedItems.toMutableList(),
    )
}

class ChallengeProgressData {
    val values: MutableMap<String, Long> = LinkedHashMap()
    var lastMoveSampleAt: Long = 0L
    var lastX: Double? = null
    var lastY: Double? = null
    var lastZ: Double? = null
    var lastWorld: String? = null

    fun copyDeep(): ChallengeProgressData {
        val copy = ChallengeProgressData()
        copy.values.putAll(values)
        copy.lastMoveSampleAt = lastMoveSampleAt
        copy.lastX = lastX
        copy.lastY = lastY
        copy.lastZ = lastZ
        copy.lastWorld = lastWorld
        return copy
    }
}

data class CustomTitlePurchaseOrderData(
    val orderId: String,
    val titleId: String,
    var rawText: String,
    var presetId: String,
    var groupId: String?,
    var currencyType: CurrencyType,
    var currencyAmount: Double,
    var status: CustomTitleOrderStatus,
    var createdAt: Long = System.currentTimeMillis(),
    var updatedAt: Long = createdAt,
    var failureReason: String? = null,
    var previousEquippedTagId: String? = null,
    var previousEquippedCustomTitleId: String? = null,
) {
    fun copyDeep(): CustomTitlePurchaseOrderData = copy()
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
    val customTitleOrders: MutableMap<String, CustomTitlePurchaseOrderData> = LinkedHashMap()
    val purchaseOrders: MutableMap<String, PurchaseOrderData> = LinkedHashMap()
    val challengeProgress: ChallengeProgressData = ChallengeProgressData()
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
        customTitleOrders.forEach { (key, value) -> copy.customTitleOrders[key] = value.copyDeep() }
        purchaseOrders.forEach { (key, value) -> copy.purchaseOrders[key] = value.copyDeep() }
        copy.challengeProgress.values.putAll(challengeProgress.values)
        copy.challengeProgress.lastMoveSampleAt = challengeProgress.lastMoveSampleAt
        copy.challengeProgress.lastX = challengeProgress.lastX
        copy.challengeProgress.lastY = challengeProgress.lastY
        copy.challengeProgress.lastZ = challengeProgress.lastZ
        copy.challengeProgress.lastWorld = challengeProgress.lastWorld
        return copy
    }
}
