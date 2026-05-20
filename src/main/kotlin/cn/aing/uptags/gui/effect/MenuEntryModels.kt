package cn.aing.uptags.gui.effect

import org.bukkit.inventory.ItemStack

internal enum class EntryKind {
    BUFF,
    PARTICLE,
}

internal data class UpgradeEntry(val id: String, val kind: EntryKind, val item: ItemStack)
