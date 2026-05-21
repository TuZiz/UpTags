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
        val file = File(plugin.dataFolder, "messages.yml")
        val yaml = YamlConfiguration.loadConfiguration(file)
        yaml.getKeys(false).forEach { key ->
            messages[key] = yaml.getString(key, messages[key] ?: key) ?: key
        }
    }

    fun get(key: String, vararg args: Any?): String {
        val prefix = messages["prefix"] ?: ""
        val pattern = overridePattern(key)
            ?: messages[key]
            ?: extraPattern(key)
            ?: detachFallbackPattern(key)
            ?: fallbackPattern(key)
            ?: key
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
        val stream = plugin.getResource("messages.yml") ?: return emptyMap()
        InputStreamReader(stream, Charsets.UTF_8).use { reader ->
            val yaml = YamlConfiguration.loadConfiguration(reader)
            return linkedMapOf<String, String>().apply {
                yaml.getKeys(false).forEach { key ->
                    this[key] = yaml.getString(key, key) ?: key
                }
            }
        }
    }

    private fun extraPattern(key: String): String? {
        return when (key) {
            "detach-no-items" -> "&c这个称号当前没有可拆卸的 Buff 或粒子。"
            else -> null
        }
    }

    private fun detachFallbackPattern(key: String): String? {
        return when (key) {
            "detach-disabled" -> "&c当前未开启称号效果拆卸。"
            "detach-invalid-currency" -> "&c拆卸只支持金币或点券。"
            "detach-inventory-full" -> "&c背包空间不足，请先清理至少一个空位。"
            "detach-scroll-missing" -> "&c未找到对应升级卷配置，无法拆下。"
            "detach-no-effect" -> "&c这个称号上没有可拆下的效果。"
            "buff-detached" -> "&a已拆下 Buff {0} {1}，消耗 {2} {3}，并返还对应等级升级卷。"
            "particle-detached" -> "&a已拆下粒子 {0}，消耗 {1} {2}，并返还粒子解锁卷。"
            else -> null
        }
    }

    private fun overridePattern(key: String): String? {
        return when (key) {
            "admin-help" -> listOf(
                "&#E2E8F0管理员命令:",
                "&#FDE047/tags admin manage <玩家> &#94A3B8- 打开玩家称号仓库管理 GUI",
                "&#FDE047/tags admin info <玩家> &#94A3B8- 查看玩家称号、称号币、Buff 与粒子摘要",
                "&#FDE047/tags admin give/take <玩家> <称号ID> &#94A3B8- 授予或移除配置称号",
                "&#FDE047/tags admin coin <give|take|set> <玩家> <数量> &#94A3B8- 管理内置称号币",
                "&#FDE047/tags admin buff <set|enable|disable|detach> ... &#94A3B8- 命令式管理目标称号 Buff",
                "&#FDE047/tags admin particle <give|take|select|clear|detach> ... &#94A3B8- 命令式管理目标称号粒子",
                "&#FDE047/tags admin custom <list|equip|delete|orders|refund|complete> ... &#94A3B8- 管理目标自定义称号与异常订单",
                "&#FDE047/tags admin scroll give <在线玩家> <卷轴ID> [数量] &#94A3B8- 发放卷轴",
                "&#FDE047/tags admin tag <create|delete|setdisplay|setrarity|setgroups|setdefault> ... &#94A3B8- 管理配置称号",
            ).joinToString("\n")
            "custom-title-preview-help" -> "&e用法: /tags custom preview <money|title_coin|points|single|double|triple|quad|manual|auto|prev|next|confirm|cancel>"
            "custom-title-preview-ready" -> "&a已生成配色预览，可先切换单色 / 双色 / 三色 / 四色库，再点击自定义组合或自动组合。"
            else -> null
        }
    }

    private fun fallbackPattern(key: String): String? {
        return when (key) {
            "custom-title-manual-limit" -> "&c当前只允许选择 {0} 个颜色，请先替换或移除已有颜色。"
            "custom-title-manual-count-mismatch" -> "&c当前需要 {0} 个颜色，已选择 {1} 个。"
            else -> null
        }
    }

    private data class PlayerMessageKey(val uniqueId: UUID, val key: String)

    private companion object {
        const val DEFAULT_PLAYER_MESSAGE_COOLDOWN_MILLIS = 1_200L
    }
}
