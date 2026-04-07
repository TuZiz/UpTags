package cn.aing.uptags.config

enum class StorageMode {
    YML,
    MYSQL,
    PG;

    companion object {
        fun from(raw: String?): StorageMode {
            if (raw.isNullOrBlank()) {
                return YML
            }
            return entries.firstOrNull { it.name.equals(raw.trim(), ignoreCase = true) } ?: YML
        }
    }
}

data class StorageSettings(
    val mode: StorageMode,
    val yml: YamlStorageSettings,
    val mysql: MysqlSettings,
    val pg: PostgresSettings,
)

data class YamlStorageSettings(
    val file: String,
)

data class MysqlSettings(
    val jdbcUrl: String,
    val username: String,
    val password: String,
    val table: String,
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
