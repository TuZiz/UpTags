package cn.aing.uptags.gui

import cn.aing.uptags.Support
import cn.aing.uptags.command.AdminAccess
import cn.aing.uptags.config.ConfigRegistry
import cn.aing.uptags.config.MessageService
import cn.aing.uptags.model.config.CurrencyType
import cn.aing.uptags.model.config.GuiLayout
import cn.aing.uptags.model.config.GuiTemplate
import cn.aing.uptags.model.config.ItemTemplate
import cn.aing.uptags.model.runtime.TitleEntry
import cn.aing.uptags.model.runtime.ScrollSelectionContext
import cn.aing.uptags.model.runtime.TitleKind
import cn.aing.uptags.service.AdminActionResult
import cn.aing.uptags.service.ScrollService
import cn.aing.uptags.service.ShopService
import cn.aing.uptags.service.TagService
import cn.aing.uptags.service.CustomTitleService
import cn.aing.uptags.service.ClickableMessageService
import cn.aing.uptags.service.PlayerNameService
import org.bukkit.Bukkit
import org.bukkit.Material
import org.bukkit.OfflinePlayer
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import org.bukkit.event.inventory.InventoryClickEvent
import org.bukkit.inventory.ItemStack
import org.bukkit.plugin.java.JavaPlugin
import java.util.UUID

class MenuService(
    private val plugin: JavaPlugin,
    private val config: ConfigRegistry,
    private val tagService: TagService,
    private val scrollService: ScrollService,
    private val shopService: ShopService,
    private val messageService: MessageService,
    private val customTitleService: CustomTitleService,
    private val clickableMessageService: ClickableMessageService,
    private val playerNameService: PlayerNameService,
) : Listener {
    private val entryFactory = MenuEntryFactory(config, tagService, ::currencyName)
    private val customMenus = CustomTitleMenuService(
        config,
        shopService,
        messageService,
        customTitleService,
        clickableMessageService,
        ::currencyName,
        ::openShop,
    )
    private val shopMenus = ShopMenuService(
        config,
        shopService,
        ::currencyName,
        ::normalizedPage,
        ::createSession,
        ::fillStatic,
    )
    private val effectMenus = EffectMenuService(
        config,
        tagService,
        messageService,
        entryFactory,
        ::normalizedPage,
        ::createSession,
        ::fillStatic,
    )
    private val scrollSelectionMenus = ScrollSelectionMenuService(
        config,
        scrollService,
        messageService,
        ::normalizedPage,
        ::createSession,
        ::fillStatic,
    )

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

    fun openAdminWarehouse(admin: Player, target: OfflinePlayer, page: Int) {
        openAdminWarehouse(admin, target.uniqueId, playerNameService.label(target), page)
    }

    private fun openAdminWarehouse(admin: Player, targetId: UUID, targetName: String, page: Int) {
        val layout = config.warehouseLayout
        val titles = tagService.visibleTitles(targetId)
        val currentPage = normalizedPage(layout, page, titles.size)
        val session = createSession(
            MenuType.WAREHOUSE,
            layout,
            currentPage,
            null,
            null,
            title = "&#60A5FA$targetName &#C4B5FD称号管理",
            adminTargetId = targetId,
            adminTargetName = targetName,
        )
        fillStatic(admin, layout, session, currentPage, titles.size)
        val slots = layout.entrySlots()
        val start = currentPage * slots.size
        val currentTitleId = tagService.currentTagId(targetId)
        for (offset in slots.indices) {
            val index = start + offset
            if (index >= titles.size) continue
            val title = titles[index]
            val owned = title.owned
            val template = layout.templates[if (owned) "admin-tag-owned" else "admin-tag-locked"]
                ?: defaultAdminWarehouseTemplate(owned)
            val placeholders = adminTitlePlaceholders(targetId, targetName, title)
            val slot = slots[offset]
            session.inventory.setItem(
                slot,
                templateItem(template, placeholders, currentTitleId == title.id),
            )
            if (owned) {
                session.actions[slot] = action@{ click ->
                    if (click.isLeftClick) {
                        if (!requireAdminAction(admin, AdminAccess.EQUIP)) return@action
                        dispatchAdminResult(admin, tagService.adminEquipTitle(targetId, title.id))
                        openAdminWarehouse(admin, targetId, targetName, currentPage)
                    } else if (click.isRightClick) {
                        openAdminUpgrade(admin, targetId, targetName, title.id, 0)
                    }
                }
            } else if (title.kind == TitleKind.TAG) {
                session.actions[slot] = action@{ click ->
                    if (!click.isLeftClick) return@action
                    if (!requireAdminAction(admin, AdminAccess.GIVE)) return@action
                    val target = Bukkit.getOfflinePlayer(targetId)
                    if (tagService.giveTag(target, title.id)) {
                        messageService.send(admin, "tag-given", tagService.tagName(title.id))
                    } else {
                        messageService.send(admin, "tag-already-owned", tagService.tagName(title.id))
                    }
                    openAdminWarehouse(admin, targetId, targetName, currentPage)
                }
            }
        }
        admin.openInventory(session.inventory)
    }

    fun openShop(player: Player, page: Int) {
        shopMenus.open(player, page)
    }

    fun openUpgrade(player: Player, tagId: String, page: Int) {
        effectMenus.openUpgrade(player, tagId, page)
    }

    private fun openAdminUpgrade(admin: Player, targetId: UUID, targetName: String, tagId: String, page: Int) {
        effectMenus.openAdminUpgrade(admin, targetId, targetName, tagId, page)
    }

    fun openDetach(player: Player, tagId: String, page: Int) {
        effectMenus.openDetach(player, tagId, page)
    }

    private fun openAdminDetach(admin: Player, targetId: UUID, targetName: String, tagId: String, page: Int) {
        effectMenus.openAdminDetach(admin, targetId, targetName, tagId, page)
    }

    fun openScrollSelection(player: Player, context: ScrollSelectionContext, page: Int) {
        scrollSelectionMenus.open(player, context, page)
    }

    fun openCustomCurrencySelector(player: Player) {
        customMenus.openCurrencySelector(player)
    }

    fun openCustomTitleColorEditor(player: Player) {
        customMenus.openColorEditor(player)
    }

    fun openCustomTitleGroupSelector(player: Player) {
        customMenus.openGroupSelector(player)
    }

    @EventHandler
    fun onInventoryClick(event: InventoryClickEvent) {
        val player = event.whoClicked as? Player ?: return
        val holder = event.view.topInventory.holder as? ActionMenuHolder ?: return
        if (event.clickedInventory == null || event.clickedInventory != event.view.topInventory) {
            return
        }
        event.isCancelled = true
        holder.actions[event.slot]?.invoke(event)
    }

    private fun currencyName(type: CurrencyType): String = when (type) {
        CurrencyType.POINTS -> "点券"
        CurrencyType.MONEY -> "金币"
        CurrencyType.TITLE_COIN -> "称号币"
    }

    private fun adminTitlePlaceholders(targetId: UUID, targetName: String, title: TitleEntry): Map<String, String> {
        val current = tagService.currentTagId(targetId) == title.id
        val state = when {
            current -> "当前佩戴"
            title.owned -> "已拥有"
            else -> "未获得"
        }
        return linkedMapOf(
            "target_name" to targetName,
            "target_uuid" to targetId.toString(),
            "tag_display" to title.display,
            "tag_description" to title.description.joinToString("\n"),
            "tag_rarity" to title.rarityDisplay,
            "tag_state" to state,
            "tag_buff_count" to tagService.tagBuffCount(targetId, title.id).toString(),
            "tag_particle_count" to tagService.tagParticleCount(targetId, title.id).toString(),
            "tag_buffs" to tagService.tagBuffsDisplay(targetId, title.id),
            "tag_particles" to tagService.tagParticlesDisplay(targetId, title.id),
            "admin_left_action" to if (title.owned) "左键为目标佩戴这个称号" else "左键授予目标这个称号",
            "admin_right_action" to if (title.owned) "右键打开目标强化 / 拆卸管理" else "未拥有时不能打开强化页",
        )
    }

    private fun requireAdminAction(player: Player, permission: String, vararg inherited: String): Boolean {
        if (AdminAccess.has(player, permission, *inherited)) {
            return true
        }
        messageService.send(player, "no-permission")
        return false
    }

    private fun dispatchAdminResult(player: Player, result: AdminActionResult) {
        messageService.send(player, result.messageKey, *result.args.toTypedArray())
    }

    private fun defaultAdminWarehouseTemplate(owned: Boolean): GuiTemplate {
        return if (owned) {
            GuiTemplate(
                "NAME_TAG",
                "&#60A5FA管理 &#F8FAFC%tag_display%",
                listOf(
                    "&#64748B&m━━━━━━━━━━━━━━━━━━━━━━━━",
                    " &#E2E8F0目标玩家: &#FDE047%target_name%",
                    " &#E2E8F0当前状态: &#F8FAFC%tag_state%",
                    "%tag_description%",
                    "",
                    " &#93C5FD稀有度: &#F8FAFC%tag_rarity%",
                    " &#FDE047Buff 数量: &#F8FAFC%tag_buff_count%",
                    " &#A78BFA粒子数量: &#F8FAFC%tag_particle_count%",
                    " &#A7F3D0Buff 列表: &#F8FAFC%tag_buffs%",
                    " &#C4B5FD粒子列表: &#F8FAFC%tag_particles%",
                    "",
                    " &#A7F3D0%admin_left_action%",
                    " &#FDBA74%admin_right_action%",
                    "&#64748B&m━━━━━━━━━━━━━━━━━━━━━━━━",
                ),
                emptyList(),
            )
        } else {
            GuiTemplate(
                "BARRIER",
                "&#94A3B8未授予 &#F8FAFC%tag_display%",
                listOf(
                    "&#64748B&m━━━━━━━━━━━━━━━━━━━━━━━━",
                    " &#E2E8F0目标玩家: &#FDE047%target_name%",
                    "%tag_description%",
                    "",
                    " &#E2E8F0稀有度: &#F8FAFC%tag_rarity%",
                    " &#F87171当前状态: %tag_state%",
                    " &#A7F3D0%admin_left_action%",
                    " &#94A3B8%admin_right_action%",
                    "&#64748B&m━━━━━━━━━━━━━━━━━━━━━━━━",
                ),
                emptyList(),
            )
        }
    }

    private fun defaultAdminUnequipTemplate(): ItemTemplate {
        return ItemTemplate(
            "SHEARS",
            "&#FDE68A取消目标佩戴",
            listOf(
                "&#E2E8F0管理员模式下此按钮用于取消目标当前称号。",
                "&#A7F3D0点击后立即保存目标数据。",
            ),
        )
    }

    private fun createSession(
        type: MenuType,
        layout: GuiLayout,
        page: Int,
        tagId: String?,
        scrollContext: ScrollSelectionContext?,
        title: String = layout.title,
        adminTargetId: UUID? = null,
        adminTargetName: String? = null,
    ): MenuSession {
        val holder = MenuHolder()
        val inventory = Bukkit.createInventory(holder, layout.size(), Support.color(title))
        val session = MenuSession(type, inventory, page, tagId, scrollContext, adminTargetId, adminTargetName)
        holder.session = session
        return session
    }

    private fun normalizedPage(layout: GuiLayout, requestedPage: Int, entryCount: Int): Int {
        val pageSize = layout.entrySlots().size.coerceAtLeast(1)
        val maxPage = maxOf(0, kotlin.math.ceil(entryCount / pageSize.toDouble()).toInt() - 1)
        return requestedPage.coerceIn(0, maxPage)
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
                    function.equals("custom", true) -> customMenus.customButtonPlaceholders(player)
                    function.equals("detach", true) -> effectMenus.detachButtonPlaceholders(player, session)
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
                    function.equals("shop", true) -> {
                        val targetId = session.adminTargetId
                        if (targetId != null) {
                            template = defaultAdminUnequipTemplate()
                            session.actions[slot] = action@{
                                if (!requireAdminAction(player, AdminAccess.UNEQUIP)) return@action
                                dispatchAdminResult(player, tagService.adminUnequipTitle(targetId))
                                openAdminWarehouse(player, targetId, session.adminTargetName ?: targetId.toString(), 0)
                            }
                        } else {
                            session.actions[slot] = { openShop(player, 0) }
                        }
                    }
                    function.equals("back", true) -> session.actions[slot] = { goBack(player, session) }
                    function.equals("custom", true) -> session.actions[slot] = { startCustomTitleFlow(player) }
                    function.equals("detach", true) -> {
                        val tagId = session.tagId
                        if (tagId != null) {
                            val targetId = session.adminTargetId
                            session.actions[slot] = {
                                if (targetId != null) {
                                    openAdminDetach(player, targetId, session.adminTargetName ?: targetId.toString(), tagId, 0)
                                } else {
                                    openDetach(player, tagId, 0)
                                }
                            }
                        }
                    }
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
        customMenus.startFlow(player)
    }

    private fun templateItem(
        template: GuiTemplate?,
        placeholders: Map<String, String>,
        glow: Boolean,
    ): ItemStack {
        if (template == null) return ItemStack(Material.PAPER)
        return Support.createItem(template.material, template.name, template.lore, placeholders, glow)
    }

    private fun changePage(player: Player, session: MenuSession, page: Int) {
        val adminTargetId = session.adminTargetId
        if (adminTargetId != null) {
            val targetName = session.adminTargetName ?: adminTargetId.toString()
            when (session.type) {
                MenuType.WAREHOUSE -> openAdminWarehouse(player, adminTargetId, targetName, page)
                MenuType.UPGRADE -> openAdminUpgrade(player, adminTargetId, targetName, session.tagId ?: return, page)
                MenuType.DETACH -> openAdminDetach(player, adminTargetId, targetName, session.tagId ?: return, page)
                MenuType.SHOP,
                MenuType.SCROLL_SELECT,
                MenuType.CUSTOM_CURRENCY,
                MenuType.CUSTOM_TITLE_COLOR,
                MenuType.CUSTOM_TITLE_GROUP,
                -> {
                    // these menus are not used for admin paging
                }
            }
            return
        }
        when (session.type) {
            MenuType.WAREHOUSE -> openWarehouse(player, page)
            MenuType.SHOP -> openShop(player, page)
            MenuType.UPGRADE -> openUpgrade(player, session.tagId ?: return, page)
            MenuType.DETACH -> openDetach(player, session.tagId ?: return, page)
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
        val adminTargetId = session.adminTargetId
        if (adminTargetId != null) {
            val targetName = session.adminTargetName ?: adminTargetId.toString()
            when (session.type) {
                MenuType.UPGRADE -> openAdminWarehouse(player, adminTargetId, targetName, 0)
                MenuType.DETACH -> openAdminUpgrade(player, adminTargetId, targetName, session.tagId ?: return, 0)
                MenuType.WAREHOUSE -> player.closeInventory()
                MenuType.SHOP,
                MenuType.SCROLL_SELECT,
                MenuType.CUSTOM_CURRENCY,
                MenuType.CUSTOM_TITLE_COLOR,
                MenuType.CUSTOM_TITLE_GROUP,
                -> player.closeInventory()
            }
            return
        }
        when (session.type) {
            MenuType.UPGRADE,
            MenuType.SHOP,
            MenuType.CUSTOM_CURRENCY,
            MenuType.CUSTOM_TITLE_COLOR,
            MenuType.CUSTOM_TITLE_GROUP,
            -> openWarehouse(player, 0)
            MenuType.DETACH -> openUpgrade(player, session.tagId ?: return, 0)
            MenuType.SCROLL_SELECT -> player.closeInventory()
            MenuType.WAREHOUSE -> player.closeInventory()
        }
    }

}
