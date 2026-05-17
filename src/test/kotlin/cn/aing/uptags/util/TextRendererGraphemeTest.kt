package cn.aing.uptags.util

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class TextRendererGraphemeTest {
    @Test
    fun renderPaletteTextDoesNotSplitSurrogatePairs() {
        val rendered = TextRenderer.renderPaletteText("🐉龍", listOf("#000000", "#FFFFFF"))
        val dragon = "🐉"
        val dragonIndex = rendered.indexOf(dragon)

        assertTrue(dragonIndex >= 0)
        assertEquals("🐉龍", TextRenderer.stripColor(rendered))
        assertFalse(rendered.contains("\uD83D${TextRenderer.color("&#FFFFFF")}\uDC09"))
    }

    @Test
    fun renderPaletteTextCountsGraphemeClustersForUnicodeGradient() {
        val rendered = TextRenderer.renderPaletteText("桜咲く귀여운★♡✦・ー⭐勇者🏳️🌈", listOf("#000000", "#FFFFFF"))

        assertEquals("桜咲く귀여운★♡✦・ー⭐勇者🏳️🌈", TextRenderer.stripColor(rendered))
        assertEquals(16, UnicodeText.visibleCharacterCount("桜咲く귀여운★♡✦・ー⭐勇者🏳️🌈"))
        assertEquals(2, UnicodeText.visibleCharacterCount("🏳️🌈"))
        assertEquals(2, UnicodeText.visibleCharacterCount("e\u0301龍"))
    }
}
