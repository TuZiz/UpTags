package cn.aing.uptags.gui

import cn.aing.uptags.Support
import cn.aing.uptags.config.ConfigRegistry
import cn.aing.uptags.model.config.CurrencyType
import cn.aing.uptags.model.config.GuiLayout
import cn.aing.uptags.model.runtime.ScrollSelectionContext
import cn.aing.uptags.service.ShopService
import org.bukkit.Material
import org.bukkit.entity.Player
import org.bukkit.inventory.ItemStack
import java.util.UUID

internal class ShopMenuService(
    private val config: ConfigRegistry,
    private val shopService: ShopService,
    private val currencyName: (CurrencyType) -> String,
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
    fun open(player: Player, page: Int) {
        val layout = config.shopLayout
        val products = shopService.visibleProducts(player)
        val currentPage = normalizedPage(layout, page, products.size)
        val session = createSession(MenuType.SHOP, layout, currentPage, null, null, layout.title, null, null)
        fillStatic(player, layout, session, currentPage, products.size)
        val slots = layout.entrySlots()
        val start = currentPage * slots.size
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
                if (template == null) {
                    ItemStack(Material.PAPER)
                } else {
                    Support.createItem(product.icon.material, template.name, template.lore, placeholders, false)
                },
            )
            session.actions[slot] = {
                if (product.type.name == "CUSTOM") {
                    if (shopService.startCustomFlow(player, product.id)) {
                        player.closeInventory()
                    }
                } else if (shopService.buy(player, product.id)) {
                    open(player, currentPage)
                }
            }
        }
        player.openInventory(session.inventory)
    }
}
