package cn.aing.uptags.config

data class StorageSettings(
    val pg: PostgresSettings,
)

data class PostgresSettings(
    val jdbcUrl: String,
    val username: String,
    val password: String,
    val table: String,
)

data class SyncSettings(
    val serverId: String,
    val redis: RedisSettings,
    val onlineRefreshDelayTicks: Long,
    val staleMaxAgeSeconds: Long,
)

data class RedisSettings(
    val enabled: Boolean,
    val uri: String,
    val channel: String,
)
