package cn.aing.uptags.service

import cn.aing.uptags.UpTagsPlugin
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlin.test.Test
import kotlin.test.assertEquals
import org.bukkit.OfflinePlayer
import org.bukkit.entity.Player
import java.util.UUID

class UpTagsPlaceholderExpansionTest {
    @Test
    fun offlinePlayerUsesRepositoryBackedPlaceholderValues() {
        val plugin = mockk<UpTagsPlugin>(relaxed = true)
        val tagService = mockk<TagService>()
        val offlinePlayer = mockk<OfflinePlayer>()
        val uniqueId = UUID.randomUUID()

        every { offlinePlayer.uniqueId } returns uniqueId
        every { offlinePlayer.player } returns null
        every { tagService.currentTagId(uniqueId) } returns "vip"
        every { tagService.currentTagDisplay(uniqueId) } returns "VIP"
        every { tagService.titleCoins(uniqueId) } returns 12.5
        every { tagService.tagBuffCount(uniqueId, "vip") } returns 2
        every { tagService.canUpgrade(uniqueId) } returns true

        val expansion = UpTagsPlaceholderExpansion(plugin, tagService)

        assertEquals("vip", expansion.onRequest(offlinePlayer, "current_id"))
        assertEquals("VIP", expansion.onRequest(offlinePlayer, "current"))
        assertEquals("12.5", expansion.onRequest(offlinePlayer, "title_coin"))
        assertEquals("2", expansion.onRequest(offlinePlayer, "tag_buff_count_vip"))
        assertEquals("是", expansion.onRequest(offlinePlayer, "can_upgrade"))
    }

    @Test
    fun onlinePlayerStillUsesLivePlayerResolvers() {
        val plugin = mockk<UpTagsPlugin>(relaxed = true)
        val tagService = mockk<TagService>()
        val offlinePlayer = mockk<OfflinePlayer>()
        val player = mockk<Player>()
        val uniqueId = UUID.randomUUID()

        every { offlinePlayer.uniqueId } returns uniqueId
        every { offlinePlayer.player } returns player
        every { tagService.currentTagId(player) } returns "newbie"
        every { tagService.points(player) } returns 88.0

        val expansion = UpTagsPlaceholderExpansion(plugin, tagService)

        assertEquals("newbie", expansion.onRequest(offlinePlayer, "current_id"))
        assertEquals("88", expansion.onRequest(offlinePlayer, "points"))
        verify(exactly = 1) { tagService.currentTagId(player) }
        verify(exactly = 1) { tagService.points(player) }
    }
}
