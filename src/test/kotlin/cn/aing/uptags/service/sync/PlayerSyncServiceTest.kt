package cn.aing.uptags.service.sync

import cn.aing.uptags.compat.PlatformScheduler
import cn.aing.uptags.repository.PlayerDataRepository
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import java.util.UUID
import kotlin.test.Test

class PlayerSyncServiceTest {
    @Test
    fun oldRemoteInvalidationIsIgnored() {
        val repository = mockk<PlayerDataRepository>()
        val scheduler = mockk<PlatformScheduler>(relaxed = true)
        val uniqueId = UUID.randomUUID()

        every { repository.shouldAcceptRemoteVersion(uniqueId, 2L) } returns false

        PlayerSyncService(repository, scheduler).handleRemoteInvalidation(
            PlayerSyncMessage(uniqueId, version = 2L, serverId = "remote", updatedAt = 100L),
        )

        verify(exactly = 0) { repository.markStale(any()) }
        verify(exactly = 0) { scheduler.runAsync(any()) }
    }
}
