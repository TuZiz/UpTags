package cn.aing.uptags.service.sync

import org.bukkit.plugin.java.JavaPlugin
import redis.clients.jedis.HostAndPort
import redis.clients.jedis.JedisPubSub
import redis.clients.jedis.JedisPooled
import redis.clients.jedis.UnifiedJedis
import java.net.URI
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean

class JedisRedisSyncService(
    private val plugin: JavaPlugin,
    private val uri: String,
    private val channel: String,
    private val onMessage: (PlayerSyncMessage) -> Unit,
) : RedisSyncService {
    private var publisher: UnifiedJedis? = null
    private var subscriberClient: UnifiedJedis? = null
    private var subscriberThread: Thread? = null
    private val running = AtomicBoolean(false)

    override fun start() {
        if (!running.compareAndSet(false, true)) {
            return
        }
        publisher = createClient()
        subscriberClient = createClient()
        val pubSub = object : JedisPubSub() {
            override fun onMessage(channel: String, message: String) {
                decode(message)?.let(onMessage)
            }
        }
        subscriberThread = Thread({
            try {
                subscriberClient?.subscribe(pubSub, channel)
            } catch (ex: Exception) {
                if (running.get()) {
                    plugin.logger.warning("Redis 订阅失败: ${ex.message}")
                }
            }
        }, "${plugin.name}-redis-sub").apply {
            isDaemon = true
            start()
        }
    }

    override fun publish(message: PlayerSyncMessage) {
        try {
            publisher?.publish(channel, encode(message))
        } catch (ex: Exception) {
            plugin.logger.warning("Redis 发布失败: ${ex.message}")
        }
    }

    override fun shutdown() {
        running.set(false)
        runCatching { subscriberClient?.close() }
        runCatching { publisher?.close() }
        subscriberThread?.interrupt()
        subscriberThread = null
        subscriberClient = null
        publisher = null
    }

    private fun createClient(): UnifiedJedis {
        val parsed = URI(uri)
        return if (parsed.scheme == "redis") {
            val port = if (parsed.port == -1) 6379 else parsed.port
            JedisPooled(HostAndPort(parsed.host, port))
        } else {
            JedisPooled(uri)
        }
    }

    private fun encode(message: PlayerSyncMessage): String = listOf(
        message.uniqueId.toString(),
        message.version.toString(),
        message.serverId,
        message.updatedAt.toString(),
    ).joinToString("|")

    private fun decode(raw: String): PlayerSyncMessage? {
        val parts = raw.split('|')
        if (parts.size != 4) return null
        return runCatching {
            PlayerSyncMessage(
                uniqueId = UUID.fromString(parts[0]),
                version = parts[1].toLong(),
                serverId = parts[2],
                updatedAt = parts[3].toLong(),
            )
        }.getOrNull()
    }
}
