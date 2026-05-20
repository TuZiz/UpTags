package cn.aing.uptags.command.custom

import cn.aing.uptags.service.title.ValidationResult

import cn.aing.uptags.command.core.TagsCommandContext

import cn.aing.uptags.Support
import org.bukkit.command.CommandSender
import org.bukkit.entity.Player
import java.util.Locale

internal class TagsCustomCommand(private val context: TagsCommandContext) {
    fun handle(sender: CommandSender, args: Array<out String>): Boolean {
        val player = sender as? Player ?: run {
            context.messageService.send(sender, "player-only")
            return true
        }
        if (!context.requireUse(sender)) {
            return true
        }
        if (args.size < 3 || !args[1].equals("preview", true)) {
            sendPreviewHelp(sender)
            return true
        }
        val action = args[2].lowercase(Locale.ROOT)
        if (action.startsWith("pick_")) {
            val index = action.removePrefix("pick_").toIntOrNull()
            if (index == null) {
                sendPreviewHelp(sender)
                return true
            }
            val result = context.customTitleService.selectManualColor(player, index)
            if (!dispatchValidation(player, result)) {
                return true
            }
            sendManualPicker(player)
            return true
        }
        when (action) {
            "money", "title_coin", "points" -> {
                val result = context.customTitleService.handleInput(player, args[2])
                result.messageKey?.let { key ->
                    when (val payload = result.args) {
                        null -> context.messageService.send(player, key)
                        is Array<*> -> context.messageService.send(player, key, *payload)
                        else -> context.messageService.send(player, key, payload)
                    }
                }
            }
            "single" -> {
                val result = context.customTitleService.selectPaletteLibrary(player, 1)
                if (!dispatchValidation(player, result)) {
                    return true
                }
                sendPreview(player)
            }
            "double" -> {
                val result = context.customTitleService.selectPaletteLibrary(player, 2)
                if (!dispatchValidation(player, result)) {
                    return true
                }
                sendPreview(player)
            }
            "triple" -> {
                val result = context.customTitleService.selectPaletteLibrary(player, 3)
                if (!dispatchValidation(player, result)) {
                    return true
                }
                sendPreview(player)
            }
            "quad" -> {
                val result = context.customTitleService.selectPaletteLibrary(player, 4)
                if (!dispatchValidation(player, result)) {
                    return true
                }
                sendPreview(player)
            }
            "auto" -> {
                val result = context.customTitleService.autoComposePalette(player)
                if (!dispatchValidation(player, result)) {
                    return true
                }
                sendPreview(player)
            }
            "manual" -> sendManualLibraryChooser(player)
            "manual_single" -> {
                val result = context.customTitleService.beginManualPaletteEditing(player, 1)
                if (!dispatchValidation(player, result)) {
                    return true
                }
                sendManualPicker(player)
            }
            "manual_double" -> {
                val result = context.customTitleService.beginManualPaletteEditing(player, 2)
                if (!dispatchValidation(player, result)) {
                    return true
                }
                sendManualPicker(player)
            }
            "manual_triple" -> {
                val result = context.customTitleService.beginManualPaletteEditing(player, 3)
                if (!dispatchValidation(player, result)) {
                    return true
                }
                sendManualPicker(player)
            }
            "manual_quad" -> {
                val result = context.customTitleService.beginManualPaletteEditing(player, 4)
                if (!dispatchValidation(player, result)) {
                    return true
                }
                sendManualPicker(player)
            }
            "manual_choose_back" -> sendPreview(player)
            "manual_remove" -> {
                val result = context.customTitleService.removeLastManualColor(player)
                if (!dispatchValidation(player, result)) {
                    return true
                }
                sendManualPicker(player)
            }
            "manual_clear" -> {
                val result = context.customTitleService.clearManualColors(player)
                if (!dispatchValidation(player, result)) {
                    return true
                }
                sendManualPicker(player)
            }
            "manual_page_prev" -> {
                val result = context.customTitleService.changeManualColorPage(player, -1)
                if (!dispatchValidation(player, result)) {
                    return true
                }
                sendManualPicker(player)
            }
            "manual_page_next" -> {
                val result = context.customTitleService.changeManualColorPage(player, 1)
                if (!dispatchValidation(player, result)) {
                    return true
                }
                sendManualPicker(player)
            }
            "manual_done" -> {
                val result = context.customTitleService.finishManualPaletteEditing(player)
                if (!dispatchValidation(player, result)) {
                    return true
                }
                sendPreview(player)
            }
            "manual_back" -> {
                val result = context.customTitleService.cancelManualPaletteEditing(player)
                if (!dispatchValidation(player, result)) {
                    return true
                }
                sendPreview(player)
            }
            "prev" -> {
                context.customTitleService.cycleScheme(player, -1)
                sendPreview(player)
            }
            "next" -> {
                context.customTitleService.cycleScheme(player, 1)
                sendPreview(player)
            }
            "confirm" -> {
                val result = context.customTitleService.confirm(player)
                result.messageKey?.let { key ->
                    when (val payload = result.args) {
                        null -> context.messageService.send(player, key)
                        is Array<*> -> context.messageService.send(player, key, *payload)
                        else -> context.messageService.send(player, key, payload)
                    }
                }
                if (result.success && context.customTitleService.activeDraft(player)?.stage?.name == "CHOOSE_GROUP") {
                    context.menuService.openCustomTitleGroupSelector(player)
                } else if (result.success && context.customTitleService.activeDraft(player) != null) {
                    sendPreview(player)
                }
            }
            "cancel" -> context.customTitleService.cancelDraft(player)
            else -> sendPreviewHelp(sender)
        }
        return true
    }

    private fun sendPreview(player: Player) {
        context.clickableMessageService.sendPreviewControls(
            player,
            context.customTitleService.previewMessage(player),
            context.customTitleService.previewPalette(player),
            context.customTitleService.currentPaletteLibrary(player),
            context.customTitleService.availablePaletteLibraries(player),
            context.customTitleService.manualColorsAllowed(player),
        )
    }

    private fun dispatchValidation(player: Player, result: cn.aing.uptags.service.title.ValidationResult): Boolean {
        result.messageKey?.let { key ->
            when (val payload = result.args) {
                null -> context.messageService.send(player, key)
                is Array<*> -> context.messageService.send(player, key, *payload)
                else -> context.messageService.send(player, key, payload)
            }
        }
        return result.success
    }

    private fun sendManualPicker(player: Player) {
        val page = context.customTitleService.manualColorPage(player)
        context.clickableMessageService.sendManualColorPicker(
            player,
            context.customTitleService.draftRawText(player),
            context.customTitleService.previewMessage(player),
            context.customTitleService.selectedManualColors(player),
            page.colors,
            page.pageIndex,
            page.totalPages,
            page.pageOffset,
            context.customTitleService.manualPaletteTarget(player) ?: context.customTitleService.currentPaletteLibrary(player) ?: 1,
        )
    }

    private fun sendManualLibraryChooser(player: Player) {
        context.clickableMessageService.sendManualLibraryChooser(
            player,
            context.customTitleService.availablePaletteLibraries(player),
        )
    }

    private fun sendPreviewHelp(sender: CommandSender) {
        sender.sendMessage(
            Support.color("&#E2E8F0用法: &#FDE047/tags custom preview <money|title_coin|points|single|double|triple|quad|manual|auto|prev|next|confirm|cancel>"),
        )
    }
}
