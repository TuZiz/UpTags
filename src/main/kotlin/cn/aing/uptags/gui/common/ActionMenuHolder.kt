package cn.aing.uptags.gui.common

import org.bukkit.event.inventory.InventoryClickEvent
import org.bukkit.inventory.InventoryHolder

internal interface ActionMenuHolder : InventoryHolder {
    val actions: Map<Int, (InventoryClickEvent) -> Unit>
}
