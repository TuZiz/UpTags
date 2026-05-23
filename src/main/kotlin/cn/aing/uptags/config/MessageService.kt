package cn.aing.uptags.config

import cn.aing.uptags.Support
import org.bukkit.command.CommandSender
import org.bukkit.configuration.file.YamlConfiguration
import org.bukkit.entity.Player
import org.bukkit.plugin.java.JavaPlugin
import java.io.File
import java.io.InputStreamReader
import java.text.MessageFormat
import java.util.LinkedHashMap
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

class MessageService(private val plugin: JavaPlugin) {
    private val messages = LinkedHashMap<String, String>()
    private val playerMessageCooldowns = ConcurrentHashMap<PlayerMessageKey, Long>()

    fun load() {
        messages.clear()
        messages.putAll(loadBundledDefaults())
        loadExternalMessages(File(plugin.dataFolder, LEGACY_MESSAGES_PATH))
        loadExternalMessages(File(plugin.dataFolder, DEFAULT_LANGUAGE_PATH))
    }

    fun get(key: String, vararg args: Any?): String {
        val prefix = messages["prefix"] ?: ""
        val pattern = messages[key] ?: key
        val formatted = if (args.isEmpty()) pattern else MessageFormat.format(pattern, *args)
        return Support.color(prefix + formatted)
    }

    fun send(sender: CommandSender, key: String, vararg args: Any?) {
        sender.sendMessage(get(key, *args))
    }

    fun sendThrottled(player: Player, key: String, vararg args: Any?) {
        sendThrottled(player, key, DEFAULT_PLAYER_MESSAGE_COOLDOWN_MILLIS, *args)
    }

    fun sendThrottled(player: Player, key: String, cooldownMillis: Long, vararg args: Any?) {
        if (!canSend(player.uniqueId, key, cooldownMillis)) {
            return
        }
        send(player, key, *args)
    }

    private fun canSend(uniqueId: UUID, key: String, cooldownMillis: Long): Boolean {
        if (cooldownMillis <= 0L) {
            return true
        }
        val now = System.currentTimeMillis()
        val cooldownKey = PlayerMessageKey(uniqueId, key)
        val last = playerMessageCooldowns[cooldownKey]
        if (last != null && now - last < cooldownMillis) {
            return false
        }
        playerMessageCooldowns[cooldownKey] = now
        if (playerMessageCooldowns.size > 4096) {
            playerMessageCooldowns.entries.removeIf { now - it.value > cooldownMillis * 8L }
        }
        return true
    }

    private fun loadBundledDefaults(): Map<String, String> {
        val stream = plugin.getResource(DEFAULT_LANGUAGE_PATH) ?: return emptyMap()
        InputStreamReader(stream, Charsets.UTF_8).use { reader ->
            val yaml = YamlConfiguration.loadConfiguration(reader)
            return readFlatMessages(yaml)
        }
    }

    private fun loadExternalMessages(file: File) {
        if (!file.exists()) {
            return
        }
        messages.putAll(readFlatMessages(YamlConfiguration.loadConfiguration(file)))
    }

    private fun readFlatMessages(yaml: YamlConfiguration): LinkedHashMap<String, String> {
        return linkedMapOf<String, String>().apply {
            yaml.getKeys(false).forEach { key ->
                if (yaml.isString(key)) {
                    this[key] = yaml.getString(key, key) ?: key
                }
            }
        }
    }

    private data class PlayerMessageKey(val uniqueId: UUID, val key: String)

    private companion object {
        const val DEFAULT_LANGUAGE_PATH = "lang/zh_cn.yml"
        const val LEGACY_MESSAGES_PATH = "messages.yml"
        const val DEFAULT_PLAYER_MESSAGE_COOLDOWN_MILLIS = 1_200L
    }
}
