package cn.aing.uptags.util

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class TextRendererTest {
    @Test
    fun renderPaletteTextUsesSingleColorWhenPaletteHasOneStop() {
        val rendered = TextRenderer.renderPaletteText("ABC", listOf("#112233"))

        val colorCode = TextRenderer.color("&#112233")
        assertEquals("${colorCode}A${colorCode}B${colorCode}C", rendered)
    }

    @Test
    fun renderPaletteTextInterpolatesAcrossGradientStops() {
        val rendered = TextRenderer.renderPaletteText("ABCD", listOf("#000000", "#FFFFFF"))
        val black = TextRenderer.color("&#000000")
        val white = TextRenderer.color("&#FFFFFF")

        assertTrue(rendered.startsWith("${black}A"))
        assertTrue(rendered.endsWith("${white}D"))
        assertTrue(rendered.contains(TextRenderer.color("&#8B8B8BB")))
        assertTrue(rendered.contains(TextRenderer.color("&#DFDFDFC")))
        assertFalse(rendered.contains(TextRenderer.color("&#555555B")))
        assertFalse(rendered.contains(TextRenderer.color("&#AAAAAAC")))
    }
}
