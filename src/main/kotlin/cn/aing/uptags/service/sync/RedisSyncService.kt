package cn.aing.uptags.service.sync

interface RedisSyncService {
    fun start() {}

    fun publish(message: PlayerSyncMessage) {}

    fun shutdown() {}
}
