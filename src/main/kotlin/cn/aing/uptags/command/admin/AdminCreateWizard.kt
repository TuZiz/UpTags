package cn.aing.uptags.command.admin

import cn.aing.uptags.Support
import cn.aing.uptags.command.core.TagsCommandContext
import cn.aing.uptags.model.config.CostDefinition
import cn.aing.uptags.model.config.CurrencyType
import cn.aing.uptags.model.config.ItemTemplate
import cn.aing.uptags.model.config.ShopProductDefinition
import cn.aing.uptags.model.config.ShopProductMode
import cn.aing.uptags.model.config.ShopProductType
import cn.aing.uptags.model.config.SubmitItemDefinition
import cn.aing.uptags.model.config.TagDefinition
import org.bukkit.conversations.ConversationContext
import org.bukkit.conversations.ConversationFactory
import org.bukkit.conversations.Prompt
import org.bukkit.conversations.StringPrompt
import org.bukkit.entity.Player
import java.util.Locale

internal class AdminCreateWizard(private val context: TagsCommandContext) {
    fun start(player: Player) {
        ConversationFactory(context.plugin)
            .withModality(true)
            .withLocalEcho(false)
            .withTimeout(120)
            .withFirstPrompt(TagIdPrompt())
            .buildConversation(player)
            .begin()
    }

    private inner class TagIdPrompt : StringPrompt() {
        override fun getPromptText(context: ConversationContext): String =
            color("&#A7F3D0请输入称号 ID，输入 cancel 取消。")

        override fun acceptInput(context: ConversationContext, input: String?): Prompt {
            val value = input.cleanInput() ?: return this
            if (value.isCancel()) return Prompt.END_OF_CONVERSATION
            if (this@AdminCreateWizard.context.plugin.config.tags.containsKey(value)) {
                context.forWhom.sendRawMessage(color("&#F87171该称号 ID 已存在，请换一个。"))
                return this
            }
            context.setSessionData("tagId", value)
            return DisplayPrompt()
        }
    }

    private inner class DisplayPrompt : StringPrompt() {
        override fun getPromptText(context: ConversationContext): String =
            color("&#A7F3D0请输入显示名，例如 &#F8FAFC[&#FF8FD8星愿者&#F8FAFC]。")

        override fun acceptInput(context: ConversationContext, input: String?): Prompt {
            val value = input.cleanInput() ?: return this
            if (value.isCancel()) return Prompt.END_OF_CONVERSATION
            context.setSessionData("display", value)
            return DescriptionPrompt()
        }
    }

    private inner class DescriptionPrompt : StringPrompt() {
        override fun getPromptText(context: ConversationContext): String =
            color("&#A7F3D0请输入 Lore 描述，多行用 | 分隔。")

        override fun acceptInput(context: ConversationContext, input: String?): Prompt {
            val value = input.cleanInput() ?: return this
            if (value.isCancel()) return Prompt.END_OF_CONVERSATION
            context.setSessionData("description", value.split('|').map(String::trim).filter(String::isNotBlank))
            return RarityPrompt()
        }
    }

    private inner class RarityPrompt : StringPrompt() {
        override fun getPromptText(context: ConversationContext): String =
            color("&#A7F3D0请输入稀有度，留空使用 ${this@AdminCreateWizard.context.plugin.config.defaultTagRarity}。")

        override fun acceptInput(context: ConversationContext, input: String?): Prompt {
            val value = input?.trim().orEmpty()
            if (value.isCancel()) return Prompt.END_OF_CONVERSATION
            val rarity = value.takeIf(String::isNotBlank)?.uppercase(Locale.ROOT)
                ?: this@AdminCreateWizard.context.plugin.config.defaultTagRarity
            context.setSessionData("rarity", rarity)
            return ShopPrompt()
        }
    }

    private inner class ShopPrompt : StringPrompt() {
        override fun getPromptText(context: ConversationContext): String =
            color("&#A7F3D0是否上架商店？输入 yes/no，默认 no。")

        override fun acceptInput(context: ConversationContext, input: String?): Prompt {
            val value = input?.trim().orEmpty()
            if (value.isCancel()) return Prompt.END_OF_CONVERSATION
            val enabled = value.equals("yes", true) || value.equals("y", true) || value == "是"
            context.setSessionData("shop", enabled)
            return if (enabled) CategoryPrompt() else PreviewPrompt()
        }
    }

    private inner class CategoryPrompt : StringPrompt() {
        override fun getPromptText(context: ConversationContext): String =
            color("&#A7F3D0请输入商店分类，留空不设置，例如 buy/challenge/exchange/limited。")

        override fun acceptInput(context: ConversationContext, input: String?): Prompt {
            val value = input?.trim().orEmpty()
            if (value.isCancel()) return Prompt.END_OF_CONVERSATION
            context.setSessionData("category", value.takeIf(String::isNotBlank))
            return PricePrompt()
        }
    }

    private inner class PricePrompt : StringPrompt() {
        override fun getPromptText(context: ConversationContext): String =
            color("&#A7F3D0请输入价格简写，默认 POINTS:0，例如 MONEY:75000 / POINTS:3000 / TITLE_COIN:250。")

        override fun acceptInput(context: ConversationContext, input: String?): Prompt {
            val value = input?.trim().orEmpty()
            if (value.isCancel()) return Prompt.END_OF_CONVERSATION
            context.setSessionData("price", value.takeIf(String::isNotBlank) ?: "POINTS:0")
            return IconPrompt()
        }
    }

    private inner class IconPrompt : StringPrompt() {
        override fun getPromptText(context: ConversationContext): String =
            color("&#A7F3D0请输入图标材质，留空 NAME_TAG，例如 STONE_PICKAXE。")

        override fun acceptInput(context: ConversationContext, input: String?): Prompt {
            val value = input?.trim().orEmpty()
            if (value.isCancel()) return Prompt.END_OF_CONVERSATION
            context.setSessionData("icon", value.takeIf(String::isNotBlank) ?: "NAME_TAG")
            return SubmitItemsPrompt()
        }
    }

    private inner class SubmitItemsPrompt : StringPrompt() {
        override fun getPromptText(context: ConversationContext): String =
            color("&#A7F3D0请输入提交材料，留空 none，例如 REDSTONE:64,COAL:16。")

        override fun acceptInput(context: ConversationContext, input: String?): Prompt {
            val value = input?.trim().orEmpty()
            if (value.isCancel()) return Prompt.END_OF_CONVERSATION
            context.setSessionData("submitItems", value)
            return PreviewPrompt()
        }
    }

    private inner class PreviewPrompt : StringPrompt() {
        override fun getPromptText(context: ConversationContext): String {
            val tag = buildTag(context)
            val product = buildProduct(context, tag)
            context.forWhom.sendRawMessage(color("&#C4B5FD即将写入 tags.yml:"))
            context.forWhom.sendRawMessage(color("&#94A3B8tags.${tag.id}.display: &#F8FAFC${tag.display}"))
            context.forWhom.sendRawMessage(color("&#94A3B8tags.${tag.id}.rarity: &#FDE047${tag.rarity}"))
            if (product != null) {
                context.forWhom.sendRawMessage(color("&#C4B5FD即将写入 shop.yml:"))
                context.forWhom.sendRawMessage(color("&#94A3B8products.${product.id}.price: &#FDE047${product.cost.type.name}:${Support.formatDouble(product.cost.amount)}"))
                context.forWhom.sendRawMessage(color("&#94A3B8products.${product.id}.icon: &#F8FAFC${product.icon.material}"))
            }
            return color("&#A7F3D0输入 confirm 确认保存，输入 cancel 取消。")
        }

        override fun acceptInput(context: ConversationContext, input: String?): Prompt {
            val value = input?.trim().orEmpty()
            if (value.isCancel()) return Prompt.END_OF_CONVERSATION
            if (!value.equals("confirm", true) && value != "确认") {
                context.forWhom.sendRawMessage(color("&#F87171未确认，已取消。"))
                return Prompt.END_OF_CONVERSATION
            }
            val tag = buildTag(context)
            val product = buildProduct(context, tag)
            val saved = this@AdminCreateWizard.context.plugin.config.applyTagAndProductAtomic(tag, product)
            if (saved) {
                context.forWhom.sendRawMessage(color("&#A7F3D0称号创建完成，配置已保存。"))
            } else {
                context.forWhom.sendRawMessage(color("&#F87171保存失败，已回滚 tags.yml 和 shop.yml。"))
            }
            return Prompt.END_OF_CONVERSATION
        }
    }

    private fun buildTag(context: ConversationContext): TagDefinition {
        val tagId = context.value("tagId")
        val rarity = context.value("rarity").uppercase(Locale.ROOT)
        return TagDefinition(
            id = tagId,
            display = context.value("display"),
            description = context.getSessionData("description") as? List<String> ?: emptyList(),
            rarity = rarity,
            defaultUnlocked = this.context.plugin.config.defaultTagUnlocked,
            upgradeGroups = this.context.plugin.config.defaultGroupsForRarity(rarity).toMutableList(),
            permission = "uptags.tag.$tagId",
        )
    }

    private fun buildProduct(context: ConversationContext, tag: TagDefinition): ShopProductDefinition? {
        val shop = context.getSessionData("shop") as? Boolean ?: false
        if (!shop) {
            return null
        }
        val cost = parsePrice(context.value("price"))
        val iconMaterial = context.value("icon")
        return ShopProductDefinition(
            id = tag.id,
            type = ShopProductType.TAG,
            targetId = tag.id,
            mode = if (parseSubmitItems(context.value("submitItems")).isEmpty()) ShopProductMode.BUY else ShopProductMode.ITEM_EXCHANGE,
            category = context.getSessionData("category") as? String,
            enabled = true,
            permission = null,
            conditions = emptyList(),
            cost = cost,
            submitItems = parseSubmitItems(context.value("submitItems")),
            icon = ItemTemplate(iconMaterial, tag.display, tag.description),
        )
    }

    private fun parsePrice(raw: String): CostDefinition {
        val parts = raw.split(':', limit = 2)
        if (parts.size != 2) {
            return CostDefinition()
        }
        val currency = parts[0].trim().replace('-', '_')
        return CostDefinition(
            type = CurrencyType.entries.firstOrNull { it.name.equals(currency, ignoreCase = true) } ?: CurrencyType.POINTS,
            amount = parts[1].trim().toDoubleOrNull()?.coerceAtLeast(0.0) ?: 0.0,
        )
    }

    private fun parseSubmitItems(raw: String): List<SubmitItemDefinition> {
        if (raw.isBlank() || raw.equals("none", true) || raw == "无") {
            return emptyList()
        }
        return raw.split(',').mapNotNull { entry ->
            val parts = entry.split(':', limit = 2)
            val material = parts.getOrNull(0)?.trim()?.takeIf(String::isNotBlank) ?: return@mapNotNull null
            val amount = parts.getOrNull(1)?.trim()?.toIntOrNull()?.coerceAtLeast(1) ?: 1
            SubmitItemDefinition(material, amount)
        }
    }

    private fun ConversationContext.value(key: String): String =
        getSessionData(key)?.toString().orEmpty()

    private fun String?.cleanInput(): String? = this?.trim()?.takeIf(String::isNotBlank)

    private fun String.isCancel(): Boolean = equals("cancel", true) || this == "取消"

    private fun color(text: String): String = Support.color(text)
}
