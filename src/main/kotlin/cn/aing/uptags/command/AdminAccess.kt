package cn.aing.uptags.command

import org.bukkit.command.CommandSender

object AdminAccess {
    const val USE = "uptags.use"
    const val ADMIN = "uptags.admin"
    const val RELOAD = "uptags.reload"
    const val CREATE = "uptags.create"
    const val GIVE = "uptags.admin.give"
    const val TAKE = "uptags.admin.take"
    const val SCROLL_GIVE = "uptags.admin.scroll.give"
    const val TAG_ALL = "uptags.admin.tag.*"
    const val TAG_CREATE = "uptags.admin.tag.create"
    const val TAG_DELETE = "uptags.admin.tag.delete"
    const val TAG_EDIT = "uptags.admin.tag.edit"
    const val TAG_SET_DISPLAY = "uptags.admin.tag.setdisplay"
    const val TAG_SET_RARITY = "uptags.admin.tag.setrarity"
    const val TAG_SET_GROUPS = "uptags.admin.tag.setgroups"
    const val TAG_SET_DEFAULT = "uptags.admin.tag.setdefault"
    const val MANAGE = "uptags.admin.manage"
    const val INFO = "uptags.admin.info"
    const val EQUIP = "uptags.admin.equip"
    const val UNEQUIP = "uptags.admin.unequip"
    const val COIN_ALL = "uptags.admin.coin.*"
    const val COIN_ADD = "uptags.admin.coin.add"
    const val COIN_TAKE = "uptags.admin.coin.take"
    const val COIN_SET = "uptags.admin.coin.set"
    const val BUFF_ALL = "uptags.admin.buff.*"
    const val BUFF_SET = "uptags.admin.buff.set"
    const val BUFF_ENABLE = "uptags.admin.buff.enable"
    const val BUFF_DISABLE = "uptags.admin.buff.disable"
    const val BUFF_DETACH = "uptags.admin.buff.detach"
    const val PARTICLE_ALL = "uptags.admin.particle.*"
    const val PARTICLE_GIVE = "uptags.admin.particle.give"
    const val PARTICLE_TAKE = "uptags.admin.particle.take"
    const val PARTICLE_SELECT = "uptags.admin.particle.select"
    const val PARTICLE_CLEAR = "uptags.admin.particle.clear"
    const val PARTICLE_DETACH = "uptags.admin.particle.detach"
    const val CUSTOM_ALL = "uptags.admin.custom.*"
    const val CUSTOM_LIST = "uptags.admin.custom.list"
    const val CUSTOM_EQUIP = "uptags.admin.custom.equip"
    const val CUSTOM_DELETE = "uptags.admin.custom.delete"

    private val adminPermissions = setOf(
        RELOAD,
        CREATE,
        GIVE,
        TAKE,
        SCROLL_GIVE,
        TAG_ALL,
        TAG_CREATE,
        TAG_DELETE,
        TAG_EDIT,
        TAG_SET_DISPLAY,
        TAG_SET_RARITY,
        TAG_SET_GROUPS,
        TAG_SET_DEFAULT,
        MANAGE,
        INFO,
        EQUIP,
        UNEQUIP,
        COIN_ALL,
        COIN_ADD,
        COIN_TAKE,
        COIN_SET,
        BUFF_ALL,
        BUFF_SET,
        BUFF_ENABLE,
        BUFF_DISABLE,
        BUFF_DETACH,
        PARTICLE_ALL,
        PARTICLE_GIVE,
        PARTICLE_TAKE,
        PARTICLE_SELECT,
        PARTICLE_CLEAR,
        PARTICLE_DETACH,
        CUSTOM_ALL,
        CUSTOM_LIST,
        CUSTOM_EQUIP,
        CUSTOM_DELETE,
    )

    fun hasUse(sender: CommandSender): Boolean = sender.hasPermission(USE) || sender.hasPermission(ADMIN)

    fun has(sender: CommandSender, permission: String, vararg inherited: String): Boolean {
        return sender.hasPermission(ADMIN) ||
            sender.hasPermission(permission) ||
            inherited.any(sender::hasPermission)
    }

    fun hasAny(sender: CommandSender, permissions: Iterable<String>): Boolean {
        return sender.hasPermission(ADMIN) || permissions.any(sender::hasPermission)
    }

    fun hasAnyAdmin(sender: CommandSender): Boolean = hasAny(sender, adminPermissions)
}
