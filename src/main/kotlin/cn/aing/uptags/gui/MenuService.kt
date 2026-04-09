package cn.aing.uptags.gui

import cn.aing.uptags.Support
import cn.aing.uptags.config.ConfigRegistry
import cn.aing.uptags.config.MessageService
import cn.aing.uptags.model.config.CurrencyType
import cn.aing.uptags.model.config.GuiLayout
import cn.aing.uptags.model.config.GuiTemplate
import cn.aing.uptags.model.config.TagDefinition
import cn.aing.uptags.model.runtime.ScrollKind
import cn.aing.uptags.model.runtime.ScrollSelectionContext
import cn.aing.uptags.model.runtime.TitleKind
import cn.aing.uptags.service.ScrollService
import cn.aing.uptags.service.ShopService
import cn.aing.uptags.service.TagService
import cn.aing.uptags.service.CustomTitleService
import cn.aing.uptags.service.ClickableMessageService
import cn.aing.uptags.service.CustomTitleStage
import org.bukkit.Bukkit
import org.bukkit.Material
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import org.bukkit.event.inventory.InventoryClickEvent
import org.bukkit.inventory.Inventory
import org.bukkit.inventory.InventoryHolder
import org.bukkit.inventory.ItemStack
import org.bukkit.plugin.java.JavaPlugin
import java.util.LinkedHashMap
import java.util.LinkedHashSet

class MenuService(
    private val plugin: JavaPlugin,
    private val config: ConfigRegistry,
    private val tagService: TagService,
    private val scrollService: ScrollService,
    private val shopService: ShopService,
    private val messageService: MessageService,
    private val customTitleService: CustomTitleService,
    private val clickableMessageService: ClickableMessageService,
) : Listener {

    fun openWarehouse(player: Player, page: Int) {
        val layout = config.warehouseLayout
        val titles = tagService.visibleTitles(player)
        val session = createSession(MenuType.WAREHOUSE, layout, page, null, null)
        fillStatic(player, layout, session, page, titles.size)
        val slots = layout.entrySlots()
        val start = page * slots.size
        for (offset in slots.indices) {
            val index = start + offset
            if (index >= titles.size) continue
            val title = titles[index]
            val owned = title.owned
            val template = layout.templates[if (owned) "tag-owned" else "tag-locked"]
            val placeholders = linkedMapOf(
                "tag_display" to title.display,
                "tag_description" to title.description.joinToString("\n"),
                "tag_rarity" to title.rarityDisplay,
                "tag_buff_count" to tagService.tagBuffCount(player, title.id).toString(),
                "tag_particle_count" to tagService.tagParticleCount(player, title.id).toString(),
                "tag_buffs" to tagService.tagBuffsDisplay(player, title.id),
                "tag_particles" to tagService.tagParticlesDisplay(player, title.id),
            )
            val slot = slots[offset]
            session.inventory.setItem(
                slot,
                templateItem(template, placeholders, tagService.currentTagId(player) == title.id),
            )
            if (owned) {
                session.actions[slot] = { click ->
                    if (click.isLeftClick) {
                        if (title.kind == TitleKind.CUSTOM) {
                            tagService.equipCustomTitle(player, title.id)
                        } else {
                            tagService.equipTag(player, title.id)
                        }
                        openWarehouse(player, page)
                    } else if (click.isRightClick) {
                        openUpgrade(player, title.id, 0)
                    }
                }
            }
        }
        player.openInventory(session.inventory)
    }

    fun openShop(player: Player, page: Int) {
        val layout = config.shopLayout
        val products = shopService.visibleProducts(player)
        val session = createSession(MenuType.SHOP, layout, page, null, null)
        fillStatic(player, layout, session, page, products.size)
        val slots = layout.entrySlots()
        val start = page * slots.size
        for (offset in slots.indices) {
            val index = start + offset
            if (index >= products.size) continue
            val product = products[index]
            val template = layout.templates["product-available"]
            val placeholders = mapOf(
                "product_name" to product.icon.name,
                "product_lore" to product.icon.lore.joinToString("\n"),
                "product_price" to Support.formatDouble(product.cost.priceForLevel(1)),
                "product_currency" to currencyName(product.cost.type),
            )
            val slot = slots[offset]
            session.inventory.setItem(
                slot,
                if (template == null) ItemStack(Material.PAPER) else Support.createItem(product.icon.material, template.name, template.lore, placeholders, false),
            )
            session.actions[slot] = {
                if (product.type.name == "CUSTOM") {
                    if (shopService.startCustomFlow(player, product.id)) {
                        player.closeInventory()
                    }
                } else if (shopService.buy(player, product.id)) {
                    openShop(player, page)
                }
            }
        }
        player.openInventory(session.inventory)
    }

    fun openUpgrade(player: Player, tagId: String, page: Int) {
        val tag = upgradeViewTag(player, tagId) ?: run {
            messageService.send(player, "tag-not-found", tagId)
            return
        }
        val layout = config.upgradeLayout
        val entries = buildUpgradeEntries(player, tag)
        val session = createSession(MenuType.UPGRADE, layout, page, tagId, null)
        fillStatic(player, layout, session, page, entries.size)
        val slots = layout.entrySlots()
        val start = page * slots.size
        for (offset in slots.indices) {
            val index = start + offset
            if (index >= entries.size) continue
            val entry = entries[index]
            val slot = slots[offset]
            session.inventory.setItem(slot, entry.item)
            session.actions[slot] = { click ->
                when (entry.kind) {
                    EntryKind.BUFF -> {
                        if (click.isLeftClick) tagService.upgradeBuff(player, tagId, entry.id) else if (click.isRightClick) tagService.toggleBuff(player, tagId, entry.id)
                        openUpgrade(player, tagId, page)
                    }
                    EntryKind.PARTICLE -> {
                        if (click.isLeftClick) tagService.buyParticle(player, tagId, entry.id) else if (click.isRightClick) tagService.selectParticle(player, tagId, entry.id)
                        openUpgrade(player, tagId, page)
                    }
                }
            }
        }
        player.openInventory(session.inventory)
    }

    fun openScrollSelection(player: Player, context: ScrollSelectionContext, page: Int) {
        val layout = config.warehouseLayout
        val titles = scrollService.eligibleTitles(player, context)
        if (titles.isEmpty()) {
            messageService.send(player, "scroll-no-eligible-tags")
            return
        }
        val session = createSession(
            MenuType.SCROLL_SELECT,
            layout,
            page,
            null,
            context,
            "&0鍗囩骇鍗?- 閫夋嫨绉板彿",
        )
        fillStatic(player, layout, session, page, titles.size)
        val slots = layout.entrySlots()
        val start = page * slots.size
        for (offset in slots.indices) {
            val index = start + offset
            if (index >= titles.size) continue
            val title = titles[index]
            val tag = title
            val slot = slots[offset]
            val targetName = scrollService.displayName(context.kind, context.targetId)
            session.inventory.setItem(
                slot,
                Support.createItem(
                    "NAME_TAG",
                    "&f${title.display}",
                    listOf(
                        "&7称号名称: &f${Support.stripColor(tag.display)}",
                        "&7卷轴类型: &f${if (context.kind == ScrollKind.BUFF) "Buff 升级卷" else "粒子解锁卷"}",
                        "&7目标内容: &f$targetName",
                        "&e左键对这个称号使用升级卷",
                    ),
                ),
            )
            session.actions[slot] = {
                if (scrollService.apply(player, context, tag.id)) {
                    player.closeInventory()
                }
            }
        }
        player.openInventory(session.inventory)
    }

    fun openCustomCurrencySelector(player: Player) {
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
                Triple(CurrencyType.MONEY, "閲戝竵 888888", 888888.0),
                Triple(CurrencyType.TITLE_COIN, "绉板彿甯?100", 100.0),
                Triple(CurrencyType.POINTS, "鐐瑰埜 35", 35.0),
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

    fun openCustomTitleColorEditor(player: Player) {
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
            draft.rawText,
            previewPalette.ifEmpty { customTitleService.previewPalette(player) },
        )
        val previewPlaceholders = mapOf(
            "title_text" to draft.rawText,
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
                        hexDigitMaterial(digit.first()),
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
                    openCustomTitleColorEditor(player)
                }
            }
        }

        val opNames = listOf("add", "replace", "remove", "backspace", "clear-input", "clear-palette", "confirm", "back")
        opNames.zip(opSlots).forEach { (name, slot) ->
            val template = layout.templates[name] ?: defaultManualEditorTemplate(name) ?: defaultEditorTemplate(name) ?: return@forEach
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
                        openCustomTitleColorEditor(player)
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
                        openCustomTitleColorEditor(player)
                    }
                    "remove" -> {
                        if (current.manualColors.isNotEmpty()) {
                            current.manualColors.removeAt(current.manualColors.lastIndex)
                        }
                        openCustomTitleColorEditor(player)
                    }
                    "backspace" -> {
                        if (current.hexBuffer.isNotEmpty()) {
                            current.hexBuffer = current.hexBuffer.dropLast(1)
                        }
                        openCustomTitleColorEditor(player)
                    }
                    "clear-input" -> {
                        current.hexBuffer = ""
                        openCustomTitleColorEditor(player)
                    }
                    "clear-palette" -> {
                        current.hexBuffer = ""
                        current.manualColors.clear()
                        current.manualColorTarget = targetColors
                        openCustomTitleColorEditor(player)
                    }
                    "confirm" -> {
                        val finalPalette = current.manualColors.mapNotNull(Support::normalizeHex).toMutableList()
                        val pendingColor = Support.normalizeHex("#${current.hexBuffer}")
                        if (current.hexBuffer.isNotEmpty() && (current.hexBuffer.length != 6 || pendingColor == null)) {
                            messageService.send(player, "custom-title-invalid-color", current.hexBuffer.ifBlank { "------" })
                            openCustomTitleColorEditor(player)
                            return@handler
                        }
                        if (pendingColor != null && finalPalette.size < targetColors) {
                            finalPalette += pendingColor
                        }
                        if (finalPalette.size != targetColors) {
                            messageService.send(player, "custom-title-manual-count-mismatch", targetColors, finalPalette.size)
                            openCustomTitleColorEditor(player)
                            return@handler
                        }
                        customTitleService.applyManualColors(current, finalPalette)
                        val result = customTitleService.confirm(player)
                        if (!dispatchValidationResult(player, result)) {
                            openCustomTitleColorEditor(player)
                        } else if (customTitleService.activeDraft(player)?.stage == CustomTitleStage.CHOOSE_GROUP) {
                            openCustomTitleGroupSelector(player)
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

    private fun openCustomTitleColorEditorLegacy(player: Player) {
        val draft = customTitleService.activeDraft(player) ?: run {
            messageService.send(player, "custom-title-no-session")
            return
        }
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
                            inventory.setItem(
                                slot,
                                Support.createItem(template.material, template.name, template.lore),
                            )
                        }
                    }
                    'P' -> {
                        previewSlots += slot
                    }
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

        val currentHex = if (draft.hexBuffer.length == 6) Support.normalizeHex("#${draft.hexBuffer}") else null
        val previewText = customTitleService.previewText(player) ?: ""
        val previewPlaceholders = mapOf(
            "title_text" to draft.rawText,
            "title_preview" to previewText,
            "title_color" to (currentHex ?: draft.manualColors.firstOrNull() ?: "鏈€夋嫨"),
            "title_input" to draft.hexBuffer.padEnd(6, '_'),
        )
        val previewKey = layout.keys['P']
        val previewTemplate = previewKey?.base
        if (previewTemplate != null) {
            previewSlots.forEach { slot ->
                inventory.setItem(
                    slot,
                    Support.createItem(
                        previewTemplate.material,
                        previewTemplate.name,
                        previewTemplate.lore,
                        previewPlaceholders,
                    ),
                )
            }
        }

        val hexTemplate = layout.templates["hex"]
        val digits = "0123456789ABCDEF"
        if (hexTemplate != null) {
            hexSlots.forEachIndexed { index, slot ->
                if (index >= digits.length) return@forEachIndexed
                val digit = digits[index].toString()
                val placeholders = mapOf(
                    "title_digit" to digit,
                    "title_input" to draft.hexBuffer.padEnd(6, '_'),
                )
                inventory.setItem(
                    slot,
                    Support.createItem(
                        hexDigitMaterial(digit.first()),
                        hexTemplate.name,
                        hexTemplate.lore,
                        placeholders,
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
                    openCustomTitleColorEditor(player)
                }
            }
        }

        val opNames = listOf("backspace", "clear-input", "clear-palette", "confirm", "back")
        opNames.zip(opSlots).forEach { (name, slot) ->
            val template = layout.templates[name] ?: defaultEditorTemplate(name) ?: return@forEach
            inventory.setItem(
                slot,
                Support.createItem(template.material, template.name, template.lore),
            )
            session.actions[slot] = handler@{
                val current = customTitleService.activeDraft(player)
                if (current == null) {
                    player.closeInventory()
                    return@handler
                }
                when (name) {
                    "backspace" -> {
                        if (current.hexBuffer.isNotEmpty()) {
                            current.hexBuffer = current.hexBuffer.dropLast(1)
                        }
                        openCustomTitleColorEditor(player)
                    }
                    "clear-input" -> {
                        current.hexBuffer = ""
                        openCustomTitleColorEditor(player)
                    }
                    "clear-palette" -> {
                        current.hexBuffer = ""
                        current.manualColors.clear()
                        openCustomTitleColorEditor(player)
                    }
                    "confirm" -> {
                        val selectedColor = Support.normalizeHex("#${current.hexBuffer}") ?: current.manualColors.firstOrNull()
                        if (selectedColor == null) {
                            messageService.send(player, "custom-title-invalid-color", current.hexBuffer.ifBlank { "------" })
                            openCustomTitleColorEditor(player)
                        } else {
                            customTitleService.applyManualColors(current, listOf(selectedColor))
                            val result = customTitleService.confirm(player)
                            if (!dispatchValidationResult(player, result)) {
                                openCustomTitleColorEditor(player)
                            } else if (customTitleService.activeDraft(player)?.stage == cn.aing.uptags.service.CustomTitleStage.CHOOSE_GROUP) {
                                openCustomTitleGroupSelector(player)
                            } else {
                                player.closeInventory()
                            }
                        }
                    }
                    "back" -> {
                        customTitleService.cancelDraft(player, notify = false)
                        openShop(player, 0)
                    }
                }
            }
        }
        opSlots.drop(opNames.size).forEach { slot ->
            inventory.setItem(slot, Support.createItem("BLACK_STAINED_GLASS_PANE", " ", emptyList()))
        }

        player.openInventory(inventory)
    }

    fun openCustomTitleGroupSelector(player: Player) {
        val draft = customTitleService.activeDraft(player) ?: run {
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

    @EventHandler
    fun onInventoryClick(event: InventoryClickEvent) {
        val player = event.whoClicked as? Player ?: return
        val holder = event.view.topInventory.holder as? MenuHolder ?: return
        val session = holder.session
        if (event.clickedInventory == null || event.clickedInventory != event.view.topInventory) {
            return
        }
        event.isCancelled = true
        session.actions[event.slot]?.invoke(event)
    }

    private fun buildUpgradeEntries(player: Player, tag: TagDefinition): List<UpgradeEntry> {
        val entries = ArrayList<UpgradeEntry>()
        val progress = tagService.data(player).tagProgress[tag.id]
        val visibleBuffIds = LinkedHashSet(tagService.allowedBuffIds(tag))
        progress?.buffLevels?.keys?.forEach { visibleBuffIds += it }
        for (buffId in visibleBuffIds) {
            val buff = config.buffs[buffId] ?: continue
            val level = tagService.buffLevel(player, tag.id, buffId)
            val enabled = tagService.isBuffEnabled(player, tag.id, buffId)
            val maxed = level >= buff.maxLevel
            val price = buff.cost.priceForLevel(minOf(buff.maxLevel, level + 1))
            val template = config.upgradeLayout.templates["buff"] ?: continue
            val lore = if (maxed && template.loreMaxed.isNotEmpty()) template.loreMaxed else template.lore
            val placeholders = mapOf(
                "entry_display" to buff.display,
                "entry_current" to level.toString(),
                "entry_max" to buff.maxLevel.toString(),
                "entry_status" to if (maxed) "已满级" else if (buffId in tagService.allowedBuffIds(tag)) "可升级" else "卷轴附加",
                "entry_equip_state" to if (enabled) "已启用" else "未启用",
                "entry_buffs" to Support.color(buff.display) + " " + Support.roman(maxOf(1, if (level == 0) 1 else level)),
                "entry_points" to Support.formatDouble(price),
                "entry_currency" to currencyName(buff.cost.type),
                "entry_action" to if (maxed) "左键已无可升级项" else if (buffId in tagService.allowedBuffIds(tag)) "左键购买或用升级卷提升下一级" else "这条词条来自升级卷，仍可继续升级",
                "entry_right_action" to "右键切换启用 / 停用",
            )
            entries += UpgradeEntry(
                buffId,
                EntryKind.BUFF,
                Support.createItem(template.material, template.name, lore, placeholders, enabled),
            )
        }
        val visibleParticleIds = LinkedHashSet(tagService.allowedParticleIds(tag))
        progress?.ownedParticles?.forEach { visibleParticleIds += it }
        for (particleId in visibleParticleIds) {
            val particle = config.particles[particleId] ?: continue
            val owned = tagService.isParticleOwned(player, tag.id, particleId)
            val selected = tagService.isParticleSelected(player, tag.id, particleId)
            val template = config.upgradeLayout.templates["particle"] ?: continue
            val placeholders = mapOf(
                "entry_display" to particle.display,
                "entry_status" to if (owned) "已拥有" else if (particleId in tagService.allowedParticleIds(tag)) "未拥有" else "卷轴专属",
                "entry_equip_state" to if (selected) "宸查€変腑" else "鏈€変腑",
                "entry_points" to Support.formatDouble(particle.cost.priceForLevel(1)),
                "entry_currency" to currencyName(particle.cost.type),
                "entry_action" to if (owned) "左键已解锁" else if (particleId in tagService.allowedParticleIds(tag)) "左键购买或用升级卷解锁" else "该粒子可通过升级卷直接解锁",
                "entry_right_action" to "右键设为当前粒子 / 取消",
            )
            entries += UpgradeEntry(
                particleId,
                EntryKind.PARTICLE,
                Support.createItem(
                    template.material,
                    template.name,
                    template.lore,
                    placeholders,
                    selected,
                ),
            )
        }
        return entries
    }


    private fun dispatchValidationResult(player: Player, result: cn.aing.uptags.service.ValidationResult): Boolean {
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

    private fun defaultManualEditorTemplate(name: String): GuiTemplate? {
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

    private fun defaultEditorTemplate(name: String): GuiTemplate? {
        return when (name) {
            "prev-scheme" -> GuiTemplate("ARROW", "&#FDE047上一套", listOf("&#E2E8F0切换到上一套推荐配色"), emptyList())
            "next-scheme" -> GuiTemplate("ARROW", "&#FDE047下一套", listOf("&#E2E8F0切换到下一套推荐配色"), emptyList())
            "reroll-scheme" -> GuiTemplate("AMETHYST_SHARD", "&#60A5FA自动组合", listOf("&#E2E8F0重新生成一套参考配色"), emptyList())
            else -> null
        }
    }

    private fun hexDigitMaterial(digit: Char): String {
        return when (digit.uppercaseChar()) {
            '0', '1', '2', '3', '4', '5' -> "LIGHT_GRAY_WOOL"
            '6', '7', '8', '9' -> "GRAY_WOOL"
            'A', 'B', 'C' -> "PINK_WOOL"
            'D', 'E', 'F' -> "MAGENTA_WOOL"
            else -> "WHITE_WOOL"
        }
    }

    private fun currencyName(type: CurrencyType): String = when (type) {
        CurrencyType.POINTS -> "点券"
        CurrencyType.MONEY -> "金币"
        CurrencyType.TITLE_COIN -> "称号币"
    }

    private fun customButtonPlaceholders(player: Player): Map<String, String> {
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

    private fun createSession(
        type: MenuType,
        layout: GuiLayout,
        page: Int,
        tagId: String?,
        scrollContext: ScrollSelectionContext?,
        title: String = layout.title,
    ): MenuSession {
        val holder = MenuHolder()
        val inventory = Bukkit.createInventory(holder, layout.size(), Support.color(title))
        val session = MenuSession(type, inventory, page, tagId, scrollContext)
        holder.session = session
        return session
    }

    private fun fillStatic(
        player: Player,
        layout: GuiLayout,
        session: MenuSession,
        page: Int,
        entryCount: Int,
    ) {
        val pageSize = layout.entrySlots().size.coerceAtLeast(1)
        val maxPage = maxOf(0, kotlin.math.ceil(entryCount / pageSize.toDouble()).toInt() - 1)
        layout.plain.forEachIndexed { row, line ->
            line.forEachIndexed { column, token ->
                if (token == '@') return@forEachIndexed
                val slot = row * 9 + column
                val key = layout.keys[token] ?: return@forEachIndexed
                val function = key.iconFunction
                var template = key.base
                val placeholders = when {
                    function.equals("custom", true) -> customButtonPlaceholders(player)
                    else -> emptyMap()
                }
                when {
                    function.equals("last", true) -> {
                        template = if (page > 0 && key.has != null) key.has else key.normal
                        if (page > 0) session.actions[slot] = { changePage(player, session, page - 1) }
                    }
                    function.equals("next", true) -> {
                        template = if (page < maxPage && key.has != null) key.has else key.normal
                        if (page < maxPage) session.actions[slot] = { changePage(player, session, page + 1) }
                    }
                    function.equals("shop", true) -> session.actions[slot] = { openShop(player, 0) }
                    function.equals("back", true) -> session.actions[slot] = { goBack(player, session) }
                    function.equals("custom", true) -> session.actions[slot] = { startCustomTitleFlow(player) }
                }
                if (template != null) {
                    session.inventory.setItem(
                        slot,
                        Support.createItem(template.material, template.name, template.lore, placeholders),
                    )
                }
            }
        }
    }

    private fun startCustomTitleFlow(player: Player) {
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
        openCustomCurrencySelector(player)
    }

    private fun templateItem(
        template: GuiTemplate?,
        placeholders: Map<String, String>,
        glow: Boolean,
    ): ItemStack {
        if (template == null) return ItemStack(Material.PAPER)
        return Support.createItem(template.material, template.name, template.lore, placeholders, glow)
    }

    private fun upgradeViewTag(player: Player, tagId: String): TagDefinition? {
        config.tags[tagId]?.let { return it }
        val custom = tagService.data(player).customTitles[tagId] ?: return null
        return TagDefinition(
            id = custom.id,
            display = tagService.renderCustomTitle(custom),
            description = listOf("&#E2E8F0玩家自定义称号"),
            rarity = "CUSTOM",
            defaultUnlocked = true,
            upgradeGroups = custom.groupId?.let { mutableListOf(it) } ?: mutableListOf(),
            permission = null,
        )
    }

    private fun changePage(player: Player, session: MenuSession, page: Int) {
        when (session.type) {
            MenuType.WAREHOUSE -> openWarehouse(player, page)
            MenuType.SHOP -> openShop(player, page)
            MenuType.UPGRADE -> openUpgrade(player, session.tagId ?: return, page)
            MenuType.SCROLL_SELECT -> openScrollSelection(player, session.scrollContext ?: return, page)
            MenuType.CUSTOM_CURRENCY,
            MenuType.CUSTOM_TITLE_COLOR,
            MenuType.CUSTOM_TITLE_GROUP,
            -> {
                // these menus are single-page
            }
        }
    }

    private fun goBack(player: Player, session: MenuSession) {
        when (session.type) {
            MenuType.UPGRADE,
            MenuType.SCROLL_SELECT,
            MenuType.SHOP,
            MenuType.CUSTOM_CURRENCY,
            MenuType.CUSTOM_TITLE_COLOR,
            MenuType.CUSTOM_TITLE_GROUP,
            -> openWarehouse(player, 0)
            MenuType.WAREHOUSE -> player.closeInventory()
        }
    }

    private enum class MenuType {
        WAREHOUSE,
        SHOP,
        UPGRADE,
        SCROLL_SELECT,
        CUSTOM_CURRENCY,
        CUSTOM_TITLE_COLOR,
        CUSTOM_TITLE_GROUP,
    }

    private enum class EntryKind {
        BUFF,
        PARTICLE,
    }

    private data class UpgradeEntry(val id: String, val kind: EntryKind, val item: ItemStack)


    private class MenuHolder : InventoryHolder {
        lateinit var session: MenuSession
        override fun getInventory(): Inventory = session.inventory
    }

    private class MenuSession(
        val type: MenuType,
        val inventory: Inventory,
        val page: Int,
        val tagId: String?,
        val scrollContext: ScrollSelectionContext?,
    ) {
        val actions = LinkedHashMap<Int, (InventoryClickEvent) -> Unit>()
    }
}
