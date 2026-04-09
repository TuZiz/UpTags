package cn.aing.uptags.service

import cn.aing.uptags.config.ConfigRegistry
import cn.aing.uptags.config.MessageService
import cn.aing.uptags.model.config.CostDefinition
import cn.aing.uptags.model.config.CurrencyType
import cn.aing.uptags.model.config.ItemTemplate
import cn.aing.uptags.model.config.ShopProductDefinition
import cn.aing.uptags.model.config.ShopProductType
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlin.test.Test
import kotlin.test.assertTrue
import org.bukkit.entity.Player

class ShopServiceTest {
    @Test
    fun customProductStartsDraftWithoutImmediateCharge() {
        val player = mockk<Player>()
        val config = mockk<ConfigRegistry>()
        val tagService = mockk<TagService>()
        val customTitleService = mockk<CustomTitleService>(relaxed = true)
        val economy = mockk<EconomyBridge>(relaxed = true)
        val messages = mockk<MessageService>(relaxed = true)
        val product = ShopProductDefinition(
            id = "custom_basic",
            type = ShopProductType.CUSTOM,
            targetId = "basic",
            enabled = true,
            permission = null,
            conditions = emptyList(),
            cost = CostDefinition(type = CurrencyType.TITLE_COIN, amount = 5.0),
            icon = ItemTemplate("BOOK", "Basic", listOf("Start custom title flow")),
        )

        every { config.shopProducts } returns linkedMapOf(product.id to product)
        every { tagService.checkConditions(player, product.conditions) } returns true
        every { economy.isAvailable(CurrencyType.TITLE_COIN) } returns true
        every { economy.balance(player, CurrencyType.TITLE_COIN) } returns 100.0
        every { customTitleService.startProductDraft(player, "basic", CurrencyType.TITLE_COIN, 5.0, "custom_basic") } returns true

        val service = ShopService(config, tagService, customTitleService, economy, messages)
        val started = service.startCustomFlow(player, product.id)

        assertTrue(started)
        verify(exactly = 1) { customTitleService.startProductDraft(player, "basic", CurrencyType.TITLE_COIN, 5.0, "custom_basic") }
        verify(exactly = 0) { economy.withdraw(any(), any(), any()) }
    }
}
