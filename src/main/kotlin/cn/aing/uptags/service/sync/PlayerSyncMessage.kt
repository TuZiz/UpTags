package cn.aing.uptags.service.sync

import java.util.UUID

data class PlayerSyncMessage(
    val uniqueId: UUID,
    val version: Long,
    val serverId: String,
    val updatedAt: Long,
)
