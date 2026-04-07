package cn.aing.uptags.repository

import cn.aing.uptags.model.runtime.PlayerTagData

data class PlayerCacheEntry(
    var data: PlayerTagData,
    var version: Long,
    var stale: Boolean = false,
    var lastSyncAt: Long = System.currentTimeMillis(),
)
