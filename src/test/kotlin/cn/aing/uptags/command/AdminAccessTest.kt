package cn.aing.uptags.command

import cn.aing.uptags.UpTagsPlugin
import cn.aing.uptags.config.MessageService
import cn.aing.uptags.gui.MenuService
import cn.aing.uptags.service.ClickableMessageService
import cn.aing.uptags.service.CustomTitleService
import cn.aing.uptags.service.PlayerNameService
import cn.aing.uptags.service.ScrollService
import cn.aing.uptags.service.ShopService
import cn.aing.uptags.service.TagService
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.bukkit.Bukkit
import org.bukkit.command.Command
import org.bukkit.command.CommandSender
import org.bukkit.entity.Player

class AdminAccessTest {
    @Test
    fun adminPermissionBypassesEveryGranularNode() {
        val sender = senderWith(AdminAccess.ADMIN)

        assertTrue(AdminAccess.hasUse(sender))
        assertTrue(AdminAccess.has(sender, AdminAccess.BUFF_DETACH, AdminAccess.BUFF_ALL))
        assertTrue(AdminAccess.has(sender, AdminAccess.CUSTOM_DELETE, AdminAccess.CUSTOM_ALL))
    }

    @Test
    fun coinSetPermissionDoesNotExposeOtherAdminSubcommands() {
        val command = command()
        val sender = senderWith(AdminAccess.COIN_SET)

        val adminCommands = command.onTabComplete(sender, mockk<Command>(), "tags", arrayOf("admin", ""))
        val coinActions = command.onTabComplete(sender, mockk<Command>(), "tags", arrayOf("admin", "coin", ""))

        assertEquals(listOf("coin"), adminCommands)
        assertEquals(listOf("set"), coinActions)
        assertFalse("buff" in adminCommands)
        assertFalse("custom" in adminCommands)
    }

    @Test
    fun coinAddPermissionCompletesGiveAction() {
        val command = command()
        val sender = senderWith(AdminAccess.COIN_ADD)

        val coinActions = command.onTabComplete(sender, mockk<Command>(), "tags", arrayOf("admin", "coin", ""))

        assertEquals(listOf("give"), coinActions)
    }

    @Test
    fun playerWithoutUsePermissionDoesNotSeeNormalCommands() {
        val command = command()
        val sender = senderWith()

        val completions = command.onTabComplete(sender, mockk<Command>(), "tags", arrayOf(""))

        assertTrue("help" in completions)
        assertFalse("equip" in completions)
        assertFalse("shop" in completions)
        assertFalse("admin" in completions)
    }

    @Test
    fun adminTargetCompletionUsesOnlyOnlinePlayers() {
        mockkStatic(Bukkit::class)
        val playerNames = mockk<PlayerNameService>()
        val online = mockk<Player>()
        try {
            every { playerNames.knownNames() } returns listOf("Alice", "Bob")
            every { online.name } returns "Alex"
            every { Bukkit.getOnlinePlayers() } returns listOf(online)
            val command = command(playerNames)
            val sender = senderWith(AdminAccess.INFO)

            val completions = command.onTabComplete(sender, mockk<Command>(), "tags", arrayOf("admin", "info", "A"))

            assertEquals(listOf("Alex"), completions)
        } finally {
            unmockkStatic(Bukkit::class)
        }
    }

    private fun command(playerNameService: PlayerNameService = mockk(relaxed = true)): TagsCommand {
        return TagsCommand(
            plugin = mockk<UpTagsPlugin>(relaxed = true),
            tagService = mockk<TagService>(relaxed = true),
            scrollService = mockk<ScrollService>(relaxed = true),
            shopService = mockk<ShopService>(relaxed = true),
            customTitleService = mockk<CustomTitleService>(relaxed = true),
            clickableMessageService = mockk<ClickableMessageService>(relaxed = true),
            menuService = mockk<MenuService>(relaxed = true),
            messageService = mockk<MessageService>(relaxed = true),
            playerNameService = playerNameService,
        )
    }

    private fun senderWith(vararg permissions: String): CommandSender {
        val allowed = permissions.toSet()
        return mockk<CommandSender>(relaxed = true) {
            every { hasPermission(any<String>()) } answers { firstArg<String>() in allowed }
        }
    }
}
