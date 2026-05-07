package cn.aing.uptags.service

import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import io.mockk.verify
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame
import org.bukkit.Bukkit
import org.bukkit.OfflinePlayer
import org.bukkit.entity.Player
import org.bukkit.plugin.java.JavaPlugin
import java.io.File
import java.util.UUID

class PlayerNameServiceTest {
    @Test
    fun loadImportsUsercacheAndResolvesByCachedNameWithoutOfflineEnumeration() {
        val rootDir = createTempDirectory("uptags-player-names").toFile()
        val pluginDir = File(rootDir, "plugins/UpTags").apply { mkdirs() }
        val aliceId = UUID.fromString("11111111-1111-1111-1111-111111111111")
        File(rootDir, "usercache.json").writeText(
            """
            [
              {"name":"Alice","uuid":"$aliceId","expiresOn":"2099-01-01 00:00:00 +0000"}
            ]
            """.trimIndent(),
        )

        val plugin = mockk<JavaPlugin>()
        val offlinePlayer = mockk<OfflinePlayer>()
        every { plugin.dataFolder } returns pluginDir
        every { offlinePlayer.uniqueId } returns aliceId

        mockkStatic(Bukkit::class)
        try {
            every { Bukkit.getOnlinePlayers() } returns emptyList<Player>()
            every { Bukkit.getPlayerExact(any()) } returns null
            every { Bukkit.getPlayer(any<UUID>()) } returns null
            every { Bukkit.getOfflinePlayer(aliceId) } returns offlinePlayer

            val service = PlayerNameService(plugin)
            service.load()

            assertEquals(listOf("Alice"), service.knownNames())
            assertEquals("Alice", service.label(aliceId))
            assertSame(offlinePlayer, service.resolve("Alice"))
            verify(exactly = 0) { Bukkit.getOfflinePlayers() }
        } finally {
            unmockkStatic(Bukkit::class)
            rootDir.deleteRecursively()
        }
    }
}
