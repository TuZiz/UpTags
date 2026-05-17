package cn.aing.uptags.repository.store

import cn.aing.uptags.model.config.CurrencyType
import cn.aing.uptags.model.runtime.CustomTitleData
import cn.aing.uptags.model.runtime.CustomTitleOrderStatus
import cn.aing.uptags.model.runtime.CustomTitlePurchaseOrderData
import cn.aing.uptags.model.runtime.PlayerTagData
import cn.aing.uptags.model.runtime.TagColorProfile
import cn.aing.uptags.model.runtime.TagProgress
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class PlayerDataCodecUnicodeRoundTripTest {
    @Test
    fun roundTripsUnicodeEmojiAndSeparatorCharacters() {
        val uniqueId = UUID.randomUUID()
        val separatorText = "A|B,C:D;E#F~G"
        val data = PlayerTagData(uniqueId).apply {
            ownedTags += "vip|桜"
            equippedTagId = "tag,귀여운"
            titleCoinBalance = 42.5
            titleCoinInitialized = true
            tagProgress["tag:semicolon"] = TagProgress().apply {
                selectedParticleId = "星空★旅人"
                ownedParticles += "A|B"
                activeBuffs += "C:D"
                buffLevels["E#F~G"] = 3
            }
            customTitles["custom-unicode"] = CustomTitleData(
                id = "custom-unicode",
                rawText = "$separatorText🐉龍⭐勇者🏳️🌈夢・幻귀여운칭호",
                presetId = "unicode",
                groupId = "starter|group",
                manualColors = mutableListOf("#FFFFFF", "#000000"),
                randomSchemes = mutableListOf(mutableListOf("#123456", "#654321")),
                selectedSchemeIndex = 0,
                createdAt = 123L,
            )
            customTitleOrders["order-1"] = CustomTitlePurchaseOrderData(
                orderId = "order-1",
                titleId = "custom-unicode",
                rawText = "桜咲く|귀여운칭호",
                presetId = "unicode",
                groupId = "starter",
                currencyType = CurrencyType.MONEY,
                currencyAmount = 12.0,
                status = CustomTitleOrderStatus.REFUND_PENDING,
                createdAt = 100L,
                updatedAt = 200L,
                failureReason = "save failed: $separatorText",
                previousEquippedTagId = "old|tag",
                previousEquippedCustomTitleId = "old,custom",
            )
            tagColorOverrides["tag:semicolon"] = TagColorProfile(
                tagId = "tag:semicolon",
                palette = mutableListOf("#ABCDEF", "#FEDCBA"),
                updatedAt = 456L,
            )
            equippedCustomTitleId = "custom-unicode"
        }

        val serialized = PlayerDataCodec.serialize(data)
        val decoded = PlayerDataCodec.deserialize(uniqueId, serialized)

        assertTrue(serialized.startsWith("schema_version=2###"))
        assertEquals(data.ownedTags, decoded.ownedTags)
        assertEquals(data.equippedTagId, decoded.equippedTagId)
        assertEquals(data.titleCoinBalance, decoded.titleCoinBalance)
        assertEquals(data.titleCoinInitialized, decoded.titleCoinInitialized)
        assertEquals(data.equippedCustomTitleId, decoded.equippedCustomTitleId)
        assertEquals(data.customTitles["custom-unicode"]?.rawText, decoded.customTitles["custom-unicode"]?.rawText)
        assertEquals(data.customTitles["custom-unicode"]?.groupId, decoded.customTitles["custom-unicode"]?.groupId)
        assertEquals(data.tagProgress["tag:semicolon"]?.buffLevels, decoded.tagProgress["tag:semicolon"]?.buffLevels)
        assertEquals(data.tagColorOverrides["tag:semicolon"]?.palette, decoded.tagColorOverrides["tag:semicolon"]?.palette)
        assertEquals(
            data.customTitleOrders["order-1"]?.failureReason,
            decoded.customTitleOrders["order-1"]?.failureReason,
        )
        assertEquals(CustomTitleOrderStatus.REFUND_PENDING, decoded.customTitleOrders["order-1"]?.status)
    }
}
