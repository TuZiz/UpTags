package cn.aing.uptags.command

import cn.aing.uptags.Support
import org.bukkit.command.CommandSender

internal object TagsHelpRenderer {
    fun send(sender: CommandSender) {
        val lines = mutableListOf<String>()
        lines += "&#A78BFA[称号] &#E2E8F0玩家命令"
        if (AdminAccess.hasUse(sender)) {
            lines += "&#FDE047/tags &#E2E8F0打开称号仓库"
            lines += "&#FDE047/tags shop &#E2E8F0打开称号商店"
            lines += "&#FDE047/tags equip <id> &#E2E8F0佩戴已拥有的称号"
            lines += "&#FDE047/tags unequip &#E2E8F0卸下当前称号"
            lines += "&#FDE047/tags upgrade <id> &#E2E8F0打开称号强化界面"
            lines += "&#FDE047/tags custom preview <方式> &#E2E8F0自定义称号预览与购买"
        } else {
            lines += "&#94A3B8你当前没有普通称号命令权限。"
        }
        if (AdminAccess.has(sender, AdminAccess.RELOAD) || AdminAccess.has(sender, AdminAccess.CREATE) || AdminAccess.hasAnyAdmin(sender)) {
            lines += "&#A78BFA[称号] &#E2E8F0管理员命令"
            if (AdminAccess.has(sender, AdminAccess.RELOAD)) {
                lines += "&#FDE047/tags reload &#E2E8F0重载插件配置"
            }
            if (AdminAccess.has(sender, AdminAccess.CREATE)) {
                lines += "&#FDE047/tags create <称号> [权限] [buff组] [粒子组] &#E2E8F0快速创建配置称号"
            }
            if (AdminAccess.hasAnyAdmin(sender)) {
                lines += "&#FDE047/tags admin manage <玩家> &#E2E8F0打开玩家称号仓库管理 GUI"
                lines += "&#FDE047/tags admin info <玩家> &#E2E8F0查看玩家称号完整详情"
                lines += "&#FDE047/tags admin give/take <玩家> <称号ID> &#E2E8F0授予或移除称号"
                lines += "&#FDE047/tags admin coin <give|take|set> <玩家> <数量> &#E2E8F0管理称号币"
                lines += "&#FDE047/tags admin buff <set|enable|disable|detach> ... &#E2E8F0管理目标称号 Buff"
                lines += "&#FDE047/tags admin particle <give|take|select|clear|detach> ... &#E2E8F0管理目标称号粒子"
                lines += "&#FDE047/tags admin custom <list|equip|delete> ... &#E2E8F0管理玩家自定义称号"
                lines += "&#FDE047/tags admin scroll give <在线玩家> <卷轴ID> [数量] &#E2E8F0发放强化卷轴"
                lines += "&#FDE047/tags admin tag <create|delete|setdisplay|setrarity|setgroups|setdefault> ... &#E2E8F0管理配置称号"
            }
        }
        lines.forEach { sender.sendMessage(Support.color(it)) }
    }
}
