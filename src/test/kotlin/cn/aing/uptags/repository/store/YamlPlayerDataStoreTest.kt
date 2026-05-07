package cn.aing.uptags.repository.store

import cn.aing.uptags.model.runtime.PlayerTagData
import cn.aing.uptags.repository.PlayerDataSnapshot
import java.nio.file.Files
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals

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
}
