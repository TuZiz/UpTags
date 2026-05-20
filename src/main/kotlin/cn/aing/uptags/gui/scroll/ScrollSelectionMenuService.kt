package cn.aing.uptags.gui.scroll

import cn.aing.uptags.gui.common.MenuType

import cn.aing.uptags.gui.common.MenuSession

import cn.aing.uptags.Support
import cn.aing.uptags.config.ConfigRegistry
import cn.aing.uptags.config.MessageService
import cn.aing.uptags.model.config.GuiLayout
import cn.aing.uptags.model.runtime.ScrollKind
import cn.aing.uptags.model.runtime.ScrollSelectionContext
import cn.aing.uptags.service.scroll.ScrollService
import org.bukkit.entity.Player
import java.util.UUID

internal class ScrollSelectionMenuService(
    private val config: ConfigRegistry,
    private val scrollService: ScrollService,
    private val messageService: MessageService,
    private val normalizedPage: (GuiLayout, Int, Int) -> Int,
    private val createSession: (
        MenuType,
        GuiLayout,
        Int,
        String?,
        ScrollSelectionContext?,
        String,
        UUID?,
        String?,
    ) -> MenuSession,
    private val fillStatic: (Player, GuiLayout, MenuSession, Int, Int) -> Unit,
) {
    fun open(player: Player, context: ScrollSelectionContext, page: Int) {
        val layout = config.scrollSelectLayout
        val titles = scrollService.eligibleTitles(player, context)
        if (titles.isEmpty()) {
            messageService.send(player, "scroll-no-eligible-tags")
            return
        }
        val currentPage = normalizedPage(layout, page, titles.size)
        val session = createSession(MenuType.SCROLL_SELECT, layout, currentPage, null, context, layout.title, null, null)
        fillStatic(player, layout, session, currentPage, titles.size)
        val slots = layout.entrySlots()
        val start = currentPage * slots.size
        val template = layout.templates["tag"]
        for (offset in slots.indices) {
            val index = start + offset
            if (index >= titles.size) continue
            val title = titles[index]
            val tag = title
            val slot = slots[offset]
            val targetName = scrollService.displayName(context.kind, context.targetId)
            val placeholders = mapOf(
                "tag_display" to title.display,
                "tag_name" to Support.stripColor(tag.display),
                "scroll_type" to if (context.kind == ScrollKind.BUFF) "Buff 升级卷" else "粒子解锁卷",
                "scroll_target" to targetName,
                "scroll_level" to context.level.toString(),
            )
            session.inventory.setItem(
                slot,
                if (template != null) {
                    Support.createItem(template.material, template.name, template.lore, placeholders)
                } else {
                    Support.createItem(
                        "NAME_TAG",
                        "&f${title.display}",
                        listOf(
                            "&7称号名称: &f${Support.stripColor(tag.display)}",
                            "&7卷轴类型: &f${placeholders.getValue("scroll_type")}",
                            "&7目标内容: &f$targetName",
                            "&7卷轴等级: &f${context.level}",
                            "&e左键对这个称号使用卷轴",
                        ),
                    )
                },
            )
            session.actions[slot] = {
                if (scrollService.apply(player, context, tag.id)) {
                    player.closeInventory()
                }
            }
        }
        player.openInventory(session.inventory)
    }
}
