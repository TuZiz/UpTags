package cn.aing.uptags.gui.shop

import cn.aing.uptags.gui.common.MenuType

import cn.aing.uptags.gui.common.MenuSession

import cn.aing.uptags.Support
import cn.aing.uptags.config.ConfigRegistry
import cn.aing.uptags.model.config.CurrencyType
import cn.aing.uptags.model.config.GuiLayout
import cn.aing.uptags.model.config.GuiKey
import cn.aing.uptags.model.config.ShopProductDefinition
import cn.aing.uptags.model.config.ShopProductMode
import cn.aing.uptags.model.runtime.ScrollSelectionContext
import cn.aing.uptags.service.shop.ShopService
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
    fun open(player: Player, page: Int, categoryId: String = DEFAULT_CATEGORY_ID) {
        val layout = config.shopLayout
        val allProducts = shopService.visibleProducts(player)
        val category = ShopCategory.from(categoryId)
        val products = allProducts.filter(category::matches)
        val currentPage = normalizedPage(layout, page, products.size)
        val session = createSession(MenuType.SHOP, layout, currentPage, category.id, null, layout.title, null, null)
        fillStatic(player, layout, session, currentPage, products.size)
        renderCategoryButtons(player, layout, session, allProducts, category)
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
                "product_requirement" to shopService.requirementDisplay(product),
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
                    open(player, currentPage, category.id)
                }
            }
        }
        player.openInventory(session.inventory)
    }

    private fun renderCategoryButtons(
        player: Player,
        layout: GuiLayout,
        session: MenuSession,
        products: List<ShopProductDefinition>,
        selected: ShopCategory,
    ) {
        layout.plain.forEachIndexed { row, line ->
            line.forEachIndexed { column, token ->
                val slot = row * 9 + column
                val key = layout.keys[token] ?: return@forEachIndexed
                val category = categoryFromKey(key) ?: return@forEachIndexed
                val template = key.base ?: return@forEachIndexed
                val count = products.count(category::matches)
                val selectedText = if (category == selected) "&#A7F3D0当前分类" else "&#94A3B8点击切换"
                val placeholders = mapOf(
                    "category_name" to category.display,
                    "category_count" to count.toString(),
                    "category_state" to selectedText,
                    "category_hint" to category.hint,
                )
                session.inventory.setItem(
                    slot,
                    Support.createItem(template.material, template.name, template.lore, placeholders, category == selected),
                )
                session.actions[slot] = {
                    if (category != selected) {
                        open(player, 0, category.id)
                    }
                }
            }
        }
    }

    private fun categoryFromKey(key: GuiKey): ShopCategory? {
        val function = key.iconFunction ?: return null
        val raw = function.substringAfter("shop-category:", missingDelimiterValue = "")
        return raw.takeIf(String::isNotBlank)?.let(ShopCategory::from)
    }

    companion object {
        const val DEFAULT_CATEGORY_ID = "all"
    }
}

private enum class ShopCategory(
    val id: String,
    val display: String,
    val hint: String,
    private val predicate: (ShopProductDefinition) -> Boolean,
) {
    ALL("all", "全部", "显示所有可见商品", { true }),
    CHALLENGE(
        "challenge",
        "挑战",
        "统计、维度、群系、击杀、挖掘等挑战领取",
        { product -> product.mode == ShopProductMode.CHALLENGE_CLAIM },
    ),
    EXCHANGE(
        "exchange",
        "兑换",
        "提交物品兑换称号",
        { product -> product.mode == ShopProductMode.ITEM_EXCHANGE },
    ),
    BUY(
        "buy",
        "购买",
        "消耗点券、金币或称号币购买",
        { product -> product.mode == ShopProductMode.BUY && product.cost.priceForLevel(1) > 0.0 },
    ),
    LIMITED(
        "limited",
        "限定",
        "季节活动、声望与特殊条件商品",
        { product -> product.mode == ShopProductMode.SEASONAL || product.mode == ShopProductMode.PRESTIGE },
    );

    fun matches(product: ShopProductDefinition): Boolean = predicate(product)

    companion object {
        fun from(raw: String?): ShopCategory {
            val normalized = raw?.trim()?.lowercase().orEmpty()
            return entries.firstOrNull { it.id == normalized } ?: ALL
        }
    }
}
