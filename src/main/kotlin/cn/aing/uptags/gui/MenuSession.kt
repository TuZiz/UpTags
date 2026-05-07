package cn.aing.uptags.gui

import cn.aing.uptags.model.runtime.ScrollSelectionContext
import org.bukkit.event.inventory.InventoryClickEvent
import org.bukkit.inventory.Inventory
import java.util.LinkedHashMap
import java.util.UUID

internal enum class MenuType {
    WAREHOUSE,
    SHOP,
    UPGRADE,
    DETACH,
    SCROLL_SELECT,
    CUSTOM_CURRENCY,
    CUSTOM_TITLE_COLOR,
    CUSTOM_TITLE_GROUP,
}

internal class MenuHolder : ActionMenuHolder {
    lateinit var session: MenuSession

    override val actions: Map<Int, (InventoryClickEvent) -> Unit>
        get() = session.actions

    override fun getInventory(): Inventory = session.inventory
}

internal class MenuSession(
    val type: MenuType,
    val inventory: Inventory,
    val page: Int,
    val tagId: String?,
    val scrollContext: ScrollSelectionContext?,
    val adminTargetId: UUID? = null,
    val adminTargetName: String? = null,
) {
    val actions = LinkedHashMap<Int, (InventoryClickEvent) -> Unit>()
}
