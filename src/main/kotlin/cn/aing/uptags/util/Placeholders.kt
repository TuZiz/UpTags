package cn.aing.uptags.util

object Placeholders {
    fun apply(source: String?, placeholders: Map<String, String>): String {
        if (source == null || placeholders.isEmpty()) {
            return source ?: ""
        }
        var result: String = source
        placeholders.forEach { (key, value) ->
            result = result.replace("%$key%", value)
        }
        return result
    }
}
