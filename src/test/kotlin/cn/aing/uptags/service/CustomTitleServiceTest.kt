package cn.aing.uptags.service

import cn.aing.uptags.Support
import cn.aing.uptags.config.ConfigRegistry
import cn.aing.uptags.config.MessageService
import cn.aing.uptags.model.config.CurrencyType
import cn.aing.uptags.model.config.CustomTitlePreset
import cn.aing.uptags.model.config.CustomTitleSettings
import cn.aing.uptags.repository.PlayerDataRepository
import cn.aing.uptags.repository.SaveResult
import cn.aing.uptags.model.runtime.PlayerTagData
import cn.aing.uptags.service.economy.EconomyBridge
import cn.aing.uptags.service.title.CustomTitleService
import cn.aing.uptags.service.title.CustomTitleStage
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.bukkit.entity.Player
import java.util.UUID

class CustomTitleServiceTest {
    @Test
    fun currencyChoiceDoesNotWithdrawImmediately() {
        val playerId = UUID.randomUUID()
        val player = mockk<Player>()
        val config = mockk<ConfigRegistry>()
        val repository = mockk<PlayerDataRepository>(relaxed = true)
        val economy = mockk<EconomyBridge>(relaxed = true)
        val messages = mockk<MessageService>(relaxed = true)
        val data = PlayerTagData(playerId)

        every { player.uniqueId } returns playerId
        every { repository.get(playerId) } returns data
        every { config.customTitleSettings } returns CustomTitleSettings(
            defaultTitleCoinBalance = 0.0,
            sessionTimeoutSeconds = 120,
            currencyCosts = linkedMapOf(CurrencyType.TITLE_COIN to 55.0),
            presets = mapOf("basic" to preset()),
        )
        every { economy.isAvailable(CurrencyType.TITLE_COIN) } returns true
        every { economy.balance(player, CurrencyType.TITLE_COIN) } returns 100.0

        val service = CustomTitleService(config, repository, economy, messages)
        assertTrue(service.startDraft(player, "basic"))

        val result = service.handleInput(player, "title_coin")

        assertFalse(result.success)
        assertNull(result.messageKey)
        assertEquals(CustomTitleStage.INPUT_NAME, service.activeDraft(player)?.stage)
        assertEquals(55.0, service.activeDraft(player)?.currencyAmount)
        verify(exactly = 0) { economy.withdraw(any(), any(), any()) }
    }

    @Test
    fun confirmWithSingleGroupCreatesPrivateCustomTitle() {
        val playerId = UUID.randomUUID()
        val player = mockk<Player>()
        val config = mockk<ConfigRegistry>()
        val repository = mockk<PlayerDataRepository>(relaxed = true)
        val economy = mockk<EconomyBridge>(relaxed = true)
        val messages = mockk<MessageService>(relaxed = true)
        val data = PlayerTagData(playerId)

        every { player.uniqueId } returns playerId
        every { repository.get(playerId) } returns data
        every { config.customTitleSettings } returns CustomTitleSettings(
            defaultTitleCoinBalance = 0.0,
            sessionTimeoutSeconds = 120,
            currencyCosts = linkedMapOf(CurrencyType.TITLE_COIN to 100.0),
            presets = mapOf("basic" to preset()),
        )
        every { config.allUpgradeGroups() } returns listOf("starter")
        every { config.hasUpgradeGroup("starter") } returns true
        every { economy.isAvailable(CurrencyType.TITLE_COIN) } returns true
        every { economy.balance(player, CurrencyType.TITLE_COIN) } returns 100.0
        every { economy.withdraw(player, CurrencyType.TITLE_COIN, 5.0) } returns true
        every { repository.saveAsyncStrict(data, any()) } answers {
            secondArg<(SaveResult) -> Unit>().invoke(SaveResult.Success(1L, System.currentTimeMillis()))
        }

        val service = CustomTitleService(config, repository, economy, messages)
        assertTrue(service.startProductDraft(player, "basic", CurrencyType.TITLE_COIN, 5.0, "custom_basic"))
        assertTrue(service.handleInput(player, "Hero").success)

        val result = service.confirm(player)

        assertTrue(result.success)
        assertNull(service.activeDraft(player))
        val custom = data.customTitles.values.firstOrNull()
        assertNotNull(custom)
        assertEquals("starter", custom.groupId)
        assertEquals(custom.id, data.equippedCustomTitleId)
        verify(exactly = 2) { repository.saveAsyncStrict(data, any()) }
        verify(exactly = 1) { economy.withdraw(player, CurrencyType.TITLE_COIN, 5.0) }
        verify(exactly = 0) { config.saveTags() }
    }

    @Test
    fun previewUsesConfiguredPalettesAfterNameInput() {
        val playerId = UUID.randomUUID()
        val player = mockk<Player>()
        val config = mockk<ConfigRegistry>()
        val repository = mockk<PlayerDataRepository>(relaxed = true)
        val economy = mockk<EconomyBridge>(relaxed = true)
        val messages = mockk<MessageService>(relaxed = true)
        val data = PlayerTagData(playerId)

        every { player.uniqueId } returns playerId
        every { repository.get(playerId) } returns data
        every { config.customTitleSettings } returns CustomTitleSettings(
            defaultTitleCoinBalance = 0.0,
            sessionTimeoutSeconds = 120,
            currencyCosts = linkedMapOf(CurrencyType.TITLE_COIN to 100.0),
            presets = mapOf("basic" to preset()),
        )

        val service = CustomTitleService(config, repository, economy, messages)
        assertTrue(service.startProductDraft(player, "basic", CurrencyType.TITLE_COIN, 5.0, "custom_basic"))
        assertTrue(service.handleInput(player, "Hero").success)

        val draft = service.activeDraft(player)
        assertNotNull(draft)
        assertEquals(CustomTitleStage.PREVIEW, draft.stage)
        assertEquals(1, draft.selectedPaletteLibrary)
        assertEquals(listOf("#ABCDEF"), draft.randomSchemes[0])
        assertEquals(listOf("#ABCDEF"), service.previewPalette(player))
        assertEquals("[Hero]", Support.stripColor(service.previewText(player)))
    }

    @Test
    fun confirmedCustomTitleIsRenderedWithBrackets() {
        val playerId = UUID.randomUUID()
        val player = mockk<Player>()
        val config = mockk<ConfigRegistry>()
        val repository = mockk<PlayerDataRepository>(relaxed = true)
        val economy = mockk<EconomyBridge>(relaxed = true)
        val messages = mockk<MessageService>(relaxed = true)
        val data = PlayerTagData(playerId)

        every { player.uniqueId } returns playerId
        every { repository.get(playerId) } returns data
        every { config.customTitleSettings } returns CustomTitleSettings(
            defaultTitleCoinBalance = 0.0,
            sessionTimeoutSeconds = 120,
            currencyCosts = linkedMapOf(CurrencyType.TITLE_COIN to 100.0),
            presets = mapOf("basic" to preset()),
        )
        every { config.allUpgradeGroups() } returns listOf("starter")
        every { config.hasUpgradeGroup("starter") } returns true
        every { economy.isAvailable(CurrencyType.TITLE_COIN) } returns true
        every { economy.balance(player, CurrencyType.TITLE_COIN) } returns 100.0
        every { economy.withdraw(player, CurrencyType.TITLE_COIN, 5.0) } returns true
        every { repository.saveAsyncStrict(data, any()) } answers {
            secondArg<(SaveResult) -> Unit>().invoke(SaveResult.Success(1L, System.currentTimeMillis()))
        }

        val service = CustomTitleService(config, repository, economy, messages)
        assertTrue(service.startProductDraft(player, "basic", CurrencyType.TITLE_COIN, 5.0, "custom_basic"))
        assertTrue(service.handleInput(player, "Hero").success)
        assertTrue(service.confirm(player).success)

        val custom = data.customTitles.values.firstOrNull()
        assertNotNull(custom)
        assertEquals("[Hero]", Support.stripColor(service.renderCustomTitle(custom)))
    }

    @Test
    fun selectPaletteLibraryFiltersConfiguredSchemesByColorCount() {
        val playerId = UUID.randomUUID()
        val player = mockk<Player>()
        val config = mockk<ConfigRegistry>()
        val repository = mockk<PlayerDataRepository>(relaxed = true)
        val economy = mockk<EconomyBridge>(relaxed = true)
        val messages = mockk<MessageService>(relaxed = true)
        val data = PlayerTagData(playerId)

        every { player.uniqueId } returns playerId
        every { repository.get(playerId) } returns data
        every { config.customTitleSettings } returns CustomTitleSettings(
            defaultTitleCoinBalance = 0.0,
            sessionTimeoutSeconds = 120,
            currencyCosts = linkedMapOf(CurrencyType.TITLE_COIN to 100.0),
            presets = mapOf("basic" to preset()),
        )

        val service = CustomTitleService(config, repository, economy, messages)
        assertTrue(service.startProductDraft(player, "basic", CurrencyType.TITLE_COIN, 5.0, "custom_basic"))
        assertTrue(service.handleInput(player, "Hero").success)

        val switched = service.selectPaletteLibrary(player, 3)

        assertTrue(switched.success)
        assertEquals(listOf(1, 2, 3), service.availablePaletteLibraries(player))
        assertEquals(3, service.currentPaletteLibrary(player))
        assertEquals(listOf("#00FF00", "#00BFFF", "#BD93F9"), service.previewPalette(player))
    }

    @Test
    fun moneyPurchaseOnlyAllowsSingleAndDoubleLibraries() {
        val playerId = UUID.randomUUID()
        val player = mockk<Player>()
        val config = mockk<ConfigRegistry>()
        val repository = mockk<PlayerDataRepository>(relaxed = true)
        val economy = mockk<EconomyBridge>(relaxed = true)
        val messages = mockk<MessageService>(relaxed = true)
        val data = PlayerTagData(playerId)

        every { player.uniqueId } returns playerId
        every { repository.get(playerId) } returns data
        every { config.customTitleSettings } returns CustomTitleSettings(
            defaultTitleCoinBalance = 0.0,
            sessionTimeoutSeconds = 120,
            currencyCosts = linkedMapOf(CurrencyType.MONEY to 888888.0),
            presets = mapOf("basic" to preset()),
        )

        val service = CustomTitleService(config, repository, economy, messages)
        assertTrue(service.startProductDraft(player, "basic", CurrencyType.MONEY, 888888.0, "custom_basic"))
        assertTrue(service.handleInput(player, "Hero").success)

        assertEquals(listOf(1, 2), service.availablePaletteLibraries(player))
        assertFalse(service.selectPaletteLibrary(player, 3).success)
        assertFalse(service.selectPaletteLibrary(player, 4).success)
    }

    @Test
    fun titleCoinPurchaseDisablesQuadLibrary() {
        val playerId = UUID.randomUUID()
        val player = mockk<Player>()
        val config = mockk<ConfigRegistry>()
        val repository = mockk<PlayerDataRepository>(relaxed = true)
        val economy = mockk<EconomyBridge>(relaxed = true)
        val messages = mockk<MessageService>(relaxed = true)
        val data = PlayerTagData(playerId)

        every { player.uniqueId } returns playerId
        every { repository.get(playerId) } returns data
        every { config.customTitleSettings } returns CustomTitleSettings(
            defaultTitleCoinBalance = 0.0,
            sessionTimeoutSeconds = 120,
            currencyCosts = linkedMapOf(CurrencyType.TITLE_COIN to 100.0),
            presets = mapOf("basic" to preset()),
        )

        val service = CustomTitleService(config, repository, economy, messages)
        assertTrue(service.startProductDraft(player, "basic", CurrencyType.TITLE_COIN, 100.0, "custom_basic"))
        assertTrue(service.handleInput(player, "Hero").success)

        assertEquals(listOf(1, 2, 3), service.availablePaletteLibraries(player))
        assertFalse(service.selectPaletteLibrary(player, 4).success)
    }

    @Test
    fun autoComposeRespectsCurrencyLibraryLimits() {
        val playerId = UUID.randomUUID()
        val player = mockk<Player>()
        val config = mockk<ConfigRegistry>()
        val repository = mockk<PlayerDataRepository>(relaxed = true)
        val economy = mockk<EconomyBridge>(relaxed = true)
        val messages = mockk<MessageService>(relaxed = true)
        val data = PlayerTagData(playerId)

        every { player.uniqueId } returns playerId
        every { repository.get(playerId) } returns data
        every { config.customTitleSettings } returns CustomTitleSettings(
            defaultTitleCoinBalance = 0.0,
            sessionTimeoutSeconds = 120,
            currencyCosts = linkedMapOf(CurrencyType.MONEY to 888888.0),
            presets = mapOf("basic" to preset()),
        )

        val service = CustomTitleService(config, repository, economy, messages)
        assertTrue(service.startProductDraft(player, "basic", CurrencyType.MONEY, 888888.0, "custom_basic"))
        assertTrue(service.handleInput(player, "Hero").success)
        assertEquals(1, service.currentPaletteLibrary(player))

        val result = service.autoComposePalette(player)

        assertTrue(result.success)
        assertEquals(listOf(1, 2), service.availablePaletteLibraries(player))
        assertEquals(2, service.currentPaletteLibrary(player))
    }

    @Test
    fun manualPaletteEditingUsesCurrentLibraryAndManualColorsForPreview() {
        val playerId = UUID.randomUUID()
        val player = mockk<Player>()
        val config = mockk<ConfigRegistry>()
        val repository = mockk<PlayerDataRepository>(relaxed = true)
        val economy = mockk<EconomyBridge>(relaxed = true)
        val messages = mockk<MessageService>(relaxed = true)
        val data = PlayerTagData(playerId)

        every { player.uniqueId } returns playerId
        every { repository.get(playerId) } returns data
        every { config.customTitleSettings } returns CustomTitleSettings(
            defaultTitleCoinBalance = 0.0,
            sessionTimeoutSeconds = 120,
            currencyCosts = linkedMapOf(CurrencyType.TITLE_COIN to 100.0),
            presets = mapOf("basic" to preset()),
        )

        val service = CustomTitleService(config, repository, economy, messages)
        assertTrue(service.startProductDraft(player, "basic", CurrencyType.TITLE_COIN, 5.0, "custom_basic"))
        assertTrue(service.handleInput(player, "Hero").success)

        val result = service.beginManualPaletteEditing(player, 2)

        assertTrue(result.success)
        assertEquals(2, service.manualPaletteTarget(player))
        assertEquals(2, service.currentPaletteLibrary(player))
        assertEquals(listOf("#123456", "#654321"), service.manualColorChoices(player).take(2))
        assertTrue(service.selectManualColor(player, 0).success)
        assertTrue(service.selectManualColor(player, 1).success)
        assertEquals(listOf("#123456", "#654321"), service.previewPalette(player))
        assertTrue(service.finishManualPaletteEditing(player).success)
        assertNull(service.manualPaletteTarget(player))
        assertEquals(2, service.currentPaletteLibrary(player))
    }

    @Test
    fun manualPaletteChoicesSupportPagination() {
        val playerId = UUID.randomUUID()
        val player = mockk<Player>()
        val config = mockk<ConfigRegistry>()
        val repository = mockk<PlayerDataRepository>(relaxed = true)
        val economy = mockk<EconomyBridge>(relaxed = true)
        val messages = mockk<MessageService>(relaxed = true)
        val data = PlayerTagData(playerId)

        every { player.uniqueId } returns playerId
        every { repository.get(playerId) } returns data
        every { config.customTitleSettings } returns CustomTitleSettings(
            defaultTitleCoinBalance = 0.0,
            sessionTimeoutSeconds = 120,
            currencyCosts = linkedMapOf(CurrencyType.TITLE_COIN to 100.0),
            presets = mapOf(
                "basic" to preset(
                    palettes = listOf(
                        listOf("#111111"),
                        listOf("#222222"),
                        listOf("#333333"),
                        listOf("#444444"),
                        listOf("#555555"),
                        listOf("#666666"),
                        listOf("#777777"),
                        listOf("#888888"),
                        listOf("#999999"),
                        listOf("#AAAAAA"),
                        listOf("#BBBBBB"),
                        listOf("#CCCCCC"),
                        listOf("#DDDDDD"),
                    ),
                    randomColorPool = emptyList(),
                ),
            ),
        )

        val service = CustomTitleService(config, repository, economy, messages)
        assertTrue(service.startProductDraft(player, "basic", CurrencyType.TITLE_COIN, 5.0, "custom_basic"))
        assertTrue(service.handleInput(player, "Hero").success)
        assertTrue(service.selectPaletteLibrary(player, 1).success)
        assertTrue(service.beginManualPaletteEditing(player).success)

        val firstPage = service.manualColorPage(player)
        assertEquals(12, firstPage.colors.size)
        assertEquals(
            listOf(
                "#111111",
                "#222222",
                "#333333",
                "#444444",
                "#555555",
                "#666666",
                "#777777",
                "#888888",
                "#999999",
                "#AAAAAA",
                "#BBBBBB",
                "#CCCCCC",
            ),
            firstPage.colors,
        )
        assertEquals(0, firstPage.pageIndex)
        assertTrue(firstPage.totalPages > 1)

        assertTrue(service.changeManualColorPage(player, 1).success)
        val secondPage = service.manualColorPage(player)
        assertEquals("#DDDDDD", secondPage.colors.first())
        assertEquals(1, secondPage.pageIndex)
        assertEquals(12, secondPage.pageOffset)
    }

    @Test
    fun manualPaletteChoicesIncludeBuiltInRpgColors() {
        val playerId = UUID.randomUUID()
        val player = mockk<Player>()
        val config = mockk<ConfigRegistry>()
        val repository = mockk<PlayerDataRepository>(relaxed = true)
        val economy = mockk<EconomyBridge>(relaxed = true)
        val messages = mockk<MessageService>(relaxed = true)
        val data = PlayerTagData(playerId)

        every { player.uniqueId } returns playerId
        every { repository.get(playerId) } returns data
        every { config.customTitleSettings } returns CustomTitleSettings(
            defaultTitleCoinBalance = 0.0,
            sessionTimeoutSeconds = 120,
            currencyCosts = linkedMapOf(CurrencyType.TITLE_COIN to 100.0),
            presets = mapOf(
                "basic" to preset(
                    palettes = listOf(listOf("#123456")),
                    randomColorPool = emptyList(),
                ),
            ),
        )

        val service = CustomTitleService(config, repository, economy, messages)
        assertTrue(service.startProductDraft(player, "basic", CurrencyType.TITLE_COIN, 5.0, "custom_basic"))
        assertTrue(service.handleInput(player, "Hero").success)
        assertTrue(service.selectPaletteLibrary(player, 1).success)
        assertTrue(service.beginManualPaletteEditing(player).success)

        val choices = service.manualColorChoices(player)
        assertTrue(choices.contains("#123456"))
        assertTrue(choices.contains("#FDE68A"))
        assertTrue(choices.contains("#3B82F6"))
    }

    @Test
    fun manualPaletteEditingIsBlockedWhenPresetDisablesManualColors() {
        val playerId = UUID.randomUUID()
        val player = mockk<Player>()
        val config = mockk<ConfigRegistry>()
        val repository = mockk<PlayerDataRepository>(relaxed = true)
        val economy = mockk<EconomyBridge>(relaxed = true)
        val messages = mockk<MessageService>(relaxed = true)
        val data = PlayerTagData(playerId)

        every { player.uniqueId } returns playerId
        every { repository.get(playerId) } returns data
        every { config.customTitleSettings } returns CustomTitleSettings(
            defaultTitleCoinBalance = 0.0,
            sessionTimeoutSeconds = 120,
            currencyCosts = linkedMapOf(CurrencyType.TITLE_COIN to 100.0),
            presets = mapOf("basic" to preset(allowManualColors = false)),
        )

        val service = CustomTitleService(config, repository, economy, messages)
        assertTrue(service.startProductDraft(player, "basic", CurrencyType.TITLE_COIN, 5.0, "custom_basic"))
        assertTrue(service.handleInput(player, "Hero").success)

        val result = service.beginManualPaletteEditing(player)

        assertFalse(result.success)
        assertEquals("custom-title-manual-disabled", result.messageKey)
    }

    @Test
    fun randomColorPoolIsFallbackWhenPalettesAreMissing() {
        val playerId = UUID.randomUUID()
        val player = mockk<Player>()
        val config = mockk<ConfigRegistry>()
        val repository = mockk<PlayerDataRepository>(relaxed = true)
        val economy = mockk<EconomyBridge>(relaxed = true)
        val messages = mockk<MessageService>(relaxed = true)
        val data = PlayerTagData(playerId)

        every { player.uniqueId } returns playerId
        every { repository.get(playerId) } returns data
        every { config.customTitleSettings } returns CustomTitleSettings(
            defaultTitleCoinBalance = 0.0,
            sessionTimeoutSeconds = 120,
            currencyCosts = linkedMapOf(CurrencyType.TITLE_COIN to 100.0),
            presets = mapOf(
                "basic" to preset(
                    palettes = emptyList(),
                    randomColorPool = listOf("#112233", "#445566", "#778899"),
                ),
            ),
        )

        val service = CustomTitleService(config, repository, economy, messages)
        assertTrue(service.startProductDraft(player, "basic", CurrencyType.TITLE_COIN, 5.0, "custom_basic"))
        assertTrue(service.handleInput(player, "Hero").success)

        assertTrue(service.previewPalette(player).all { it in setOf("#112233", "#445566", "#778899") })
    }

    private fun preset(
        palettes: List<List<String>> = listOf(
            listOf("#ABCDEF"),
            listOf("#123456", "#654321"),
            listOf("#00FF00", "#00BFFF", "#BD93F9"),
            listOf("#FDE68A", "#FF8FD8", "#7DD3FC", "#C084FC"),
        ),
        randomColorPool: List<String> = listOf("#112233", "#445566", "#778899"),
        allowManualColors: Boolean = true,
    ): CustomTitlePreset {
        return CustomTitlePreset(
            id = "basic",
            minLength = 2,
            maxLength = 12,
            maxSchemes = 3,
            colorsPerScheme = 2,
            allowManualColors = allowManualColors,
            allowSpaces = true,
            allowedPattern = "^[A-Za-z ]+$",
            blockedWords = emptySet(),
            blockedPatterns = emptyList(),
            palettes = palettes,
            randomColorPool = randomColorPool,
            previewTemplate = "%title%",
            equipAfterConfirm = true,
        )
    }
}
