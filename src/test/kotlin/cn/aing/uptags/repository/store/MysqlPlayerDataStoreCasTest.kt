package cn.aing.uptags.repository.store

import cn.aing.uptags.model.runtime.PlayerTagData
import cn.aing.uptags.repository.PlayerDataSnapshot
import cn.aing.uptags.repository.SaveResult
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class MysqlPlayerDataStoreCasTest {
    @Test
    fun expectedVersionZeroConflictDoesNotOverwriteExistingRow() {
        val table = "uptags_test_${System.nanoTime()}"
        val store = MysqlPlayerDataStore(
            jdbcUrl = "jdbc:h2:mem:$table;MODE=MySQL;DATABASE_TO_UPPER=false",
            username = "sa",
            password = "",
            table = table,
        )
        store.initialize()
        try {
            val uniqueId = UUID.randomUUID()
            val firstData = PlayerTagData(uniqueId).apply { ownedTags += "first" }
            val secondData = PlayerTagData(uniqueId).apply { ownedTags += "second" }

            assertIs<SaveResult.Success>(
                store.save(PlayerDataSnapshot(firstData, version = 1L, updatedAt = 100L), expectedVersion = 0L),
            )
            val conflict = store.save(PlayerDataSnapshot(secondData, version = 1L, updatedAt = 200L), expectedVersion = 0L)

            assertIs<SaveResult.Conflict>(conflict)
            assertEquals(setOf("first"), store.load(uniqueId)?.data?.ownedTags?.toSet())
            assertEquals(100L, store.load(uniqueId)?.updatedAt)
        } finally {
            store.shutdown()
        }
    }
}
