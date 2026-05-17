package cn.aing.uptags.util

import java.text.BreakIterator
import java.text.Normalizer
import java.util.Locale

object UnicodeText {
    private val legacyAmpColor = Regex("(?i)&(?:#[0-9a-f]{6}|[0-9a-fk-or])")
    private val legacySectionColor = Regex("(?i)\u00A7(?:x(?:\u00A7[0-9a-f]){6}|#[0-9a-f]{6}|[0-9a-fk-or])")
    private val miniMessageTag = Regex("</?[A-Za-z][A-Za-z0-9_-]*(?::[^<>]*)?>")

    private val forbiddenTypes = setOf(
        Character.CONTROL.toInt(),
        Character.FORMAT.toInt(),
        Character.PRIVATE_USE.toInt(),
        Character.SURROGATE.toInt(),
        Character.UNASSIGNED.toInt(),
    )

    fun sanitizePlayerTitleInput(input: String?): String {
        if (input.isNullOrBlank()) {
            return ""
        }
        return miniMessageTag
            .replace(legacySectionColor.replace(legacyAmpColor.replace(input, ""), ""), "")
            .trim()
    }

    fun containsForbiddenCodePoint(value: String): Boolean {
        var index = 0
        while (index < value.length) {
            val codePoint = value.codePointAt(index)
            if (Character.getType(codePoint) in forbiddenTypes) {
                return true
            }
            index += Character.charCount(codePoint)
        }
        return false
    }

    fun riskText(value: String?): String {
        val cleaned = sanitizePlayerTitleInput(value)
        val withoutForbidden = buildString {
            var index = 0
            while (index < cleaned.length) {
                val codePoint = cleaned.codePointAt(index)
                if (Character.getType(codePoint) !in forbiddenTypes) {
                    appendCodePoint(codePoint)
                }
                index += Character.charCount(codePoint)
            }
        }
        return Normalizer.normalize(withoutForbidden, Normalizer.Form.NFKC)
            .lowercase(Locale.ROOT)
    }

    fun graphemeClusters(value: String?): List<String> {
        if (value.isNullOrEmpty()) {
            return emptyList()
        }
        val iterator = BreakIterator.getCharacterInstance(Locale.ROOT)
        iterator.setText(value)
        val raw = ArrayList<String>()
        var start = iterator.first()
        var end = iterator.next()
        while (end != BreakIterator.DONE) {
            raw += value.substring(start, end)
            start = end
            end = iterator.next()
        }
        return mergeGraphemeFragments(raw)
    }

    fun visibleCharacterCount(value: String?): Int = graphemeClusters(value).size

    private fun mergeGraphemeFragments(raw: List<String>): List<String> {
        if (raw.isEmpty()) {
            return emptyList()
        }
        val merged = ArrayList<String>()
        raw.forEach { fragment ->
            if (merged.isEmpty()) {
                merged += fragment
                return@forEach
            }
            val firstCodePoint = fragment.codePointAt(0)
            val previous = merged.last()
            val previousLastCodePoint = previous.codePointBefore(previous.length)
            if (shouldJoin(previousLastCodePoint, firstCodePoint, previous)) {
                merged[merged.lastIndex] = previous + fragment
            } else {
                merged += fragment
            }
        }
        return merged
    }

    private fun shouldJoin(previousLastCodePoint: Int, firstCodePoint: Int, previous: String): Boolean {
        if (previousLastCodePoint == 0x200D || firstCodePoint == 0x200D) {
            return true
        }
        if (firstCodePoint in 0xFE00..0xFE0F) {
            return true
        }
        if (firstCodePoint in 0x1F3FB..0x1F3FF) {
            return true
        }
        if (Character.getType(firstCodePoint) in setOf(
                Character.NON_SPACING_MARK.toInt(),
                Character.COMBINING_SPACING_MARK.toInt(),
                Character.ENCLOSING_MARK.toInt(),
            )
        ) {
            return true
        }
        return isRegionalIndicator(previousLastCodePoint) &&
            isRegionalIndicator(firstCodePoint) &&
            regionalIndicatorRunLength(previous).mod(2) == 1
    }

    private fun isRegionalIndicator(codePoint: Int): Boolean = codePoint in 0x1F1E6..0x1F1FF

    private fun regionalIndicatorRunLength(value: String): Int {
        var count = 0
        var index = value.length
        while (index > 0) {
            val codePoint = value.codePointBefore(index)
            if (!isRegionalIndicator(codePoint)) {
                break
            }
            count++
            index -= Character.charCount(codePoint)
        }
        return count
    }
}
