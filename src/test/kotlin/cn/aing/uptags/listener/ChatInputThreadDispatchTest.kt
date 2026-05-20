package cn.aing.uptags.listener

import cn.aing.uptags.compat.PlatformScheduler
import cn.aing.uptags.compat.TaskHandle
import cn.aing.uptags.config.MessageService
import cn.aing.uptags.service.message.ClickableMessageService
import cn.aing.uptags.service.title.CustomTitleService
import cn.aing.uptags.service.title.ValidationResult
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.bukkit.entity.Player
import org.bukkit.event.player.AsyncPlayerChatEvent

class ChatInputThreadDispatchTest {
    @Test
    fun asyncChatOnlyCancelsAndDispatchesToPlayerScheduler() {
        val playerId = UUID.randomUUID()
        val player = mockk<Player>()
        val scheduler = mockk<PlatformScheduler>()
        val customTitleService = mockk<CustomTitleService>()
        val clickableMessageService = mockk<ClickableMessageService>(relaxed = true)
        val messageService = mockk<MessageService>(relaxed = true)
        val task = slot<() -> Unit>()

        every { player.uniqueId } returns playerId
        every { customTitleService.hasActiveDraft(playerId) } returns true
        every { customTitleService.handleInput(player, "桜咲く") } returns ValidationResult(false, null)
        every { scheduler.runPlayer(player, capture(task)) } returns mockk<TaskHandle>(relaxed = true)

        @Suppress("DEPRECATION")
        val event = AsyncPlayerChatEvent(true, player, "桜咲く", linkedSetOf())
        ChatInputListener(scheduler, customTitleService, clickableMessageService, messageService).onChat(event)

        assertTrue(event.isCancelled)
        verify(exactly = 1) { scheduler.runPlayer(player, any()) }
        verify(exactly = 0) { customTitleService.handleInput(player, "桜咲く") }

        task.captured.invoke()

        verify(exactly = 1) { customTitleService.handleInput(player, "桜咲く") }
    }

    @Test
    fun inactiveDraftDoesNotCancelChat() {
        val playerId = UUID.randomUUID()
        val player = mockk<Player>()
        val scheduler = mockk<PlatformScheduler>(relaxed = true)
        val customTitleService = mockk<CustomTitleService>()
        val clickableMessageService = mockk<ClickableMessageService>(relaxed = true)
        val messageService = mockk<MessageService>(relaxed = true)

        every { player.uniqueId } returns playerId
        every { customTitleService.hasActiveDraft(playerId) } returns false

        @Suppress("DEPRECATION")
        val event = AsyncPlayerChatEvent(true, player, "normal chat", linkedSetOf())
        ChatInputListener(scheduler, customTitleService, clickableMessageService, messageService).onChat(event)

        assertFalse(event.isCancelled)
        verify(exactly = 0) { scheduler.runPlayer(any(), any()) }
    }
}
