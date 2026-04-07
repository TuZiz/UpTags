package cn.aing.uptags

import cn.aing.uptags.command.TagsCommand
import cn.aing.uptags.compat.PlatformScheduler
import cn.aing.uptags.config.ConfigRegistry
import cn.aing.uptags.config.MessageService
import cn.aing.uptags.gui.MenuService
import cn.aing.uptags.listener.PlayerListener
import cn.aing.uptags.listener.ScrollListener
import cn.aing.uptags.repository.PlayerDataRepository
import cn.aing.uptags.repository.store.PlayerDataStore
import cn.aing.uptags.repository.store.PostgresPlayerDataStore
import cn.aing.uptags.service.EconomyBridge
import cn.aing.uptags.service.EffectService
import cn.aing.uptags.service.ScrollService
import cn.aing.uptags.service.TagService
import cn.aing.uptags.service.UpTagsPlaceholderExpansion
import cn.aing.uptags.service.sync.JedisRedisSyncService
import cn.aing.uptags.service.sync.NoopRedisSyncService
import cn.aing.uptags.service.sync.PlayerSyncService
import cn.aing.uptags.service.sync.RedisSyncService
import org.bukkit.Bukkit
import org.bukkit.event.HandlerList
import org.bukkit.plugin.java.JavaPlugin

class UpTagsPlugin : JavaPlugin() {
    lateinit var config: ConfigRegistry
        private set
    private lateinit var messages: MessageService
    private lateinit var scheduler: PlatformScheduler
    private lateinit var repository: PlayerDataRepository
    private lateinit var economyBridge: EconomyBridge
    private lateinit var tagService: TagService
    private lateinit var scrollService: ScrollService
    private lateinit var menuService: MenuService
    private lateinit var effectService: EffectService
    private lateinit var playerSyncService: PlayerSyncService
    private lateinit var redisSyncService: RedisSyncService
    private var placeholderExpansion: UpTagsPlaceholderExpansion? = null

    override fun onEnable() {
        reloadPlugin()
    }

    override fun onDisable() {
        shutdownServices()
    }

    fun reloadPlugin() {
        shutdownServices()
        HandlerList.unregisterAll(this)
        if (!dataFolder.exists()) {
            dataFolder.mkdirs()
        }

        scheduler = PlatformScheduler(this)
        config = ConfigRegistry(this)
        config.load()
        messages = MessageService(this)
        messages.load()

        repository = PlayerDataRepository(this, scheduler, createStore())
        playerSyncService = PlayerSyncService(repository, scheduler)
        redisSyncService = createRedisSyncService()
        repository.attachSync(redisSyncService, config.sync.serverId)

        economyBridge = EconomyBridge(this)
        economyBridge.hook()
        tagService = TagService(this, config, repository, economyBridge, messages)
        scrollService = ScrollService(this, config, tagService, messages)
        menuService = MenuService(this, config, tagService, scrollService, messages)
        effectService = EffectService(this, scheduler, config, tagService)

        server.pluginManager.registerEvents(menuService, this)
        server.pluginManager.registerEvents(PlayerListener(tagService, repository, effectService), this)
        server.pluginManager.registerEvents(ScrollListener(menuService, scrollService, messages), this)

        getCommand("tags")?.let { command ->
            val executor = TagsCommand(this, tagService, scrollService, menuService, messages)
            command.setExecutor(executor)
            command.tabCompleter = executor
        }

        placeholderExpansion?.unregister()
        placeholderExpansion = null
        val papi = Bukkit.getPluginManager().getPlugin("PlaceholderAPI")
        if (papi != null && papi.isEnabled) {
            placeholderExpansion = UpTagsPlaceholderExpansion(this, tagService).also { it.register() }
        }

        redisSyncService.start()
        server.onlinePlayers.forEach { player ->
            tagService.preparePlayer(player, false)
            effectService.startPlayer(player)
        }
    }

    private fun createStore(): PlayerDataStore {
        return PostgresPlayerDataStore(
            jdbcUrl = config.storage.pg.jdbcUrl,
            username = config.storage.pg.username,
            password = config.storage.pg.password,
            table = config.storage.pg.table,
        )
    }

    private fun createRedisSyncService(): RedisSyncService {
        return if (config.sync.redis.enabled) {
            JedisRedisSyncService(
                plugin = this,
                uri = config.sync.redis.uri,
                channel = config.sync.redis.channel,
                onMessage = { message ->
                    if (message.serverId != config.sync.serverId) {
                        playerSyncService.handleRemoteInvalidation(message)
                    }
                },
            )
        } else {
            NoopRedisSyncService()
        }
    }

    private fun shutdownServices() {
        if (this::effectService.isInitialized) {
            effectService.stopAll()
        }
        if (this::redisSyncService.isInitialized) {
            redisSyncService.shutdown()
        }
        if (this::repository.isInitialized) {
            repository.saveAllSync()
            repository.shutdown()
        }
        if (this::scheduler.isInitialized) {
            scheduler.shutdown()
        }
        placeholderExpansion?.unregister()
        placeholderExpansion = null
    }
}
