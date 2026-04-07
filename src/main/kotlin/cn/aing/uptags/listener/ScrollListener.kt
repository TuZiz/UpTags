package cn.aing.uptags.listener

import cn.aing.uptags.config.MessageService
import cn.aing.uptags.gui.MenuService
import cn.aing.uptags.service.ScrollService
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.event.block.Action
import org.bukkit.event.player.PlayerInteractEvent
import org.bukkit.inventory.EquipmentSlot

class ScrollListener(
    private val menuService: MenuService,
    private val scrollService: ScrollService,
    private val messageService: MessageService,
) : Listener {
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    fun onInteract(event: PlayerInteractEvent) {
        if (event.hand != EquipmentSlot.HAND) {
            return
        }
        if (event.action != Action.RIGHT_CLICK_AIR && event.action != Action.RIGHT_CLICK_BLOCK) {
            return
        }
        val context = scrollService.parse(event.item, event.hand ?: EquipmentSlot.HAND) ?: return
        event.isCancelled = true
        if (!scrollService.isValidScrollKey(context.scrollKey)) {
            messageService.send(event.player, "scroll-invalid-item")
            return
        }
        menuService.openScrollSelection(event.player, context, 0)
    }
}
