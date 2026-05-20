package cn.aing.uptags.service.sync

import org.bukkit.plugin.java.JavaPlugin
import redis.clients.jedis.JedisPubSub
import redis.clients.jedis.JedisPooled
import redis.clients.jedis.UnifiedJedis
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.min

class JedisRedisSyncService(
    private val plugin: JavaPlugin,
    private val uri: String,
    private val channel: String,
    private val onMessage: (PlayerSyncMessage) -> Unit,
) : RedisSyncService {
    private var publisher: UnifiedJedis? = null
    private var subscriberThread: Thread? = null
    private val running = AtomicBoolean(false)

    @Volatile
    private var activePubSub: JedisPubSub? = null

    override fun start() {
        if (!running.compareAndSet(false, true)) {
            return
        }
        publisher = createClient()
        subscriberThread = Thread(::runSubscriberLoop, "${plugin.name}-redis-sub").apply {
            isDaemon = true
            start()
        }
    }

    override fun publish(message: PlayerSyncMessage) {
        try {
            publisher?.publish(channel, encode(message))
        } catch (ex: Exception) {
            plugin.logger.warning("Redis publish failed: ${ex.message}")
        }
    }

    override fun shutdown() {
        running.set(false)
        runCatching { activePubSub?.unsubscribe() }
        runCatching { publisher?.close() }
        subscriberThread?.interrupt()
        subscriberThread = null
        publisher = null
    }

    private fun runSubscriberLoop() {
        var backoffMillis = 1_000L
        while (running.get()) {
            var subscriber: UnifiedJedis? = null
            val pubSub = object : JedisPubSub() {
                override fun onMessage(channel: String, message: String) {
                    decode(message)?.let(onMessage)
                }
            }
            activePubSub = pubSub
            try {
                subscriber = createClient()
                subscriber.subscribe(pubSub, channel)
                backoffMillis = 1_000L
            } catch (ex: Exception) {
                if (running.get()) {
                    plugin.logger.warning("Redis subscription failed, retrying in ${backoffMillis}ms: ${ex.message}")
                    runCatching { Thread.sleep(backoffMillis) }
                    backoffMillis = min(backoffMillis * 2L, 30_000L)
                }
            } finally {
                runCatching { pubSub.unsubscribe() }
                runCatching { subscriber?.close() }
                if (activePubSub === pubSub) {
                    activePubSub = null
                }
            }
        }
    }

    private fun createClient(): UnifiedJedis = JedisPooled(uri)

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
