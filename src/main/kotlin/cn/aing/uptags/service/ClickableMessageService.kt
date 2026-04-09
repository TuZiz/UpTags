package cn.aing.uptags.service

import cn.aing.uptags.Support
import net.md_5.bungee.api.chat.BaseComponent
import net.md_5.bungee.api.chat.ClickEvent
import net.md_5.bungee.api.chat.HoverEvent
import net.md_5.bungee.api.chat.TextComponent
import net.md_5.bungee.api.chat.hover.content.Text
import org.bukkit.entity.Player

class ClickableMessageService {
    fun sendCurrencyChoices(player: Player) {
        player.spigot().sendMessage(
            button(
                "[金币 888888]",
                "money",
                listOf(
                    "&#FDE047支付方式: 金币",
                    "&#E2E8F0扣除: &#FDE047888888 金币",
                    "&#E2E8F0功能: 开启自定义称号定制流程",
                ),
            ),
            space(),
            button(
                "[称号币 100]",
                "title_coin",
                listOf(
                    "&#A78BFA支付方式: 称号币",
                    "&#E2E8F0扣除: &#A78BFA100 称号币",
                    "&#E2E8F0功能: 开启自定义称号定制流程",
                ),
            ),
            space(),
            button(
                "[点券 35]",
                "points",
                listOf(
                    "&#60A5FA支付方式: 点券",
                    "&#E2E8F0扣除: &#60A5FA35 点券",
                    "&#E2E8F0功能: 开启自定义称号定制流程",
                ),
            ),
        )
    }

    fun sendPreviewControls(
        player: Player,
        preview: String?,
        palette: List<String> = emptyList(),
        currentLibrary: Int? = null,
        availableLibraries: List<Int> = emptyList(),
        manualEnabled: Boolean = false,
    ) {
        val paletteSummary = paletteText(palette)
        val librarySuffix = currentLibrary?.let { " &#94A3B8(${paletteLibraryName(it)})" }.orEmpty()
        player.spigot().sendMessage(
            *buildPreviewHeader(preview),
            space(),
            button(
                "[随机颜色]",
                "auto",
                listOf(
                    "&#E2E8F0自动随机切换一套新的参考配色",
                    "&#94A3B8会按当前支付方式允许的颜色数量自动选择",
                ),
                color = "&#60A5FA",
            ),
        )
        player.spigot().sendMessage(
            *legacyComponents("&#94A3B8当前参考配色$librarySuffix: $paletteSummary"),
            if (manualEnabled && availableLibraries.isNotEmpty()) space() else TextComponent(""),
            if (manualEnabled && availableLibraries.isNotEmpty()) {
                button(
                    "[自定义组合]",
                    "manual",
                    listOf(
                        "&#E2E8F0先选择几色，再进入手动选色分页",
                        "&#94A3B8当前支付方式下可选: ${availableLibraries.joinToString(" / ") { paletteLibraryName(it) }}",
                    ),
                    color = "&#A7F3D0",
                )
            } else {
                TextComponent("")
            },
        )
        player.spigot().sendMessage(
            button("[上一套]", "prev", listOf("&#E2E8F0切换到当前随机库中的上一套方案", "&#94A3B8当前参考: &#FDE047$paletteSummary")),
            space(),
            button("[下一套]", "next", listOf("&#E2E8F0切换到当前随机库中的下一套方案", "&#94A3B8当前参考: &#FDE047$paletteSummary")),
            space(),
            button("[确认颜色]", "confirm", listOf("&#E2E8F0确认当前配色并进入升级组选取", "&#94A3B8当前参考: &#FDE047$paletteSummary")),
            space(),
            button("[取消]", "cancel", listOf("&#E2E8F0取消本次自定义称号流程")),
        )
    }

    fun sendManualLibraryChooser(
        player: Player,
        availableLibraries: List<Int>,
    ) {
        player.spigot().sendMessage(
            *legacyComponents("&#A7F3D0自定义组合: &#E2E8F0先选择你要的颜色数量"),
        )
        player.spigot().sendMessage(
            *legacyComponents("&#94A3B8当前可选: ${availableLibraries.joinToString(" / ") { paletteLibraryName(it) }}"),
        )

        val buttons = mutableListOf<BaseComponent>()
        availableLibraries.distinct().sorted().forEachIndexed { index, colorCount ->
            if (index > 0) {
                buttons += space()
            }
            buttons += button(
                "[${paletteLibraryName(colorCount)}]",
                manualLibraryAction(colorCount),
                listOf("&#E2E8F0进入 ${paletteLibraryName(colorCount)} 的手动选色分页"),
                color = "&#FDE68A",
            )
        }
        player.spigot().sendMessage(*buttons.toTypedArray())
        player.spigot().sendMessage(
            button("[返回预览]", "manual_choose_back", listOf("&#E2E8F0返回正常预览界面"), color = "&#F87171"),
        )
    }

    fun sendManualColorPicker(
        player: Player,
        titleText: String,
        preview: String?,
        selectedColors: List<String>,
        pageColors: List<String>,
        pageIndex: Int,
        totalPages: Int,
        pageOffset: Int,
        targetCount: Int,
    ) {
        player.spigot().sendMessage(
            *legacyComponents("&#A7F3D0手动组合: &#E2E8F0已选 &#FDE047${selectedColors.size}&#64748B/&#FDE047$targetCount &#94A3B8| 第 &#FDE047${pageIndex + 1}&#64748B/&#FDE047$totalPages &#94A3B8页"),
        )
        player.spigot().sendMessage(
            *legacyComponents("&#FDE047当前预览: ${preview ?: "&#94A3B8(尚未生成预览)"}"),
        )
        player.spigot().sendMessage(
            *legacyComponents("&#94A3B8已选顺序: ${paletteText(selectedColors)}"),
        )

        if (pageColors.isEmpty()) {
            player.spigot().sendMessage(*legacyComponents("&#64748B当前页没有可用颜色"))
        } else {
            val choices = pageColors.mapIndexedNotNull { index, color ->
                val normalized = Support.normalizeHex(color) ?: return@mapIndexedNotNull null
                val previewText = Support.renderPaletteText(titleText, selectedColors + normalized)
                previewChoiceButton(
                    previewText,
                    "pick_${pageOffset + index}",
                    listOf(
                        "&#E2E8F0点击选择这个颜色作为第 ${selectedColors.size + 1} 个颜色",
                        "&#94A3B8颜色值: &#FDE047$normalized",
                    ),
                )
            }
            choices.chunked(4).forEach { row ->
                val line = TextComponent("")
                row.forEachIndexed { index, choice ->
                    if (index > 0) {
                        line.addExtra(TextComponent("   "))
                    }
                    line.addExtra(choice)
                }
                player.spigot().sendMessage(line)
            }
        }

        val complete = selectedColors.size == targetCount
        player.spigot().sendMessage(
            button("[移除最后一个]", "manual_remove", listOf("&#E2E8F0删除最后一个已选颜色")),
            space(),
            button("[清空重选]", "manual_clear", listOf("&#E2E8F0清空当前已选颜色并重新开始")),
            space(),
            button(
                "[完成选色]",
                "manual_done",
                listOf(
                    if (complete) "&#A7F3D0已选满，点击返回正常预览" else "&#FDE68A还需选择 ${targetCount - selectedColors.size} 个颜色",
                ),
                color = if (complete) "&#A7F3D0" else "&#64748B",
                enabled = complete,
            ),
        )
        player.spigot().sendMessage(
            button(
                "[上一页]",
                "manual_page_prev",
                listOf("&#E2E8F0查看上一页可选颜色"),
                color = if (pageIndex > 0) "&#E2E8F0" else "&#64748B",
                enabled = pageIndex > 0,
            ),
            space(),
            button("[返回]", "manual_back", listOf("&#E2E8F0返回正常预览并取消本次手动选色"), color = "&#F87171"),
            space(),
            button(
                "[下一页]",
                "manual_page_next",
                listOf("&#E2E8F0查看下一页可选颜色"),
                color = if (pageIndex + 1 < totalPages) "&#E2E8F0" else "&#64748B",
                enabled = pageIndex + 1 < totalPages,
            ),
        )
    }

    private fun buildPreviewHeader(preview: String?): Array<BaseComponent> {
        return legacyComponents("&#FDE047称号预览: ${preview ?: "&#94A3B8(尚未生成预览)"}")
    }

    private fun button(
        text: String,
        action: String,
        hoverLines: List<String>,
        color: String = "&#E2E8F0",
        enabled: Boolean = true,
    ): TextComponent {
        return TextComponent("").apply {
            val hover = HoverEvent(HoverEvent.Action.SHOW_TEXT, Text(legacyComponents(hoverLines.joinToString("\n"))))
            val click = if (enabled) ClickEvent(ClickEvent.Action.RUN_COMMAND, "/tags custom preview $action") else null
            legacyComponents("$color$text").forEach { component ->
                component.clickEvent = click
                component.hoverEvent = hover
                addExtra(component)
            }
        }
    }

    private fun previewChoiceButton(
        previewText: String,
        action: String,
        hoverLines: List<String>,
    ): TextComponent {
        return TextComponent("").apply {
            val hover = HoverEvent(HoverEvent.Action.SHOW_TEXT, Text(legacyComponents(hoverLines.joinToString("\n"))))
            val click = ClickEvent(ClickEvent.Action.RUN_COMMAND, "/tags custom preview $action")
            legacyComponents(previewText).forEach { component ->
                component.clickEvent = click
                component.hoverEvent = hover
                addExtra(component)
            }
        }
    }

    private fun manualLibraryAction(colorCount: Int): String {
        return when (colorCount) {
            1 -> "manual_single"
            2 -> "manual_double"
            3 -> "manual_triple"
            4 -> "manual_quad"
            else -> "manual_single"
        }
    }

    private fun paletteLibraryName(colorCount: Int): String {
        return when (colorCount) {
            1 -> "单色"
            2 -> "双色"
            3 -> "三色"
            4 -> "四色"
            else -> "${colorCount}色"
        }
    }

    private fun space(): TextComponent = TextComponent(" ")

    private fun legacyComponents(text: String): Array<BaseComponent> {
        @Suppress("DEPRECATION")
        return TextComponent.fromLegacyText(Support.color(text))
    }

    private fun paletteText(palette: List<String>): String {
        if (palette.isEmpty()) {
            return "&#64748B未选择"
        }
        return palette.joinToString(" ") { hex ->
            val normalized = Support.normalizeHex(hex) ?: return@joinToString "&#64748B?"
            val colorCode = "&$normalized"
            "$colorCode■ &#E2E8F0$normalized"
        }
    }
}
