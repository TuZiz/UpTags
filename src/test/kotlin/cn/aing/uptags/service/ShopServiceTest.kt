package cn.aing.uptags.service

import cn.aing.uptags.config.ConfigRegistry
import cn.aing.uptags.config.MessageService
import cn.aing.uptags.compat.PlatformScheduler
import cn.aing.uptags.compat.TaskHandle
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
import cn.aing.uptags.model.runtime.PurchaseOrderStatus
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

        val service = ShopService(config, tagService, customTitleService, economy, messages, mockk(relaxed = true), immediateScheduler())
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
        stubShopLocalization(config)
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

        val service = ShopService(config, tagService, customTitleService, economy, messages, mockk(relaxed = true), immediateScheduler())
        val display = service.requirementDisplay(product)

        assertEquals("128x 火把", display)
    }

    @Test
    fun playerRequirementDisplayShowsChallengeAndSubmitProgress() {
        val player = mockk<Player>()
        val inventory = mockk<PlayerInventory>()
        val config = mockk<ConfigRegistry>()
        val tagService = mockk<TagService>()
        val customTitleService = mockk<CustomTitleService>(relaxed = true)
        val economy = mockk<EconomyBridge>(relaxed = true)
        val messages = mockk<MessageService>(relaxed = true)
        val challenge = mockk<ChallengeProgressService>()
        stubShopLocalization(config)
        val product = ShopProductDefinition(
            id = "diamond_vein_master",
            type = ShopProductType.TAG,
            targetId = "diamond_vein_master",
            enabled = true,
            permission = null,
            conditions = listOf("challenge:mine:deepslate_diamond_ore:96"),
            cost = CostDefinition(),
            submitItems = listOf(SubmitItemDefinition("DIAMOND", 16)),
            icon = ItemTemplate("NAME_TAG", "Diamond", emptyList()),
        )

        every { player.inventory } returns inventory
        every { inventory.storageContents } returns arrayOf(ItemStack(Material.DIAMOND, 3))
        every { challenge.progress(player, "challenge:mine:deepslate_diamond_ore") } returns 12L

        val service = ShopService(config, tagService, customTitleService, economy, messages, challenge, immediateScheduler())

        assertEquals("挖掘深层钻石矿: 12/96\n钻石: 3/16", service.requirementDisplay(player, product))
    }

    @Test
    fun submitItemProgressCanUseIndexedLineTemplates() {
        val player = mockk<Player>()
        val inventory = mockk<PlayerInventory>()
        val config = mockk<ConfigRegistry>()
        val tagService = mockk<TagService>()
        val customTitleService = mockk<CustomTitleService>(relaxed = true)
        val economy = mockk<EconomyBridge>(relaxed = true)
        val messages = mockk<MessageService>(relaxed = true)
        stubShopLocalization(config)
        every { config.shopText("shop.requirement.item-progress-1") } returns "第一行 %item% %current%/%required%"
        every { config.shopText("shop.requirement.item-progress-2") } returns "第二行 %item% %current%/%required%"
        val product = ShopProductDefinition(
            id = "builder_pack",
            type = ShopProductType.TAG,
            targetId = "builder_pack",
            enabled = true,
            permission = null,
            conditions = emptyList(),
            cost = CostDefinition(),
            submitItems = listOf(
                SubmitItemDefinition("TORCH", 8),
                SubmitItemDefinition("BREAD", 4),
            ),
            icon = ItemTemplate("NAME_TAG", "Builder Pack", emptyList()),
        )

        every { player.inventory } returns inventory
        every { inventory.storageContents } returns arrayOf(
            ItemStack(Material.TORCH, 3),
            ItemStack(Material.BREAD, 1),
        )

        val service = ShopService(config, tagService, customTitleService, economy, messages, mockk(relaxed = true), immediateScheduler())

        assertEquals("第一行 火把 3/8\n第二行 面包 1/4", service.requirementDisplay(player, product))
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

        val service = ShopService(config, tagService, customTitleService, economy, messages, mockk(relaxed = true), immediateScheduler())
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
        every { player.isOnline } returns true
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

        val service = ShopService(config, tagService, customTitleService, economy, messages, mockk(relaxed = true), immediateScheduler())
        val bought = service.buy(player, product.id)

        assertTrue(bought)
        assertEquals(0, contents[0]?.amount ?: 0)
        assertEquals(8, contents[1]?.amount)
        verify(exactly = 0) { economy.withdraw(any(), any(), any()) }
        verify(exactly = 1) { tagService.grantTagNoSave(player, "bread_guard") }
    }

    @Test
    fun tagPurchasePersistsStrictStatusOrderBeforeSuccessMessage() {
        val player = mockk<Player>()
        val inventory = mockk<PlayerInventory>()
        val config = mockk<ConfigRegistry>()
        val tagService = mockk<TagService>()
        val customTitleService = mockk<CustomTitleService>(relaxed = true)
        val economy = mockk<EconomyBridge>()
        val messages = mockk<MessageService>(relaxed = true)
        val savedStatuses = mutableListOf<PurchaseOrderStatus>()
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
        val data = PlayerTagData(UUID.randomUUID())
        val contents = arrayOf<ItemStack?>(ItemStack(Material.BREAD, 40))

        every { player.inventory } returns inventory
        every { player.isOnline } returns true
        every { inventory.storageContents } returns contents
        every { inventory.storageContents = any() } answers {
            val updated = firstArg<Array<ItemStack?>>()
            contents.indices.forEach { contents[it] = updated[it] }
        }
        every { config.shopProducts } returns linkedMapOf(product.id to product)
        every { player.hasPermission(any<String>()) } returns false
        every { tagService.checkConditions(player, product.conditions) } returns true
        every { tagService.isOwned(player, product.targetId) } returns false
        every { tagService.recordPurchaseOrderStrict(player, any(), any()) } answers {
            savedStatuses += secondArg<cn.aing.uptags.model.runtime.PurchaseOrderData>().status
            thirdArg<(SaveResult) -> Unit>().invoke(SaveResult.Success(savedStatuses.size.toLong(), savedStatuses.size.toLong()))
        }
        every { tagService.data(player) } returns data
        every { tagService.saveStrict(any(), any()) } answers {
            val order = data.purchaseOrders.values.single()
            savedStatuses += order.status
            secondArg<(SaveResult) -> Unit>().invoke(SaveResult.Success(savedStatuses.size.toLong(), savedStatuses.size.toLong()))
        }
        every { tagService.grantTagNoSave(player, product.targetId) } answers {
            data.ownedTags += product.targetId
            true
        }
        every { tagService.tagName(product.targetId) } returns "Bread Guard"
        every { economy.isAvailable(CurrencyType.POINTS) } returns true
        every { economy.balance(player, CurrencyType.POINTS) } returns 100.0
        every { economy.withdraw(player, CurrencyType.POINTS, 10.0) } returns true

        val service = ShopService(config, tagService, customTitleService, economy, messages, mockk(relaxed = true), immediateScheduler())

        assertTrue(service.buy(player, product.id))
        assertEquals(
            listOf(
                PurchaseOrderStatus.PENDING,
                PurchaseOrderStatus.ITEMS_TAKEN,
                PurchaseOrderStatus.PAID,
                PurchaseOrderStatus.GRANTING,
                PurchaseOrderStatus.GRANTED,
            ),
            savedStatuses,
        )
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
        every { player.isOnline } returns true
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

        val service = ShopService(config, tagService, customTitleService, economy, messages, mockk(relaxed = true), immediateScheduler())
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
        stubShopLocalization(config)
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
        every { player.isOnline } returns true
        every { inventory.storageContents } returns arrayOf(ItemStack(Material.BREAD, 1))
        every { config.shopProducts } returns linkedMapOf(product.id to product)
        every { player.hasPermission(any<String>()) } returns false
        every { tagService.checkConditions(player, product.conditions) } returns true
        every { tagService.isOwned(player, product.targetId) } returns false
        every { economy.isAvailable(CurrencyType.POINTS) } returns true
        every { economy.balance(player, CurrencyType.POINTS) } returns 100.0

        val service = ShopService(config, tagService, customTitleService, economy, messages, mockk(relaxed = true), immediateScheduler())
        val accepted = service.buy(player, product.id)

        assertFalse(accepted)
        verify(exactly = 0) { economy.withdraw(any(), any(), any()) }
        verify(exactly = 0) { tagService.recordPurchaseOrderStrict(any(), any(), any()) }
    }

    @Test
    fun visibleProductsSplitPlaceholderAndChallengeConditions() {
        val player = mockk<Player>()
        val config = mockk<ConfigRegistry>()
        val tagService = mockk<TagService>()
        val customTitleService = mockk<CustomTitleService>(relaxed = true)
        val economy = mockk<EconomyBridge>(relaxed = true)
        val messages = mockk<MessageService>(relaxed = true)
        val challenge = mockk<ChallengeProgressService>()
        val regularCondition = "%server_season%==winter"
        val challengeCondition = "challenge:kill:warden:1"
        val product = ShopProductDefinition(
            id = "winter_warden",
            type = ShopProductType.TAG,
            targetId = "winter_warden",
            mode = ShopProductMode.SEASONAL,
            enabled = true,
            permission = null,
            conditions = listOf(regularCondition, challengeCondition),
            cost = CostDefinition(),
            icon = ItemTemplate("NAME_TAG", "Winter Warden", emptyList()),
        )

        every { config.shopProducts } returns linkedMapOf(product.id to product)
        every { player.hasPermission(any<String>()) } returns false
        every { tagService.checkConditions(player, listOf(regularCondition)) } returns true
        every { challenge.canClaim(player, listOf(challengeCondition), any()) } returns true

        val service = ShopService(config, tagService, customTitleService, economy, messages, challenge, immediateScheduler())

        assertEquals(listOf(product), service.visibleProducts(player))
        verify(exactly = 1) { tagService.checkConditions(player, listOf(regularCondition)) }
        verify(exactly = 1) { challenge.canClaim(player, listOf(challengeCondition), any()) }
        verify(exactly = 0) { tagService.checkConditions(player, product.conditions) }
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
        every { tagService.isOwned(player, product.targetId) } returns false
        every { challenge.canClaim(player, product.conditions, any()) } returns false

        val service = ShopService(config, tagService, customTitleService, economy, messages, challenge, immediateScheduler())

        assertFalse(service.buy(player, product.id))
        verify(exactly = 0) { tagService.checkConditions(player, product.conditions) }
    }

    private fun immediateScheduler(): PlatformScheduler {
        val scheduler = mockk<PlatformScheduler>()
        val handle = mockk<TaskHandle>(relaxed = true)
        every { scheduler.runPlayerOrRetired(any(), any(), any()) } answers {
            thirdArg<() -> Unit>().invoke()
            handle
        }
        return scheduler
    }

    private fun stubShopLocalization(config: ConfigRegistry) {
        every { config.displayName(any()) } answers {
            when (val raw = firstArg<String>().trim().uppercase()) {
                "BREAD" -> "面包"
                "DEEPSLATE_DIAMOND_ORE" -> "深层钻石矿"
                "DIAMOND" -> "钻石"
                "TORCH" -> "火把"
                else -> raw
            }
        }
        every { config.shopText(any()) } answers {
            when (firstArg<String>()) {
                "shop.requirement.separator" -> "\n"
                "shop.requirement.inline-separator" -> ", "
                "shop.requirement.empty" -> "完成后领取"
                "shop.requirement.conditions" -> "完成条件"
                "shop.challenge.deep-dark-stay" -> "深暗停留"
                "shop.challenge.advancement" -> "完成进度"
                else -> firstArg<String>()
            }
        }
        every { config.shopText(any(), any<Map<String, String>>()) } answers {
            val pattern = when (firstArg<String>()) {
                "shop.requirement.price" -> "%amount% %currency%"
                "shop.requirement.item" -> "%amount%x %item%"
                "shop.requirement.item-progress" -> "%item%: %current%/%required%"
                "shop.requirement.progress" -> "%name%: %current%/%required%"
                "shop.challenge.mine" -> "挖掘%target%"
                "shop.challenge.collect" -> "收集%target%"
                "shop.challenge.biome" -> "到达%target%"
                "shop.challenge.world" -> "进入%target%"
                "shop.challenge.kill" -> "击杀%target%"
                "shop.challenge.height" -> "%target%高度"
                "shop.challenge.default" -> "完成%target%"
                else -> firstArg<String>()
            }
            secondArg<Map<String, String>>().entries.fold(pattern) { text, (key, value) ->
                text.replace("%$key%", value)
            }
        }
    }
}
