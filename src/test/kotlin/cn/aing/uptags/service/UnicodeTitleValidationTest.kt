package cn.aing.uptags.service

import cn.aing.uptags.config.ConfigRegistry
import cn.aing.uptags.config.MessageService
import cn.aing.uptags.model.config.CurrencyType
import cn.aing.uptags.model.config.CustomTitlePreset
import cn.aing.uptags.model.config.CustomTitleSettings
import cn.aing.uptags.model.runtime.PlayerTagData
import cn.aing.uptags.repository.PlayerDataRepository
import cn.aing.uptags.service.economy.EconomyBridge
import cn.aing.uptags.service.title.CustomTitleService
import io.mockk.every
import io.mockk.mockk
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.bukkit.entity.Player

class UnicodeTitleValidationTest {
    @Test
    fun acceptsJapaneseKoreanSymbolsAndEmoji() {
        val fixture = fixture()
        val service = fixture.service
        val samples = listOf(
            "桜咲く",
            "さくら",
            "サクラ",
            "星空★旅人",
            "夢・幻",
            "귀여운칭호",
            "勇者♡",
            "VIP✦",
            "🐉龍",
            "⭐勇者",
            "🏳️🌈",
            "🏳️‍🌈",
            "👨‍👩‍👧‍👦",
            "A|B,C:D;E#F~G",
            "ー",
        )

        samples.forEach { sample ->
            assertTrue(service.startProductDraft(fixture.player, "unicode", CurrencyType.TITLE_COIN, 1.0))
            val result = service.handleInput(fixture.player, sample)
            assertTrue(result.success, "Expected '$sample' to pass")
            assertEquals(sample, service.draftRawText(fixture.player))
            service.cancelDraft(fixture.player, notify = false)
        }
    }

    @Test
    fun blocksColorInjectionAndNormalizedSensitiveWords() {
        val fixture = fixture()
        val service = fixture.service
        val blocked = listOf(
            "&aAdmin",
            "§cOP",
            "&#FFFFFFOwner",
            "<red>Owner",
            "ａｄｍｉｎ",
            "AdMiN",
            "ad\u200Bmin",
            "\u200D",
            "abc\u200Ddef",
            "\uFE0F",
            "bad\uE000title",
        )

        blocked.forEach { sample ->
            assertTrue(service.startProductDraft(fixture.player, "unicode", CurrencyType.TITLE_COIN, 1.0))
            val result = service.handleInput(fixture.player, sample)
            assertFalse(result.success, "Expected '$sample' to be blocked")
            service.cancelDraft(fixture.player, notify = false)
        }
    }

    @Test
    fun lengthUsesGraphemeClusters() {
        val fixture = fixture(
            preset = preset(minLength = 2, maxLength = 2),
        )
        val service = fixture.service

        assertTrue(service.startProductDraft(fixture.player, "unicode", CurrencyType.TITLE_COIN, 1.0))
        assertTrue(service.handleInput(fixture.player, "🐉龍").success)
        service.cancelDraft(fixture.player, notify = false)

        assertTrue(service.startProductDraft(fixture.player, "unicode", CurrencyType.TITLE_COIN, 1.0))
        assertTrue(service.handleInput(fixture.player, "e\u0301龍").success)
        service.cancelDraft(fixture.player, notify = false)

        assertTrue(service.startProductDraft(fixture.player, "unicode", CurrencyType.TITLE_COIN, 1.0))
        val tooLong = service.handleInput(fixture.player, "桜咲く")
        assertFalse(tooLong.success)
        assertEquals("custom-title-too-long", tooLong.messageKey)
    }

    private fun fixture(preset: CustomTitlePreset = preset()): Fixture {
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
            currencyCosts = linkedMapOf(CurrencyType.TITLE_COIN to 1.0),
            presets = mapOf("unicode" to preset),
        )

        return Fixture(
            player = player,
            service = CustomTitleService(config, repository, economy, messages),
        )
    }

    private fun preset(minLength: Int = 1, maxLength: Int = 16): CustomTitlePreset {
        return CustomTitlePreset(
            id = "unicode",
            minLength = minLength,
            maxLength = maxLength,
            maxSchemes = 2,
            colorsPerScheme = 2,
            allowManualColors = true,
            allowSpaces = true,
            allowedPattern = "^[\\p{L}\\p{M}\\p{N}\\p{P}\\p{S} _　]+$",
            blockedWords = setOf("admin", "gm", "op", "owner"),
            blockedPatterns = listOf("owner"),
            palettes = listOf(listOf("#FFFFFF")),
            randomColorPool = emptyList(),
            previewTemplate = "%title%",
            equipAfterConfirm = true,
        )
    }

    private data class Fixture(
        val player: Player,
        val service: CustomTitleService,
    )
}
