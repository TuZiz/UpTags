package cn.aing.uptags.config

import cn.aing.uptags.Support
import org.bukkit.command.CommandSender
import org.bukkit.configuration.file.YamlConfiguration
import org.bukkit.plugin.java.JavaPlugin
import java.io.File
import java.text.MessageFormat
import java.util.LinkedHashMap

class MessageService(private val plugin: JavaPlugin) {
    private val messages = LinkedHashMap<String, String>()

    fun load() {
        messages.clear()
        messages.putAll(defaultMessages())
        val file = File(plugin.dataFolder, "messages.yml")
        val yaml = YamlConfiguration.loadConfiguration(file)
        yaml.getKeys(false).forEach { key ->
            messages[key] = yaml.getString(key, messages[key] ?: key) ?: key
        }
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

    private fun defaultMessages(): Map<String, String> = linkedMapOf(
        "prefix" to "&d[称号] &r",
        "default-unlocked" to "&a你自动解锁了 {0} 个默认/权限称号",
        "player-only" to "&c该命令只能由玩家执行。",
        "no-permission" to "&c你没有权限执行这个操作。",
        "reloaded" to "&a配置已重载。",
        "tag-not-found" to "&c未找到称号: {0}",
        "not-owned" to "&c你还没有拥有这个称号。",
        "tag-equipped" to "&a已佩戴称号: {0}",
        "tag-unequipped" to "&a已卸下当前称号。",
        "force-default-block" to "&c当前已启用强制默认称号，无法卸下。",
        "economy-unavailable" to "&c当前服务器没有可用的 {0} 系统。",
        "not-enough" to "&c你的{1}不足，需要 {0}。",
        "condition-failed" to "&c当前条件不足，无法购买。",
        "buff-upgraded" to "&a已将 Buff {0} 升级到 {1}。",
        "buff-enabled" to "&a已启用 Buff {0}。",
        "buff-disabled" to "&e已关闭 Buff {0}。",
        "particle-bought" to "&a已购买粒子效果: {0}",
        "particle-selected" to "&a已切换粒子效果: {0}",
        "particle-cleared" to "&e已取消当前粒子效果。",
        "tag-given" to "&a已给予玩家称号: {0}",
        "tag-taken" to "&e已移除玩家称号: {0}",
        "tag-already-owned" to "&e该玩家已拥有称号: {0}",
        "tag-created" to "&a已创建新称号: {0}",
        "tag-deleted" to "&e已删除称号: {0}",
        "tag-updated" to "&a称号配置已更新。",
        "invalid-target" to "&c目标玩家不存在。",
        "help" to "&e/tags &7打开仓库&e | /tags equip <id>&7佩戴&e | /tags unequip&7卸下&e | /tags upgrade <id>&7打开强化",
        "admin-help" to "&e/tags admin give/take/scroll/tag ...",
        "scroll-help" to "&e/tags admin scroll give <玩家> <卷轴Key|buff_all|particle_all> [数量]",
        "scroll-invalid-definition" to "&c无效的升级卷定义: 类型={0}, 目标={1}",
        "scroll-given" to "&a已向 {0} 发放 {3} 张{1}升级卷，目标: {2}",
        "scroll-invalid-item" to "&c这张升级卷的数据无效，无法使用。",
        "scroll-item-missing" to "&c你的升级卷已变化或不存在，请重新右键使用。",
        "scroll-no-eligible-tags" to "&c你当前没有任何可对这张升级卷生效的称号。",
        "scroll-no-effect" to "&c这张升级卷无法继续对所选称号生效。",
        "scroll-applied-buff" to "&a已对称号 {1} 使用 Buff 升级卷，目标: {0}",
        "scroll-applied-particle" to "&a已对称号 {1} 使用粒子解锁卷，目标: {0}",
        "quick-create-usage" to "&e用法: /tags create <称号> [权限] [buff组] [粒子组]",
        "quick-create-no-group" to "&c创建失败：当前没有可用的升级组，请先检查 upgrades.yml",
        "quick-create-success" to "&a创建成功: {0}，权限={1}，Buff组={2}，粒子组={3}。可继续在 tags.yml 中修改显示名（支持 Paper RGB）",
        "quick-create-failed" to "&c创建失败，请检查称号是否已存在，或 Buff组 / 粒子组 是否有效。",
    )
}
