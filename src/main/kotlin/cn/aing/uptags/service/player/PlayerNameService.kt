package cn.aing.uptags.service.player

import org.bukkit.Bukkit
import org.bukkit.OfflinePlayer
import org.bukkit.entity.Player
import org.bukkit.plugin.java.JavaPlugin
import org.bukkit.configuration.file.YamlConfiguration
import java.io.File
import java.util.Locale
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

class PlayerNameService(
    private val plugin: JavaPlugin,
) {
    private val cacheFile = File(plugin.dataFolder, "player-names.yml")
    private val namesByUniqueId = ConcurrentHashMap<UUID, String>()
    private val uniqueIdsByLowerName = ConcurrentHashMap<String, UUID>()

    fun load() {
        loadPersistedCache()
        val imported = importServerUserCache()
        var changed = imported
        Bukkit.getOnlinePlayers().forEach { player ->
            changed = rememberInternal(player.uniqueId, player.name) || changed
        }
        if (changed || !cacheFile.exists()) {
            save()
        }
    }

    fun remember(player: Player) {
        remember(player.uniqueId, player.name)
    }

    fun remember(uniqueId: UUID, name: String?) {
        if (rememberInternal(uniqueId, name)) {
            save()
        }
    }

    fun knownNames(): List<String> {
        val values = LinkedHashMap<String, String>()
        namesByUniqueId.values.forEach { name ->
            val normalized = name.trim()
            if (normalized.isNotEmpty()) {
                values[normalized.lowercase(Locale.ROOT)] = normalized
            }
        }
        Bukkit.getOnlinePlayers().forEach { player ->
            values[player.name.lowercase(Locale.ROOT)] = player.name
        }
        return values.values.sortedWith(String.CASE_INSENSITIVE_ORDER)
    }

    fun label(uniqueId: UUID): String {
        Bukkit.getPlayer(uniqueId)?.let { return it.name }
        return namesByUniqueId[uniqueId] ?: uniqueId.toString()
    }

    fun label(player: OfflinePlayer): String = label(player.uniqueId)

    fun resolve(input: String): OfflinePlayer? {
        Bukkit.getPlayerExact(input)?.let { player ->
            remember(player)
            return player
        }

        runCatching { UUID.fromString(input) }.getOrNull()?.let { uniqueId ->
            Bukkit.getPlayer(uniqueId)?.let { player ->
                remember(player)
                return player
            }
            return Bukkit.getOfflinePlayer(uniqueId)
        }

        val uniqueId = uniqueIdsByLowerName[input.lowercase(Locale.ROOT)] ?: return null
        Bukkit.getPlayer(uniqueId)?.let { player ->
            remember(player)
            return player
        }
        return Bukkit.getOfflinePlayer(uniqueId)
    }

    private fun loadPersistedCache() {
        if (!cacheFile.isFile) {
            return
        }
        val yaml = YamlConfiguration.loadConfiguration(cacheFile)
        val section = yaml.getConfigurationSection("players") ?: return
        section.getKeys(false).forEach { rawUniqueId ->
            val uniqueId = runCatching { UUID.fromString(rawUniqueId) }.getOrNull() ?: return@forEach
            rememberInternal(uniqueId, section.getString(rawUniqueId))
        }
    }

    private fun importServerUserCache(): Boolean {
        val serverRoot = plugin.dataFolder.parentFile?.parentFile ?: return false
        val userCacheFile = File(serverRoot, "usercache.json")
        if (!userCacheFile.isFile) {
            return false
        }
        val raw = runCatching { userCacheFile.readText(Charsets.UTF_8) }.getOrNull() ?: return false
        var changed = false
        Regex("\\{[^{}]*}").findAll(raw).forEach { match ->
            val entry = match.value
            val name = Regex("\"name\"\\s*:\\s*\"([^\"]+)\"").find(entry)?.groupValues?.getOrNull(1)
            val uniqueId = Regex("\"uuid\"\\s*:\\s*\"([^\"]+)\"").find(entry)?.groupValues?.getOrNull(1)
                ?.let { value -> runCatching { UUID.fromString(value) }.getOrNull() }
                ?: return@forEach
            changed = rememberInternal(uniqueId, name) || changed
        }
        return changed
    }

    private fun rememberInternal(uniqueId: UUID, name: String?): Boolean {
        val normalized = name?.trim()?.takeIf { it.isNotEmpty() } ?: return false
        val lowered = normalized.lowercase(Locale.ROOT)
        val previousName = namesByUniqueId.put(uniqueId, normalized)
        val previousLowered = previousName?.lowercase(Locale.ROOT)
        if (previousLowered != null && previousLowered != lowered) {
            uniqueIdsByLowerName.remove(previousLowered, uniqueId)
        }
        val previousUniqueId = uniqueIdsByLowerName.put(lowered, uniqueId)
        return previousName != normalized || previousUniqueId != uniqueId
    }

    @Synchronized
    private fun save() {
        if (!plugin.dataFolder.exists()) {
            plugin.dataFolder.mkdirs()
        }
        val yaml = YamlConfiguration()
        val section = yaml.createSection("players")
        namesByUniqueId.entries
            .sortedBy { it.value.lowercase(Locale.ROOT) }
            .forEach { (uniqueId, name) ->
                section.set(uniqueId.toString(), name)
            }
        yaml.save(cacheFile)
    }
}
