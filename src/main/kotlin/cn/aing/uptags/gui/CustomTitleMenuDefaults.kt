package cn.aing.uptags.gui

import cn.aing.uptags.model.config.GuiTemplate

internal object CustomTitleMenuDefaults {
    fun manualEditorTemplate(name: String): GuiTemplate? {
        return when (name) {
            "add" -> GuiTemplate("EMERALD", "&#A7F3D0加入颜色", listOf("&#E2E8F0将当前 6 位 HEX 颜色加入方案"), emptyList())
            "replace" -> GuiTemplate("LIME_DYE", "&#60A5FA替换尾色", listOf("&#E2E8F0用当前输入替换最后一个已选颜色"), emptyList())
            "remove" -> GuiTemplate("RED_DYE", "&#F87171移除尾色", listOf("&#E2E8F0删除最后一个已选颜色"), emptyList())
            "backspace" -> GuiTemplate("SHEARS", "&#FDE047退格", listOf("&#E2E8F0删除当前输入的最后一位"), emptyList())
            "clear-input" -> GuiTemplate("PAPER", "&#94A3B8清空输入", listOf("&#E2E8F0清空当前 6 位 HEX 输入"), emptyList())
            "clear-palette" -> GuiTemplate("BARRIER", "&#F87171清空重选", listOf("&#E2E8F0清空已选颜色并重新开始"), emptyList())
            "confirm" -> GuiTemplate("NETHER_STAR", "&#A7F3D0确认选色", listOf("&#E2E8F0选满目标颜色数量后确认当前组合"), emptyList())
            "back" -> GuiTemplate("ARROW", "&#F87171返回预览", listOf("&#E2E8F0返回聊天栏预览，不取消本次定制"), emptyList())
            else -> null
        }
    }

    fun editorTemplate(name: String): GuiTemplate? {
        return when (name) {
            "prev-scheme" -> GuiTemplate("ARROW", "&#FDE047上一套", listOf("&#E2E8F0切换到上一套推荐配色"), emptyList())
            "next-scheme" -> GuiTemplate("ARROW", "&#FDE047下一套", listOf("&#E2E8F0切换到下一套推荐配色"), emptyList())
            "reroll-scheme" -> GuiTemplate("AMETHYST_SHARD", "&#60A5FA自动组合", listOf("&#E2E8F0重新生成一套参考配色"), emptyList())
            else -> null
        }
    }

    fun hexDigitMaterial(digit: Char): String {
        return when (digit.uppercaseChar()) {
            '0', '1', '2', '3', '4', '5' -> "LIGHT_GRAY_WOOL"
            '6', '7', '8', '9' -> "GRAY_WOOL"
            'A', 'B', 'C' -> "PINK_WOOL"
            'D', 'E', 'F' -> "MAGENTA_WOOL"
            else -> "WHITE_WOOL"
        }
    }
}
