package cn.aing.uptags.repository.store

import cn.aing.uptags.model.config.CurrencyType
import cn.aing.uptags.model.runtime.PlayerTagData
import cn.aing.uptags.model.runtime.PurchaseOrderData
import cn.aing.uptags.model.runtime.PurchaseOrderStatus
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals

class PlayerDataCodecChallengeProgressTest {
    @Test
    fun challengeProgressAndPurchaseOrdersSurviveRoundTrip() {
        val uniqueId = UUID.randomUUID()
        val data = PlayerTagData(uniqueId)
        data.challengeProgress.values["challenge:mine:deepslate_diamond_ore"] = 64L
        data.challengeProgress.values["challenge:world:the_nether"] = 1L
        data.challengeProgress.lastMoveSampleAt = 1234L
        data.challengeProgress.lastWorld = "world"
        data.challengeProgress.lastX = 1.0
        data.challengeProgress.lastY = 65.0
        data.challengeProgress.lastZ = -3.0
        data.purchaseOrders["order-1"] = PurchaseOrderData(
            orderId = "order-1",
            productId = "diamond_vein_master",
            targetId = "diamond_vein_master",
            status = PurchaseOrderStatus.GRANTED,
            currencyType = CurrencyType.POINTS,
            currencyAmount = 10.0,
            submittedItems = mutableListOf("32xbread"),
            compensatedItems = mutableListOf("1xbread"),
        )

        val decoded = PlayerDataCodec.deserialize(uniqueId, PlayerDataCodec.serialize(data))

        assertEquals(64L, decoded.challengeProgress.values["challenge:mine:deepslate_diamond_ore"])
        assertEquals(1L, decoded.challengeProgress.values["challenge:world:the_nether"])
        assertEquals(1234L, decoded.challengeProgress.lastMoveSampleAt)
        assertEquals("world", decoded.challengeProgress.lastWorld)
        assertEquals(PurchaseOrderStatus.GRANTED, decoded.purchaseOrders["order-1"]?.status)
        assertEquals(listOf("32xbread"), decoded.purchaseOrders["order-1"]?.submittedItems?.toList())
        assertEquals(listOf("1xbread"), decoded.purchaseOrders["order-1"]?.compensatedItems?.toList())
    }

    @Test
    fun mainTableSerializationCanOmitOrdersWhileKeepingCurrentState() {
        val uniqueId = UUID.randomUUID()
        val data = PlayerTagData(uniqueId).apply {
            ownedTags += "vip"
            equippedTagId = "vip"
            challengeProgress.values["challenge:stat:walk_one_cm"] = 100L
            purchaseOrders["order-1"] = PurchaseOrderData(
                orderId = "order-1",
                productId = "vip",
                targetId = "vip",
                status = PurchaseOrderStatus.PENDING,
                currencyType = CurrencyType.POINTS,
                currencyAmount = 10.0,
            )
        }

        val decoded = PlayerDataCodec.deserialize(uniqueId, PlayerDataCodec.serialize(data, includeOrders = false))

        assertEquals(setOf("vip"), decoded.ownedTags)
        assertEquals("vip", decoded.equippedTagId)
        assertEquals(100L, decoded.challengeProgress.values["challenge:stat:walk_one_cm"])
        assertEquals(emptyMap(), decoded.purchaseOrders)
        assertEquals(emptyMap(), decoded.customTitleOrders)
    }
}
