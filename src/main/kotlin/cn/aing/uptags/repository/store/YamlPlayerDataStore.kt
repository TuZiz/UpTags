package cn.aing.uptags.repository.store

import cn.aing.uptags.repository.PlayerDataSnapshot
import cn.aing.uptags.repository.SaveResult
import org.bukkit.configuration.file.YamlConfiguration
import java.io.File
import java.io.IOException
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.LinkedHashMap
import java.util.UUID
import java.util.logging.Logger

class YamlPlayerDataStore(
    private val rootDir: File,
) : PlayerDataStore {
    private val logger = Logger.getLogger(YamlPlayerDataStore::class.java.name)

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
        yaml.set("schema_version", 2)
        yaml.set("data_json", PlayerDataCodec.serialize(snapshot.data))
        yaml.set("version", snapshot.version)
        yaml.set("updated_at", snapshot.updatedAt)
        return try {
            atomicSave(file, yaml)
            SaveResult.Success(snapshot.version, snapshot.updatedAt)
        } catch (ex: IOException) {
            SaveResult.Failure("YML save failed: ${ex.message}", ex)
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

    private fun atomicSave(file: File, yaml: YamlConfiguration) {
        Files.createDirectories(file.parentFile.toPath())
        val target = file.toPath()
        val temp = Files.createTempFile(file.parentFile.toPath(), "${file.name}.", ".tmp")
        try {
            Files.writeString(temp, yaml.saveToString(), Charsets.UTF_8)
            try {
                Files.move(temp, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            } catch (ex: AtomicMoveNotSupportedException) {
                logger.warning("Atomic move is not supported for ${file.absolutePath}; falling back to replace move.")
                Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING)
            }
        } finally {
            Files.deleteIfExists(temp)
        }
    }
}
