package cn.aing.uptags.service

import cn.aing.uptags.service.tag.TagConditionChecker
import kotlin.test.Test
import kotlin.test.assertTrue

class TagConditionCheckerTest {
    @Test
    fun unresolvedPlaceholderIsDetected() {
        assertTrue(TagConditionChecker.containsUnresolvedPlaceholder("%server_season%"))
    }
}
