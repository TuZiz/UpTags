package cn.aing.uptags.service

import cn.aing.uptags.model.runtime.PlayerTagData
import cn.aing.uptags.repository.PlayerDataRepository
import cn.aing.uptags.service.shop.ChallengeProgressService
import io.mockk.Runs
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.verify
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import org.bukkit.Statistic
import org.bukkit.entity.Player

class ChallengeProgressServiceTest {
    @Test
    fun challengeProgressMarksDirtyButThrottlesDatabaseSaves() {
        val uniqueId = UUID.randomUUID()
        val player = mockk<Player>()
        val repository = mockk<PlayerDataRepository>()
        val data = PlayerTagData(uniqueId)

        every { player.uniqueId } returns uniqueId
        every { repository.getCached(uniqueId) } returns data
        every { repository.isLoaded(uniqueId) } returns true
        every { repository.markDirty(data) } just Runs
        every { repository.saveAsync(data) } just Runs

        val service = ChallengeProgressService(
            repository = repository,
            progressSaveIntervalMillis = 60_000L,
        )

        service.recordStatistic(player, Statistic.WALK_ONE_CM, 128)
        service.recordStatistic(player, Statistic.WALK_ONE_CM, 256)

        assertEquals(256L, data.challengeProgress.values["challenge:stat:walk_one_cm"])
        verify(exactly = 2) { repository.markDirty(data) }
        verify(exactly = 1) { repository.saveAsync(data) }
    }
}
