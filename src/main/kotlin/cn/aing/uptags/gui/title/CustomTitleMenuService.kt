package cn.aing.uptags.gui.title

import cn.aing.uptags.service.title.ValidationResult

import cn.aing.uptags.service.title.CustomTitleStage

import cn.aing.uptags.gui.common.MenuType

import cn.aing.uptags.gui.common.MenuSession

import cn.aing.uptags.gui.common.MenuHolder

import cn.aing.uptags.Support
import cn.aing.uptags.config.ConfigRegistry
import cn.aing.uptags.config.MessageService
import cn.aing.uptags.model.config.CurrencyType
import cn.aing.uptags.service.message.ClickableMessageService
import cn.aing.uptags.service.title.CustomTitleService
import cn.aing.uptags.service.shop.ShopService
import org.bukkit.Bukkit
import org.bukkit.entity.Player

internal class CustomTitleMenuService(
    private val config: ConfigRegistry,
    private val shopService: ShopService,
    private val messageService: MessageService,
    private val customTitleService: CustomTitleService,
    private val clickableMessageService: ClickableMessageService,
    private val currencyName: (CurrencyType) -> String,
    private val openShop: (Player, Int) -> Unit,
) {
    fun openCurrencySelector(player: Player) {
        val layout = config.customTitleCurrencyLayout
        val holder = MenuHolder()
        val inventory = Bukkit.createInventory(holder, layout.size(), Support.color(layout.title))
        val session = MenuSession(MenuType.CUSTOM_CURRENCY, inventory, 0, null, null)
        holder.session = session
        val optionSlots = mutableListOf<Int>()
        var backSlot: Int? = null

        layout.plain.forEachIndexed { row, line ->
            line.forEachIndexed { column, token ->
                val slot = row * 9 + column
                when (token) {
                    '#', 'X' -> {
                        val key = layout.keys[token] ?: return@forEachIndexed
                        val template = key.base ?: return@forEachIndexed
                        inventory.setItem(slot, Support.createItem(template.material, template.name, template.lore))
                    }
                    '@' -> optionSlots += slot
                    'B' -> {
                        backSlot = slot
                        val key = layout.keys[token]
                        val template = key?.base
                        if (template != null) {
                            inventory.setItem(slot, Support.createItem(template.material, template.name, template.lore))
                        }
                    }
                }
            }
        }

        fun addOption(slot: Int, currency: CurrencyType, name: String, amount: Double) {
            val template = layout.templates["currency"] ?: return
            val placeholders = mapOf(
                "currency_name" to currencyName(currency),
                "currency_price" to Support.formatDouble(amount),
                "currency_display" to name,
            )
            inventory.setItem(
                slot,
                Support.createItem(template.material, template.name, template.lore, placeholders),
            )
            session.actions[slot] = {
                val keyword = when (currency) {
                    CurrencyType.MONEY -> "money"
                    CurrencyType.TITLE_COIN -> "title_coin"
                    CurrencyType.POINTS -> "points"
                }
                val result = customTitleService.handleInput(player, keyword)
                if (result.messageKey != null) {
                    when (val args = result.args) {
                        null -> messageService.send(player, result.messageKey)
                        is Array<*> -> messageService.send(player, result.messageKey, *args)
                        else -> messageService.send(player, result.messageKey, args)
                    }
                } else {
                    player.closeInventory()
                }
            }
        }

        val dynamicChoices = customTitleService.currencyChoices()
        if (dynamicChoices.isNotEmpty()) {
            optionSlots.zip(dynamicChoices).forEach { (slot, choice) ->
                val (currency, amount) = choice
                addOption(slot, currency, "${currencyName(currency)} ${Support.formatDouble(amount)}", amount)
            }

            backSlot?.let { slot ->
                session.actions[slot] = {
                    customTitleService.cancelDraft(player, notify = false)
                    openShop(player, 0)
                }
            }

            player.openInventory(inventory)
            return
        }

        optionSlots.zip(
            listOf(
                Triple(CurrencyType.MONEY, "金币 888888", 888888.0),
                Triple(CurrencyType.TITLE_COIN, "称号币 100", 100.0),
                Triple(CurrencyType.POINTS, "点券 35", 35.0),
            ),
        ).forEach { (slot, option) ->
            addOption(slot, option.first, option.second, option.third)
        }

        backSlot?.let { slot ->
            session.actions[slot] = {
                customTitleService.cancelDraft(player, notify = false)
                openShop(player, 0)
            }
        }

        player.openInventory(inventory)
    }

    fun openColorEditor(player: Player) {
        val draft = customTitleService.activeDraft(player) ?: run {
            messageService.send(player, "custom-title-no-session")
            return
        }
        val targetColors = (customTitleService.manualPaletteTarget(player)
            ?: customTitleService.currentPaletteLibrary(player)
            ?: 1).coerceIn(1, 4)
        val layout = config.customTitleColorLayout
        val holder = MenuHolder()
        val inventory = Bukkit.createInventory(holder, layout.size(), Support.color(layout.title))
        val session = MenuSession(MenuType.CUSTOM_TITLE_COLOR, inventory, 0, null, null)
        holder.session = session

        val hexSlots = mutableListOf<Int>()
        val opSlots = mutableListOf<Int>()
        val previewSlots = mutableListOf<Int>()

        layout.plain.forEachIndexed { row, line ->
            line.forEachIndexed { column, token ->
                val slot = row * 9 + column
                when (token) {
                    '#', 'X' -> {
                        val key = layout.keys[token] ?: return@forEachIndexed
                        val template = key.base
                        if (template != null) {
                            inventory.setItem(slot, Support.createItem(template.material, template.name, template.lore))
                        }
                    }
                    'P' -> previewSlots += slot
                    '@' -> {
                        if (hexSlots.size < 16) {
                            hexSlots += slot
                        } else {
                            opSlots += slot
                        }
                    }
                }
            }
        }

        val normalizedManual = draft.manualColors.mapNotNull(Support::normalizeHex).take(targetColors)
        draft.manualColors.clear()
        draft.manualColors.addAll(normalizedManual)
        draft.manualColorTarget = targetColors

        val currentHex = if (draft.hexBuffer.length == 6) Support.normalizeHex("#${draft.hexBuffer}") else null
        val previewPalette = if (currentHex != null && normalizedManual.size < targetColors) {
            normalizedManual + currentHex
        } else {
            normalizedManual
        }
        val previewText = Support.renderPaletteText(
            Support.decorateCustomTitle(draft.rawText),
            previewPalette.ifEmpty { customTitleService.previewPalette(player) },
        )
        val previewPlaceholders = mapOf(
            "title_text" to Support.decorateCustomTitle(draft.rawText),
            "title_preview" to previewText,
            "title_color" to (currentHex ?: normalizedManual.lastOrNull() ?: "未选择"),
            "title_input" to draft.hexBuffer.padEnd(6, '_'),
            "title_palette" to if (normalizedManual.isEmpty()) "未选择" else normalizedManual.joinToString(", "),
            "title_target_count" to targetColors.toString(),
            "title_selected_count" to normalizedManual.size.toString(),
            "title_remaining_count" to (targetColors - normalizedManual.size).coerceAtLeast(0).toString(),
            "title_status" to if (normalizedManual.size == targetColors) "已选满，可直接确认" else "还需选择 ${(targetColors - normalizedManual.size).coerceAtLeast(0)} 个颜色",
        )
        layout.keys['P']?.base?.let { previewTemplate ->
            previewSlots.forEach { slot ->
                inventory.setItem(
                    slot,
                    Support.createItem(previewTemplate.material, previewTemplate.name, previewTemplate.lore, previewPlaceholders),
                )
            }
        }

        val hexTemplate = layout.templates["hex"]
        val digits = "0123456789ABCDEF"
        if (hexTemplate != null) {
            hexSlots.forEachIndexed { index, slot ->
                if (index >= digits.length) return@forEachIndexed
                val digit = digits[index].toString()
                inventory.setItem(
                    slot,
                    Support.createItem(
                        CustomTitleMenuDefaults.hexDigitMaterial(digit.first()),
                        hexTemplate.name,
                        hexTemplate.lore,
                        mapOf("title_digit" to digit, "title_input" to draft.hexBuffer.padEnd(6, '_')),
                    ),
                )
                session.actions[slot] = action@{
                    val current = customTitleService.activeDraft(player) ?: run {
                        player.closeInventory()
                        return@action
                    }
                    if (current.hexBuffer.length < 6) {
                        current.hexBuffer += digit
                    }
                    openColorEditor(player)
                }
            }
        }

        val opNames = listOf("add", "replace", "remove", "backspace", "clear-input", "clear-palette", "confirm", "back")
        opNames.zip(opSlots).forEach { (name, slot) ->
            val template = layout.templates[name]
                ?: CustomTitleMenuDefaults.manualEditorTemplate(name)
                ?: CustomTitleMenuDefaults.editorTemplate(name)
                ?: return@forEach
            inventory.setItem(slot, Support.createItem(template.material, template.name, template.lore, previewPlaceholders))
            session.actions[slot] = handler@{
                val current = customTitleService.activeDraft(player)
                if (current == null) {
                    player.closeInventory()
                    return@handler
                }
                when (name) {
                    "add" -> {
                        val normalized = Support.normalizeHex("#${current.hexBuffer}")
                        if (current.hexBuffer.length != 6 || normalized == null) {
                            messageService.send(player, "custom-title-invalid-color", current.hexBuffer.ifBlank { "------" })
                        } else if (current.manualColors.size >= targetColors) {
                            messageService.send(player, "custom-title-manual-limit", targetColors)
                        } else {
                            current.manualColors.add(normalized)
                            current.hexBuffer = ""
                            current.manualColorTarget = targetColors
                        }
                        openColorEditor(player)
                    }
                    "replace" -> {
                        val normalized = Support.normalizeHex("#${current.hexBuffer}")
                        if (current.hexBuffer.length != 6 || normalized == null) {
                            messageService.send(player, "custom-title-invalid-color", current.hexBuffer.ifBlank { "------" })
                        } else if (current.manualColors.isNotEmpty()) {
                            current.manualColors[current.manualColors.lastIndex] = normalized
                            current.hexBuffer = ""
                        } else {
                            current.manualColors.add(normalized)
                            current.hexBuffer = ""
                        }
                        current.manualColorTarget = targetColors
                        openColorEditor(player)
                    }
                    "remove" -> {
                        if (current.manualColors.isNotEmpty()) {
                            current.manualColors.removeAt(current.manualColors.lastIndex)
                        }
                        openColorEditor(player)
                    }
                    "backspace" -> {
                        if (current.hexBuffer.isNotEmpty()) {
                            current.hexBuffer = current.hexBuffer.dropLast(1)
                        }
                        openColorEditor(player)
                    }
                    "clear-input" -> {
                        current.hexBuffer = ""
                        openColorEditor(player)
                    }
                    "clear-palette" -> {
                        current.hexBuffer = ""
                        current.manualColors.clear()
                        current.manualColorTarget = targetColors
                        openColorEditor(player)
                    }
                    "confirm" -> {
                        val finalPalette = current.manualColors.mapNotNull(Support::normalizeHex).toMutableList()
                        val pendingColor = Support.normalizeHex("#${current.hexBuffer}")
                        if (current.hexBuffer.isNotEmpty() && (current.hexBuffer.length != 6 || pendingColor == null)) {
                            messageService.send(player, "custom-title-invalid-color", current.hexBuffer.ifBlank { "------" })
                            openColorEditor(player)
                            return@handler
                        }
                        if (pendingColor != null && finalPalette.size < targetColors) {
                            finalPalette += pendingColor
                        }
                        if (finalPalette.size != targetColors) {
                            messageService.send(player, "custom-title-manual-count-mismatch", targetColors, finalPalette.size)
                            openColorEditor(player)
                            return@handler
                        }
                        customTitleService.applyManualColors(current, finalPalette)
                        val result = customTitleService.confirm(player)
                        if (!dispatchValidationResult(player, result)) {
                            openColorEditor(player)
                        } else if (customTitleService.activeDraft(player)?.stage == CustomTitleStage.CHOOSE_GROUP) {
                            openGroupSelector(player)
                        } else {
                            player.closeInventory()
                        }
                    }
                    "back" -> {
                        current.hexBuffer = ""
                        current.manualColorTarget = null
                        player.closeInventory()
                        sendCustomPreview(player)
                    }
                }
            }
        }
        opSlots.drop(opNames.size).forEach { slot ->
            inventory.setItem(slot, Support.createItem("BLACK_STAINED_GLASS_PANE", " ", emptyList()))
        }

        player.openInventory(inventory)
    }

    fun openGroupSelector(player: Player) {
        customTitleService.activeDraft(player) ?: run {
            messageService.send(player, "custom-title-no-session")
            return
        }
        val layout = config.customTitleGroupLayout
        val holder = MenuHolder()
        val inventory = Bukkit.createInventory(holder, layout.size(), Support.color(layout.title))
        val session = MenuSession(MenuType.CUSTOM_TITLE_GROUP, inventory, 0, null, null)
        holder.session = session

        val groups = config.upgradeGroups.values.toList()
        var index = 0

        layout.plain.forEachIndexed { row, line ->
            line.forEachIndexed { column, token ->
                val slot = row * 9 + column
                when (token) {
                    '#', 'X' -> {
                        val key = layout.keys[token] ?: return@forEachIndexed
                        val template = key.base
                        if (template != null) {
                            inventory.setItem(
                                slot,
                                Support.createItem(template.material, template.name, template.lore),
                            )
                        }
                    }
                    '@' -> {
                        if (index >= groups.size) return@forEachIndexed
                        val group = groups[index++]
                        val template = layout.templates["group"] ?: return@forEachIndexed
                        val placeholders = mapOf(
                            "group_id" to group.id,
                            "group_name" to group.name,
                            "group_display" to group.display,
                        )
                        inventory.setItem(
                            slot,
                            Support.createItem(
                                template.material,
                                template.name,
                                template.lore,
                                placeholders,
                            ),
                        )
                        session.actions[slot] = click@{
                            val result = customTitleService.handleInput(player, group.id)
                            if (!dispatchValidationResult(player, result)) {
                                return@click
                            }
                            player.closeInventory()
                        }
                    }
                    'B' -> {
                        val key = layout.keys[token]
                        val template = key?.base
                        if (template != null) {
                            inventory.setItem(
                                slot,
                                Support.createItem(template.material, template.name, template.lore),
                            )
                        }
                        session.actions[slot] = {
                            player.closeInventory()
                            sendCustomPreview(player)
                        }
                    }
                }
            }
        }

        player.openInventory(inventory)
    }

    fun startFlow(player: Player) {
        val customProducts = shopService.visibleCustomProducts(player)
        if (customProducts.size == 1) {
            if (shopService.startCustomFlow(player, customProducts.first().id)) {
                player.closeInventory()
            }
            return
        }
        if (customProducts.size > 1) {
            messageService.send(player, "shop-custom-select-product")
            return
        }
        val presets = config.customTitleSettings.presets
        val presetId = presets.keys.firstOrNull()
        if (presetId == null) {
            messageService.send(player, "custom-title-invalid-preset")
            return
        }
        customTitleService.cancelDraft(player, notify = false)
        if (!customTitleService.startDraft(player, presetId)) {
            messageService.send(player, "custom-title-invalid-preset")
            return
        }
        player.closeInventory()
        messageService.send(player, "shop-custom-start")
        openCurrencySelector(player)
    }

    fun customButtonPlaceholders(player: Player): Map<String, String> {
        val customProducts = shopService.visibleCustomProducts(player)
        val entryHint: String
        val priceSummary: String
        val flowSummary: String

        when {
            customProducts.size > 1 -> {
                entryHint = "点击后请先从商店列表选择具体定制商品"
                priceSummary = "当前上架 ${customProducts.size} 个自定义商品，价格以列表显示为准"
                flowSummary = "选定商品后，将直接进入对应的定制流程"
            }
            customProducts.size == 1 -> {
                val product = customProducts.first()
                entryHint = "点击后将直接进入当前上架商品的定制流程"
                priceSummary =
                    "当前商品: ${Support.stripColor(product.icon.name)} / ${Support.formatDouble(product.cost.priceForLevel(1))} ${currencyName(product.cost.type)}"
                flowSummary = "输入称号文本后，再继续编辑颜色并确认"
            }
            else -> {
                val choices = customTitleService.currencyChoices()
                val summary = if (choices.isEmpty()) {
                    "当前没有可用支付方式"
                } else {
                    choices.joinToString(" / ") { (currency, amount) ->
                        "${currencyName(currency)} ${Support.formatDouble(amount)}"
                    }
                }
                entryHint = "点击后进入支付方式选择界面"
                priceSummary = "可选: $summary"
                flowSummary = "输入称号文本后，再继续编辑颜色并确认"
            }
        }

        return mapOf(
            "custom_entry_hint" to entryHint,
            "custom_price_summary" to priceSummary,
            "custom_flow_summary" to flowSummary,
        )
    }

    private fun dispatchValidationResult(player: Player, result: ValidationResult): Boolean {
        result.messageKey?.let { key ->
            when (val args = result.args) {
                null -> messageService.send(player, key)
                is Array<*> -> messageService.send(player, key, *args)
                else -> messageService.send(player, key, args)
            }
        }
        return result.success
    }

    private fun sendCustomPreview(player: Player) {
        clickableMessageService.sendPreviewControls(
            player,
            customTitleService.previewMessage(player),
            customTitleService.previewPalette(player),
            customTitleService.currentPaletteLibrary(player),
            customTitleService.availablePaletteLibraries(player),
            customTitleService.manualColorsAllowed(player),
        )
    }

}
