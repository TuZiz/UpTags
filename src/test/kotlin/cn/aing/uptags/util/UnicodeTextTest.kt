package cn.aing.uptags.util

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class UnicodeTextTest {
    @Test
    fun allowsEmojiJoinersOnlyInsideValidEmojiClusters() {
        assertFalse(UnicodeText.containsForbiddenCodePoint("🏳️‍🌈"))
        assertFalse(UnicodeText.containsForbiddenCodePoint("👨‍👩‍👧‍👦"))
        assertFalse(UnicodeText.containsForbiddenCodePoint("🏳️🌈"))

        assertTrue(UnicodeText.containsForbiddenCodePoint("\u200D"))
        assertTrue(UnicodeText.containsForbiddenCodePoint("abc\u200Ddef"))
        assertTrue(UnicodeText.containsForbiddenCodePoint("\u200B"))
        assertTrue(UnicodeText.containsForbiddenCodePoint("\uFE0F"))
        assertTrue(UnicodeText.containsForbiddenCodePoint("A\uFE0F"))
    }
}
