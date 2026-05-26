package cn.aing.uptags.service

import cn.aing.uptags.UpTagsPlugin
import cn.aing.uptags.config.ConfigRegistry
import cn.aing.uptags.config.MessageService
import cn.aing.uptags.model.config.BuffDefinition
import cn.aing.uptags.model.config.CostDefinition
import cn.aing.uptags.model.config.CurrencyType
import cn.aing.uptags.model.config.DetachCostSettings
import cn.aing.uptags.model.config.DetachSettings
import cn.aing.uptags.model.config.ItemTemplate
import cn.aing.uptags.model.config.ParticleDefinition
import cn.aing.uptags.model.config.PluginSettings
import cn.aing.uptags.model.config.ScrollDefinition
import cn.aing.uptags.model.config.ShopProductDefinition
import cn.aing.uptags.model.config.ShopProductMode
import cn.aing.uptags.model.config.ShopProductType
import cn.aing.uptags.model.config.TagDefinition
import cn.aing.uptags.model.config.TitleCollectionCategoryDefinition
import cn.aing.uptags.model.config.TitleCollectionSettings
import cn.aing.uptags.model.config.UpgradeGroupDefinition
import cn.aing.uptags.model.runtime.CustomTitleData
import cn.aing.uptags.model.runtime.PlayerTagData
import cn.aing.uptags.model.runtime.ScrollKind
import cn.aing.uptags.model.runtime.TagProgress
import cn.aing.uptags.repository.PlayerDataRepository
import cn.aing.uptags.service.economy.EconomyBridge
import cn.aing.uptags.service.tag.TagService
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

class TagServiceTest {
    @Test
    fun preparePlayerKeepsEquippedOwnedTagWhenForceDefaultIsEnabled() {
        val playerId = UUID.randomUUID()
        val player = mockk<Player>()
        val plugin = mockk<UpTagsPlugin>(relaxed = true)
        val config = mockk<ConfigRegistry>()
        val repository = mockk<PlayerDataRepository>(relaxed = true)
        val economy = mockk<EconomyBridge>(relaxed = true)
        val messages = mockk<MessageService>(relaxed = true)
        val data = PlayerTagData(playerId).apply {
            ownedTags += "newbie"
            ownedTags += "vip"
            equippedTagId = "vip"
            tagProgress["newbie"] = TagProgress()
            tagProgress["vip"] = TagProgress()
        }

        every { player.uniqueId } returns playerId
        every { repository.get(playerId) } returns data
        every { config.settings } returns PluginSettings(
            effectTickInterval = 20L,
            forceDefaultTag = true,
            forcedTagId = "newbie",
        )
        every { config.tags } returns linkedMapOf(
            "newbie" to tag("newbie"),
            "vip" to tag("vip"),
        )

        val service = TagService(plugin, config, repository, economy, messages)
        service.preparePlayer(player, announce = false)

        assertEquals("vip", data.equippedTagId)
        verify(exactly = 0) { repository.saveAsync(data) }
    }

    @Test
    fun preparePlayerFallsBackToForcedTagWhenNoValidTagIsEquipped() {
        val playerId = UUID.randomUUID()
        val player = mockk<Player>()
        val plugin = mockk<UpTagsPlugin>(relaxed = true)
        val config = mockk<ConfigRegistry>()
        val repository = mockk<PlayerDataRepository>(relaxed = true)
        val economy = mockk<EconomyBridge>(relaxed = true)
        val messages = mockk<MessageService>(relaxed = true)
        val data = PlayerTagData(playerId)

        every { player.uniqueId } returns playerId
        every { repository.get(playerId) } returns data
        every { config.settings } returns PluginSettings(
            effectTickInterval = 20L,
            forceDefaultTag = true,
            forcedTagId = "newbie",
        )
        every { config.tags } returns linkedMapOf(
            "newbie" to tag("newbie"),
            "vip" to tag("vip"),
        )

        val service = TagService(plugin, config, repository, economy, messages)
        service.preparePlayer(player, announce = false)

        assertEquals("newbie", data.equippedTagId)
        verify(exactly = 1) { repository.saveAsync(data) }
    }

    @Test
    fun detachBuffWithMoneyReturnsSameLevelScroll() {
        val fixture = detachFixture()
        fixture.data.ownedTags += "vip"
        fixture.data.tagProgress["vip"] = cn.aing.uptags.model.runtime.TagProgress().apply {
            buffLevels["speed"] = 2
            activeBuffs += "speed"
        }
        every { fixture.economy.isAvailable(CurrencyType.MONEY) } returns true
        every { fixture.economy.balance(fixture.player, CurrencyType.MONEY) } returns 100.0
        every { fixture.economy.withdraw(fixture.player, CurrencyType.MONEY, 50.0) } returns true

        var createdScroll: Pair<String, Int>? = null
        fixture.service.attachScrollFactory { key, level ->
            createdScroll = key to level
            ItemStack(Material.PAPER)
        }

        assertTrue(fixture.service.detachBuff(fixture.player, "vip", "speed", CurrencyType.MONEY))
        assertEquals(null, fixture.data.tagProgress["vip"]?.buffLevels?.get("speed"))
        assertFalse("speed" in fixture.data.tagProgress["vip"]!!.activeBuffs)
        assertEquals("speed_scroll" to 2, createdScroll)
        verify { fixture.economy.withdraw(fixture.player, CurrencyType.MONEY, 50.0) }
        verify { fixture.inventory.addItem(any<ItemStack>()) }
    }

    @Test
    fun detachParticleClearsSelectedParticle() {
        val fixture = detachFixture()
        fixture.data.ownedTags += "vip"
        fixture.data.tagProgress["vip"] = cn.aing.uptags.model.runtime.TagProgress().apply {
            ownedParticles += "halo"
            selectedParticleId = "halo"
        }
        every { fixture.economy.isAvailable(CurrencyType.POINTS) } returns true
        every { fixture.economy.balance(fixture.player, CurrencyType.POINTS) } returns 100.0
        every { fixture.economy.withdraw(fixture.player, CurrencyType.POINTS, 25.0) } returns true

        var createdScroll: Pair<String, Int>? = null
        fixture.service.attachScrollFactory { key, level ->
            createdScroll = key to level
            ItemStack(Material.NETHER_STAR)
        }

        assertTrue(fixture.service.detachParticle(fixture.player, "vip", "halo", CurrencyType.POINTS))
        assertFalse("halo" in fixture.data.tagProgress["vip"]!!.ownedParticles)
        assertEquals(null, fixture.data.tagProgress["vip"]!!.selectedParticleId)
        assertEquals("halo_scroll" to 1, createdScroll)
        verify { fixture.economy.withdraw(fixture.player, CurrencyType.POINTS, 25.0) }
        verify { fixture.inventory.addItem(any<ItemStack>()) }
    }

    @Test
    fun levelScrollCannotOverflowBuffMaxLevel() {
        val fixture = detachFixture()
        fixture.data.ownedTags += "vip"
        fixture.data.tagProgress["vip"] = cn.aing.uptags.model.runtime.TagProgress().apply {
            buffLevels["speed"] = 2
        }

        assertFalse(fixture.service.grantBuffUpgrade(fixture.player, "vip", "speed", levels = 2))
        assertEquals(2, fixture.data.tagProgress["vip"]!!.buffLevels["speed"])

        assertTrue(fixture.service.grantBuffUpgrade(fixture.player, "vip", "speed", levels = 1))
        assertEquals(3, fixture.data.tagProgress["vip"]!!.buffLevels["speed"])
    }

    @Test
    fun adminSetBuffLevelZeroClearsLevelAndActiveState() {
        val fixture = detachFixture()
        fixture.data.ownedTags += "vip"
        fixture.data.tagProgress["vip"] = TagProgress().apply {
            buffLevels["speed"] = 2
            activeBuffs += "speed"
        }

        val result = fixture.service.adminSetBuffLevel(fixture.data.uniqueId, "vip", "speed", 0)

        assertTrue(result.success)
        assertEquals(null, fixture.data.tagProgress["vip"]?.buffLevels?.get("speed"))
        assertFalse("speed" in fixture.data.tagProgress["vip"]!!.activeBuffs)
    }

    @Test
    fun adminTakeParticleClearsSelectedParticle() {
        val fixture = detachFixture()
        fixture.data.ownedTags += "vip"
        fixture.data.tagProgress["vip"] = TagProgress().apply {
            ownedParticles += "halo"
            selectedParticleId = "halo"
        }

        val result = fixture.service.adminTakeParticle(fixture.data.uniqueId, "vip", "halo")

        assertTrue(result.success)
        assertFalse("halo" in fixture.data.tagProgress["vip"]!!.ownedParticles)
        assertEquals(null, fixture.data.tagProgress["vip"]!!.selectedParticleId)
    }

    @Test
    fun adminDeleteCustomTitleClearsEquippedAndProgress() {
        val fixture = detachFixture()
        fixture.data.customTitles["custom-1"] = CustomTitleData(
            id = "custom-1",
            rawText = "Hero",
            presetId = "basic",
        )
        fixture.data.tagProgress["custom-1"] = TagProgress()
        fixture.data.equippedCustomTitleId = "custom-1"

        val result = fixture.service.adminDeleteCustomTitle(fixture.data.uniqueId, "custom-1")

        assertTrue(result.success)
        assertFalse("custom-1" in fixture.data.customTitles)
        assertFalse("custom-1" in fixture.data.tagProgress)
        assertEquals(null, fixture.data.equippedCustomTitleId)
    }

    @Test
    fun adminDetachBuffReturnsScrollToExecutorWithoutChargingTarget() {
        val fixture = detachFixture()
        fixture.data.ownedTags += "vip"
        fixture.data.tagProgress["vip"] = TagProgress().apply {
            buffLevels["speed"] = 2
            activeBuffs += "speed"
        }
        var createdScroll: Pair<String, Int>? = null
        fixture.service.attachScrollFactory { key, level ->
            createdScroll = key to level
            ItemStack(Material.PAPER)
        }

        val result = fixture.service.adminDetachBuff(fixture.data.uniqueId, "vip", "speed", fixture.player)

        assertTrue(result.success)
        assertEquals("speed_scroll" to 2, createdScroll)
        assertEquals(null, fixture.data.tagProgress["vip"]?.buffLevels?.get("speed"))
        assertFalse("speed" in fixture.data.tagProgress["vip"]!!.activeBuffs)
        verify(exactly = 0) { fixture.economy.withdraw(any(), any(), any()) }
        verify { fixture.inventory.addItem(any<ItemStack>()) }
    }

    @Test
    fun adminDetachParticleReturnsScrollToExecutorAndClearsSelection() {
        val fixture = detachFixture()
        fixture.data.ownedTags += "vip"
        fixture.data.tagProgress["vip"] = TagProgress().apply {
            ownedParticles += "halo"
            selectedParticleId = "halo"
        }
        var createdScroll: Pair<String, Int>? = null
        fixture.service.attachScrollFactory { key, level ->
            createdScroll = key to level
            ItemStack(Material.NETHER_STAR)
        }

        val result = fixture.service.adminDetachParticle(fixture.data.uniqueId, "vip", "halo", fixture.player)

        assertTrue(result.success)
        assertEquals("halo_scroll" to 1, createdScroll)
        assertFalse("halo" in fixture.data.tagProgress["vip"]!!.ownedParticles)
        assertEquals(null, fixture.data.tagProgress["vip"]!!.selectedParticleId)
        verify(exactly = 0) { fixture.economy.withdraw(any(), any(), any()) }
        verify { fixture.inventory.addItem(any<ItemStack>()) }
    }

    @Test
    fun collectionSummaryGrantsHiddenRewardWhenCategoryIsCompleted() {
        val playerId = UUID.randomUUID()
        val player = mockk<Player>()
        val plugin = mockk<UpTagsPlugin>(relaxed = true)
        val config = mockk<ConfigRegistry>()
        val repository = mockk<PlayerDataRepository>(relaxed = true)
        val economy = mockk<EconomyBridge>(relaxed = true)
        val messages = mockk<MessageService>(relaxed = true)
        val data = PlayerTagData(playerId).apply {
            ownedTags += "mine_a"
            ownedTags += "mine_b"
        }

        every { player.uniqueId } returns playerId
        every { player.hasPermission(any<String>()) } returns false
        every { repository.get(playerId) } returns data
        every { config.settings } returns PluginSettings(
            effectTickInterval = 20L,
            forceDefaultTag = false,
            forcedTagId = "newbie",
        )
        every { config.tags } returns linkedMapOf(
            "mine_a" to tag("mine_a"),
            "mine_b" to tag("mine_b"),
            "challenge_collector" to tag("challenge_collector").copy(hidden = true),
        )
        every { config.shopProducts } returns linkedMapOf(
            "mine_a" to product("mine_a", "challenge"),
            "mine_b" to product("mine_b", "challenge"),
        )
        every { config.collection } returns TitleCollectionSettings(
            categories = listOf(
                TitleCollectionCategoryDefinition(
                    id = "challenge",
                    display = "挑战称号",
                    material = "DIAMOND_PICKAXE",
                    completedMaterial = "NETHER_STAR",
                    description = listOf("挑战来源"),
                    productCategories = setOf("challenge"),
                    rewardTagId = "challenge_collector",
                ),
            ),
        )
        every { config.rarityDisplay(any()) } returns "COMMON"

        val service = TagService(plugin, config, repository, economy, messages)
        val summary = service.collectionSummary(player)

        assertEquals(2, summary.owned)
        assertEquals(2, summary.total)
        assertTrue("challenge_collector" in data.ownedTags)
        assertEquals(1, summary.categories.size)
        assertTrue(summary.categories.single().completed)
        assertEquals("NETHER_STAR", summary.categories.single().displayMaterial)
        assertEquals(2, summary.categories.single().owned)
        verify { repository.saveAsync(data) }
    }

    private fun tag(id: String): TagDefinition {
        return TagDefinition(
            id = id,
            display = id,
            description = emptyList(),
            rarity = "COMMON",
            defaultUnlocked = true,
            upgradeGroups = mutableListOf(),
            permission = null,
        )
    }

    private fun product(tagId: String, category: String): ShopProductDefinition {
        return ShopProductDefinition(
            id = tagId,
            type = ShopProductType.TAG,
            targetId = tagId,
            mode = ShopProductMode.CHALLENGE_CLAIM,
            category = category,
            enabled = true,
            permission = null,
            conditions = emptyList(),
            cost = CostDefinition(),
            submitItems = emptyList(),
            icon = ItemTemplate("NAME_TAG", tagId, emptyList()),
        )
    }

    private fun detachFixture(): DetachFixture {
        val playerId = UUID.randomUUID()
        val player = mockk<Player>()
        val inventory = mockk<PlayerInventory>(relaxed = true)
        val plugin = mockk<UpTagsPlugin>(relaxed = true)
        val config = mockk<ConfigRegistry>()
        val repository = mockk<PlayerDataRepository>(relaxed = true)
        val economy = mockk<EconomyBridge>(relaxed = true)
        val messages = mockk<MessageService>(relaxed = true)
        val data = PlayerTagData(playerId)

        every { player.uniqueId } returns playerId
        every { player.inventory } returns inventory
        every { player.hasPermission(any<String>()) } returns false
        every { inventory.storageContents } returns arrayOfNulls(36)
        every { inventory.addItem(any<ItemStack>()) } returns hashMapOf()
        every { repository.get(playerId) } returns data
        every { config.settings } returns PluginSettings(
            effectTickInterval = 20L,
            forceDefaultTag = false,
            forcedTagId = "newbie",
        )
        every { config.detach } returns DetachSettings(
            enabled = true,
            buff = DetachCostSettings(money = 50.0, points = 25.0),
            particle = DetachCostSettings(money = 50.0, points = 25.0),
        )
        every { config.tags } returns linkedMapOf("vip" to tag("vip").copy(upgradeGroups = mutableListOf("COMMON")))
        val speedBuff = mockk<BuffDefinition>()
        every { speedBuff.id } returns "speed"
        every { speedBuff.display } returns "Speed"
        every { speedBuff.maxLevel } returns 3
        every { speedBuff.cost } returns CostDefinition(type = CurrencyType.POINTS, amount = 10.0)
        every { config.buffs } returns linkedMapOf(
            "speed" to speedBuff,
        )
        every { config.particles } returns linkedMapOf(
            "halo" to ParticleDefinition(
                id = "halo",
                display = "Halo",
                pattern = "halo",
                cost = CostDefinition(type = CurrencyType.POINTS, amount = 10.0),
            ),
        )
        every { config.scrolls } returns linkedMapOf(
            "speed_scroll" to ScrollDefinition("speed_scroll", ScrollKind.BUFF, "speed", "PAPER", "Speed", emptyList(), true),
            "halo_scroll" to ScrollDefinition("halo_scroll", ScrollKind.PARTICLE, "halo", "NETHER_STAR", "Halo", emptyList(), true),
        )
        every { config.upgradeGroups } returns linkedMapOf(
            "COMMON" to UpgradeGroupDefinition("COMMON", "Common", "Common", setOf("speed"), setOf("halo")),
        )

        return DetachFixture(
            player = player,
            inventory = inventory,
            data = data,
            economy = economy,
            service = TagService(plugin, config, repository, economy, messages),
        )
    }

    private data class DetachFixture(
        val player: Player,
        val inventory: PlayerInventory,
        val data: PlayerTagData,
        val economy: EconomyBridge,
        val service: TagService,
    )
}
