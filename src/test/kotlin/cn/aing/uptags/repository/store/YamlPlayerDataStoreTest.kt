package cn.aing.uptags.repository.store

import cn.aing.uptags.model.runtime.PlayerTagData
import cn.aing.uptags.repository.PlayerDataSnapshot
import cn.aing.uptags.repository.SaveResult
import java.nio.file.Files
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class YamlPlayerDataStoreTest {
    @Test
    fun loadAllScansUuidYamlFilesOnly() {
        val root = Files.createTempDirectory("uptags-yml-store").toFile()
        try {
            val store = YamlPlayerDataStore(root)
            store.initialize()
            val uniqueId = UUID.randomUUID()
            val data = PlayerTagData(uniqueId).apply {
                ownedTags += "vip"
            }
            store.save(PlayerDataSnapshot(data, version = 2L, updatedAt = 123L), expectedVersion = null)
            root.resolve("not-a-player.yml").writeText("data_json: ''")

            val loaded = store.loadAll()

            assertEquals(listOf(uniqueId), loaded.map { it.data.uniqueId })
            assertEquals(2L, loaded.single().version)
            assertEquals(setOf("vip"), loaded.single().data.ownedTags)
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun expectedVersionZeroConflictsWhenFileAlreadyExists() {
        val root = Files.createTempDirectory("uptags-yml-store-conflict").toFile()
        try {
            val store = YamlPlayerDataStore(root)
            store.initialize()
            val uniqueId = UUID.randomUUID()
            val first = PlayerDataSnapshot(PlayerTagData(uniqueId), version = 1L, updatedAt = 100L)
            assertIs<SaveResult.Success>(store.save(first, expectedVersion = 0L))

            val second = PlayerDataSnapshot(PlayerTagData(uniqueId), version = 1L, updatedAt = 200L)
            val result = store.save(second, expectedVersion = 0L)

            assertIs<SaveResult.Conflict>(result)
            assertEquals(100L, store.load(uniqueId)?.updatedAt)
        } finally {
            root.deleteRecursively()
        }
    }
}
