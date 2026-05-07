package cn.aing.uptags.repository.store

import cn.aing.uptags.repository.PlayerDataSnapshot
import cn.aing.uptags.repository.SaveResult
import org.bukkit.configuration.file.YamlConfiguration
import java.io.File
import java.io.IOException
import java.util.LinkedHashMap
import java.util.UUID

class YamlPlayerDataStore(
    private val rootDir: File,
) : PlayerDataStore {
    override fun initialize() {
        if (!rootDir.exists()) {
            rootDir.mkdirs()
        }
    }

    override fun load(uniqueId: UUID): PlayerDataSnapshot? {
        val file = file(uniqueId)
        if (!file.exists()) return null
        val yaml = YamlConfiguration.loadConfiguration(file)
        return PlayerDataSnapshot(
            data = PlayerDataCodec.deserialize(uniqueId, yaml.getString("data_json", "") ?: ""),
            version = yaml.getLong("version", 0L),
            updatedAt = yaml.getLong("updated_at", System.currentTimeMillis()),
        )
    }

    override fun save(snapshot: PlayerDataSnapshot, expectedVersion: Long?): SaveResult {
        val file = file(snapshot.data.uniqueId)
        val yaml = if (file.exists()) YamlConfiguration.loadConfiguration(file) else YamlConfiguration()
        val currentVersion = yaml.getLong("version", 0L)
        if (expectedVersion != null && expectedVersion != 0L && currentVersion != expectedVersion) {
            return SaveResult.Conflict(load(snapshot.data.uniqueId))
        }
        yaml.set("data_json", PlayerDataCodec.serialize(snapshot.data))
        yaml.set("version", snapshot.version)
        yaml.set("updated_at", snapshot.updatedAt)
        return try {
            yaml.save(file)
            SaveResult.Success(snapshot.version, snapshot.updatedAt)
        } catch (ex: IOException) {
            SaveResult.Failure("YML 保存失败: ${ex.message}", ex)
        }
    }

    override fun loadAll(): List<PlayerDataSnapshot> {
        if (!rootDir.exists()) {
            return emptyList()
        }
        return rootDir.listFiles { file -> file.isFile && file.extension.equals("yml", ignoreCase = true) }
            .orEmpty()
            .mapNotNull { file ->
                val uniqueId = runCatching { UUID.fromString(file.nameWithoutExtension) }.getOrNull() ?: return@mapNotNull null
                load(uniqueId)
            }
    }

    override fun loadVersions(uniqueIds: Collection<UUID>): Map<UUID, Long> {
        val versions = LinkedHashMap<UUID, Long>()
        uniqueIds.forEach { uniqueId ->
            val file = file(uniqueId)
            if (!file.exists()) return@forEach
            val yaml = YamlConfiguration.loadConfiguration(file)
            versions[uniqueId] = yaml.getLong("version", 0L)
        }
        return versions
    }

    private fun file(uniqueId: UUID): File = File(rootDir, "$uniqueId.yml")
}
