package cn.aing.uptags.repository

import cn.aing.uptags.model.runtime.PlayerTagData

data class PlayerDataSnapshot(
    val data: PlayerTagData,
    val version: Long,
    val updatedAt: Long,
)
