package cn.aing.uptags.service

import cn.aing.uptags.config.ConfigRegistry
import cn.aing.uptags.config.MessageService
import cn.aing.uptags.model.config.CostDefinition
import cn.aing.uptags.model.config.CurrencyType
import cn.aing.uptags.model.runtime.PlayerTagData
import cn.aing.uptags.repository.SaveResult
import cn.aing.uptags.service.economy.EconomyBridge
import cn.aing.uptags.service.shop.ChallengeProgressService
import cn.aing.uptags.service.shop.ShopService
import cn.aing.uptags.service.tag.TagService
import cn.aing.uptags.service.title.CustomTitleService
import cn.aing.uptags.model.config.ItemTemplate
import cn.aing.uptags.model.config.ShopProductDefinition
import cn.aing.uptags.model.config.ShopProductMode
import cn.aing.uptags.model.config.ShopProductType
import cn.aing.uptags.model.config.SubmitItemDefinition
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.bukkit.Material
import org.bukkit.entity.Player
import org.bukkit.inventory.ItemStack
import org.bukkit.inventory.PlayerInventory
import java.util.UUID

class ShopServiceTest {
    @Test
    fun adminCanSeeProductsEvenWhenConditionsAreNotMet() {
        val player = mockk<Player>()
        val config = mockk<ConfigRegistry>()
        val tagService = mockk<TagService>()
        val customTitleService = mockk<CustomTitleService>(relaxed = true)
        val economy = mockk<EconomyBridge>(relaxed = true)
        val messages = mockk<MessageService>(relaxed = true)
        val product = ShopProductDefinition(
            id = "miner_soul",
            type = ShopProductType.TAG,
            targetId = "miner_soul",
            enabled = true,
            permission = null,
            conditions = listOf("%statistic_mine_block%>=256"),
            cost = CostDefinition(),
            icon = ItemTemplate("NAME_TAG", "Miner Soul", emptyList()),
        )

        every { config.shopProducts } returns linkedMapOf(product.id to product)
        every { player.hasPermission("uptags.admin") } returns true

        val service = ShopService(config, tagService, customTitleService, economy, messages, mockk(relaxed = true))
        val visible = service.visibleProducts(player)

        assertEquals(listOf(product), visible)
        verify(exactly = 0) { tagService.checkConditions(player, product.conditions) }
    }

    @Test
    fun submitItemRequirementsUseChineseMaterialNames() {
        val config = mockk<ConfigRegistry>()
        val tagService = mockk<TagService>()
        val customTitleService = mockk<CustomTitleService>(relaxed = true)
        val economy = mockk<EconomyBridge>(relaxed = true)
        val messages = mockk<MessageService>(relaxed = true)
        val product = ShopProductDefinition(
            id = "cave_lighter",
            type = ShopProductType.TAG,
            targetId = "cave_lighter",
            enabled = true,
            permission = null,
            conditions = emptyList(),
            cost = CostDefinition(),
            submitItems = listOf(SubmitItemDefinition("TORCH", 128)),
            icon = ItemTemplate("NAME_TAG", "Cave Lighter", emptyList()),
        )

        val service = ShopService(config, tagService, customTitleService, economy, messages, mockk(relaxed = true))
        val display = service.requirementDisplay(product)

        assertEquals("128x 火把", display)
    }

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
        every { player.hasPermission(any<String>()) } returns false
        every { tagService.checkConditions(player, product.conditions) } returns true
        every { economy.isAvailable(CurrencyType.TITLE_COIN) } returns true
        every { economy.balance(player, CurrencyType.TITLE_COIN) } returns 100.0
        every { customTitleService.startProductDraft(player, "basic", CurrencyType.TITLE_COIN, 5.0, "custom_basic") } returns true

        val service = ShopService(config, tagService, customTitleService, economy, messages, mockk(relaxed = true))
        val started = service.startCustomFlow(player, product.id)

        assertTrue(started)
        verify(exactly = 1) { customTitleService.startProductDraft(player, "basic", CurrencyType.TITLE_COIN, 5.0, "custom_basic") }
        verify(exactly = 0) { economy.withdraw(any(), any(), any()) }
    }

    @Test
    fun tagProductCanUnlockBySubmittingItemsWithoutChargingCurrency() {
        val player = mockk<Player>()
        val inventory = mockk<PlayerInventory>()
        val config = mockk<ConfigRegistry>()
        val tagService = mockk<TagService>()
        val customTitleService = mockk<CustomTitleService>(relaxed = true)
        val economy = mockk<EconomyBridge>(relaxed = true)
        val messages = mockk<MessageService>(relaxed = true)
        val contents = arrayOf<ItemStack?>(
            ItemStack(Material.BREAD, 20),
            ItemStack(Material.BREAD, 20),
        )
        val product = ShopProductDefinition(
            id = "bread_guard",
            type = ShopProductType.TAG,
            targetId = "bread_guard",
            enabled = true,
            permission = null,
            conditions = emptyList(),
            cost = CostDefinition(),
            submitItems = listOf(SubmitItemDefinition("BREAD", 32)),
            icon = ItemTemplate("NAME_TAG", "Bread Guard", emptyList()),
        )

        every { player.inventory } returns inventory
        every { inventory.storageContents } returns contents
        every { inventory.storageContents = any() } answers {
            val updated = firstArg<Array<ItemStack?>>()
            contents.indices.forEach { contents[it] = updated[it] }
        }
        every { inventory.addItem(any()) } returns hashMapOf()
        every { config.shopProducts } returns linkedMapOf(product.id to product)
        every { player.hasPermission(any<String>()) } returns false
        every { tagService.checkConditions(player, product.conditions) } returns true
        every { tagService.isOwned(player, product.targetId) } returns false
        every { tagService.recordPurchaseOrderStrict(player, any(), any()) } answers {
            thirdArg<(SaveResult) -> Unit>().invoke(SaveResult.Success(1L, 1L))
        }
        every { tagService.grantTagNoSave(player, product.targetId) } returns true
        every { tagService.data(player) } returns PlayerTagData(UUID.randomUUID())
        every { tagService.saveStrict(any(), any()) } answers {
            secondArg<(SaveResult) -> Unit>().invoke(SaveResult.Success(2L, 2L))
        }
        every { tagService.tagName(product.targetId) } returns "Bread Guard"

        val service = ShopService(config, tagService, customTitleService, economy, messages, mockk(relaxed = true))
        val bought = service.buy(player, product.id)

        assertTrue(bought)
        assertEquals(0, contents[0]?.amount ?: 0)
        assertEquals(8, contents[1]?.amount)
        verify(exactly = 0) { economy.withdraw(any(), any(), any()) }
        verify(exactly = 1) { tagService.grantTagNoSave(player, "bread_guard") }
    }

    @Test
    fun failedGrantRestoresSubmittedItemsAndRefundsCurrency() {
        val player = mockk<Player>()
        val inventory = mockk<PlayerInventory>()
        val config = mockk<ConfigRegistry>()
        val tagService = mockk<TagService>()
        val customTitleService = mockk<CustomTitleService>(relaxed = true)
        val economy = mockk<EconomyBridge>()
        val messages = mockk<MessageService>(relaxed = true)
        val contents = arrayOf<ItemStack?>(ItemStack(Material.BREAD, 40))
        val product = ShopProductDefinition(
            id = "bread_guard",
            type = ShopProductType.TAG,
            targetId = "bread_guard",
            enabled = true,
            permission = null,
            conditions = emptyList(),
            cost = CostDefinition(type = CurrencyType.POINTS, amount = 10.0),
            submitItems = listOf(SubmitItemDefinition("BREAD", 32)),
            icon = ItemTemplate("NAME_TAG", "Bread Guard", emptyList()),
        )

        every { player.inventory } returns inventory
        every { inventory.storageContents } returns contents
        every { inventory.storageContents = any() } answers {
            val updated = firstArg<Array<ItemStack?>>()
            contents.indices.forEach { contents[it] = updated[it] }
        }
        every { inventory.addItem(any()) } returns hashMapOf()
        every { config.shopProducts } returns linkedMapOf(product.id to product)
        every { player.hasPermission(any<String>()) } returns false
        every { tagService.checkConditions(player, product.conditions) } returns true
        every { tagService.isOwned(player, product.targetId) } returns false
        every { tagService.recordPurchaseOrderStrict(player, any(), any()) } answers {
            thirdArg<(SaveResult) -> Unit>().invoke(SaveResult.Success(1L, 1L))
        }
        every { tagService.grantTagNoSave(player, product.targetId) } returns false
        every { economy.isAvailable(CurrencyType.POINTS) } returns true
        every { economy.balance(player, CurrencyType.POINTS) } returns 100.0
        every { economy.withdraw(player, CurrencyType.POINTS, 10.0) } returns true
        every { economy.refund(player, CurrencyType.POINTS, 10.0) } returns true

        val service = ShopService(config, tagService, customTitleService, economy, messages, mockk(relaxed = true))
        val bought = service.buy(player, product.id)

        assertTrue(bought)
        assertEquals(8, contents[0]?.amount)
        verify(exactly = 1) { economy.refund(player, CurrencyType.POINTS, 10.0) }
    }

    @Test
    fun missingSubmitItemsDoesNotWithdrawCurrency() {
        val player = mockk<Player>()
        val inventory = mockk<PlayerInventory>()
        val config = mockk<ConfigRegistry>()
        val tagService = mockk<TagService>()
        val customTitleService = mockk<CustomTitleService>(relaxed = true)
        val economy = mockk<EconomyBridge>(relaxed = true)
        val messages = mockk<MessageService>(relaxed = true)
        val product = ShopProductDefinition(
            id = "bread_guard",
            type = ShopProductType.TAG,
            targetId = "bread_guard",
            enabled = true,
            permission = null,
            conditions = emptyList(),
            cost = CostDefinition(type = CurrencyType.POINTS, amount = 10.0),
            submitItems = listOf(SubmitItemDefinition("BREAD", 32)),
            icon = ItemTemplate("NAME_TAG", "Bread Guard", emptyList()),
        )

        every { player.inventory } returns inventory
        every { inventory.storageContents } returns arrayOf(ItemStack(Material.BREAD, 1))
        every { config.shopProducts } returns linkedMapOf(product.id to product)
        every { player.hasPermission(any<String>()) } returns false
        every { tagService.checkConditions(player, product.conditions) } returns true
        every { tagService.isOwned(player, product.targetId) } returns false
        every { economy.isAvailable(CurrencyType.POINTS) } returns true
        every { economy.balance(player, CurrencyType.POINTS) } returns 100.0

        val service = ShopService(config, tagService, customTitleService, economy, messages, mockk(relaxed = true))
        val accepted = service.buy(player, product.id)

        assertFalse(accepted)
        verify(exactly = 0) { economy.withdraw(any(), any(), any()) }
        verify(exactly = 0) { tagService.recordPurchaseOrderStrict(any(), any(), any()) }
    }

    @Test
    fun challengeClaimRequiresChallengeProgress() {
        val player = mockk<Player>()
        val config = mockk<ConfigRegistry>()
        val tagService = mockk<TagService>()
        val customTitleService = mockk<CustomTitleService>(relaxed = true)
        val economy = mockk<EconomyBridge>(relaxed = true)
        val messages = mockk<MessageService>(relaxed = true)
        val challenge = mockk<ChallengeProgressService>()
        val product = ShopProductDefinition(
            id = "miner",
            type = ShopProductType.TAG,
            targetId = "miner",
            mode = ShopProductMode.CHALLENGE_CLAIM,
            enabled = true,
            permission = null,
            conditions = listOf("challenge:statistic:mine_block:10"),
            cost = CostDefinition(),
            icon = ItemTemplate("NAME_TAG", "Miner", emptyList()),
        )

        every { config.shopProducts } returns linkedMapOf(product.id to product)
        every { player.uniqueId } returns java.util.UUID.randomUUID()
        every { player.hasPermission(any<String>()) } returns false
        every { tagService.checkConditions(player, product.conditions) } returns true
        every { tagService.isOwned(player, product.targetId) } returns false
        every { challenge.canClaim(player, product.conditions) } returns false

        val service = ShopService(config, tagService, customTitleService, economy, messages, challenge)

        assertFalse(service.buy(player, product.id))
    }
}
