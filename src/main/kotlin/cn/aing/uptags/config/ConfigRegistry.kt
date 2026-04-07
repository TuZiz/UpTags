package cn.aing.uptags.config

import cn.aing.uptags.model.config.BuffDefinition
import cn.aing.uptags.model.config.CostDefinition
import cn.aing.uptags.model.config.CurrencyType
import cn.aing.uptags.model.config.GuiKey
import cn.aing.uptags.model.config.GuiLayout
import cn.aing.uptags.model.config.GuiTemplate
import cn.aing.uptags.model.config.ItemTemplate
import cn.aing.uptags.model.config.ParticleDefinition
import cn.aing.uptags.model.config.PluginSettings
import cn.aing.uptags.model.config.ScrollDefinition
import cn.aing.uptags.model.config.TagDefinition
import cn.aing.uptags.model.config.UpgradeGroupDefinition
import cn.aing.uptags.model.runtime.ScrollKind
import org.bukkit.NamespacedKey
import org.bukkit.Registry
import org.bukkit.configuration.ConfigurationSection
import org.bukkit.configuration.file.YamlConfiguration
import org.bukkit.plugin.java.JavaPlugin
import org.bukkit.potion.PotionEffectType
import java.io.File
import java.io.IOException
import java.util.LinkedHashMap
import java.util.LinkedHashSet

class ConfigRegistry(private val plugin: JavaPlugin) {
    val tags: MutableMap<String, TagDefinition> = LinkedHashMap()
    val buffs: MutableMap<String, BuffDefinition> = LinkedHashMap()
    val particles: MutableMap<String, ParticleDefinition> = LinkedHashMap()
    val scrolls: MutableMap<String, ScrollDefinition> = LinkedHashMap()
    val upgradeGroups: MutableMap<String, UpgradeGroupDefinition> = LinkedHashMap()
    val rarityDisplays: MutableMap<String, String> = LinkedHashMap()
    val rarityUpgradeGroups: MutableMap<String, String> = LinkedHashMap()
    var settings: PluginSettings = PluginSettings(20, true, "newbie")
        private set
    lateinit var warehouseLayout: GuiLayout
        private set
    lateinit var upgradeLayout: GuiLayout
        private set
    lateinit var storage: StorageSettings
        private set
    lateinit var sync: SyncSettings
        private set
    var defaultTagRarity: String = "COMMON"
        private set
    var defaultTagUnlocked: Boolean = false
        private set

    fun load() {
        saveDefaultIfAbsent("config.yml")
        saveDefaultIfAbsent("messages.yml")
        saveDefaultIfAbsent("tags.yml")
        saveDefaultIfAbsent("upgrades.yml")
        saveDefaultIfAbsent("gui/warehouse.yml")
        saveDefaultIfAbsent("gui/upgrade.yml")
        loadSettings()
        loadTags()
        loadUpgrades()
        warehouseLayout = loadGuiLayout("gui/warehouse.yml")
        upgradeLayout = loadGuiLayout("gui/upgrade.yml")
    }

    fun saveTags() {
        val file = File(plugin.dataFolder, "tags.yml")
        val yaml = YamlConfiguration()
        if (rarityDisplays.isNotEmpty()) {
            rarityDisplays.forEach { (key, value) -> yaml.set("rarity-display.$key", value) }
        }
        if (rarityUpgradeGroups.isNotEmpty()) {
            rarityUpgradeGroups.forEach { (key, value) -> yaml.set("rarity-upgrade-group.$key", value) }
        }
        yaml.set("tag-template.rarity", defaultTagRarity)
        yaml.set("tag-template.default-unlocked", defaultTagUnlocked)
        tags.values.forEach { definition ->
            val path = "tags.${definition.id}"
            yaml.set("$path.display", definition.display)
            yaml.set("$path.description", definition.description)
            yaml.set("$path.rarity", definition.rarity)
            yaml.set("$path.default-unlocked", definition.defaultUnlocked)
            yaml.set("$path.upgrade-groups", definition.upgradeGroups)
            yaml.set("$path.permission", definition.permission)
        }
        try {
            yaml.save(file)
        } catch (ex: IOException) {
            plugin.logger.warning("保存 tags.yml 失败: ${ex.message}")
        }
    }

    fun createTag(id: String): TagDefinition {
        val normalized = id.trim()
        val definition = TagDefinition(
            id = normalized,
            display = "&#FFFFFF[&#AAAAAA$normalized&#FFFFFF]",
            description = listOf("&7新的称号"),
            rarity = defaultTagRarity,
            defaultUnlocked = defaultTagUnlocked,
            upgradeGroups = defaultGroupsForRarity(defaultTagRarity).toMutableList(),
            permission = "uptags.tag.$normalized",
        )
        tags[normalized] = definition
        saveTags()
        return definition
    }

    fun deleteTag(id: String) {
        tags.remove(id)
        saveTags()
    }

    fun defaultGroupsForRarity(rarity: String): List<String> {
        val mapped = rarityUpgradeGroups[rarity.uppercase()] ?: return emptyList()
        return listOf(mapped)
    }

    fun rarityDisplay(rarity: String): String = rarityDisplays[rarity.uppercase()] ?: rarity

    fun allRarities(): List<String> = if (rarityDisplays.isNotEmpty()) rarityDisplays.keys.toList() else rarityUpgradeGroups.keys.toList()

    fun hasUpgradeGroup(groupId: String): Boolean = upgradeGroups.containsKey(groupId)

    fun allUpgradeGroups(): List<String> = upgradeGroups.keys.toList()

    fun firstUpgradeGroup(): String? = upgradeGroups.keys.firstOrNull()

    private fun saveDefaultIfAbsent(resourcePath: String) {
        val file = File(plugin.dataFolder, resourcePath)
        if (!file.exists()) {
            plugin.saveResource(resourcePath, false)
        }
    }

    private fun loadSettings() {
        val yaml = YamlConfiguration.loadConfiguration(File(plugin.dataFolder, "config.yml"))
        val interval = yaml.getLong("settings.effect-tick-interval", 20).coerceAtLeast(10)
        val enabled = yaml.getBoolean("settings.force-default-tag.enabled", true)
        val tagId = yaml.getString("settings.force-default-tag.tag-id", "newbie") ?: "newbie"
        settings = PluginSettings(interval, enabled, tagId)
        storage = StorageSettings(
            pg = PostgresSettings(
                jdbcUrl = yaml.getString("storage.pg.jdbc-url", "jdbc:postgresql://127.0.0.1:5432/minecraft") ?: "",
                username = yaml.getString("storage.pg.username", "postgres") ?: "postgres",
                password = yaml.getString("storage.pg.password", "") ?: "",
                table = yaml.getString("storage.pg.table", "uptags_player_data") ?: "uptags_player_data",
            ),
        )
        sync = SyncSettings(
            serverId = yaml.getString("sync.server-id", "server-1") ?: "server-1",
            redis = RedisSettings(
                enabled = yaml.getBoolean("sync.redis.enabled", false),
                uri = yaml.getString("sync.redis.uri", "redis://127.0.0.1:6379") ?: "",
                channel = yaml.getString("sync.redis.channel", "uptags:player-sync") ?: "uptags:player-sync",
            ),
            onlineRefreshDelayTicks = yaml.getLong("sync.online-refresh-delay-ticks", 20L),
            staleMaxAgeSeconds = yaml.getLong("sync.stale-max-age-seconds", 3L),
        )
    }

    private fun loadTags() {
        tags.clear()
        rarityDisplays.clear()
        rarityUpgradeGroups.clear()
        val yaml = YamlConfiguration.loadConfiguration(File(plugin.dataFolder, "tags.yml"))
        yaml.getConfigurationSection("rarity-display")?.getKeys(false)?.forEach { key ->
            rarityDisplays[key.uppercase()] = yaml.getString("rarity-display.$key", key) ?: key
        }
        yaml.getConfigurationSection("rarity-upgrade-group")?.getKeys(false)?.forEach { key ->
            rarityUpgradeGroups[key.uppercase()] = yaml.getString("rarity-upgrade-group.$key", key) ?: key
        }
        defaultTagRarity = (yaml.getString("tag-template.rarity", "COMMON") ?: "COMMON").uppercase()
        defaultTagUnlocked = yaml.getBoolean("tag-template.default-unlocked", false)
        yaml.getConfigurationSection("tags")?.getKeys(false)?.forEach { tagId ->
            val section = yaml.getConfigurationSection("tags.$tagId") ?: return@forEach
            val rarity = (section.getString("rarity", defaultTagRarity) ?: defaultTagRarity).uppercase()
            val groups = section.getStringList("upgrade-groups").ifEmpty { defaultGroupsForRarity(rarity) }
            tags[tagId] = TagDefinition(
                id = tagId,
                display = section.getString("display", tagId) ?: tagId,
                description = section.getStringList("description"),
                rarity = rarity,
                defaultUnlocked = section.getBoolean("default-unlocked", defaultTagUnlocked),
                upgradeGroups = groups.toMutableList(),
                permission = section.getString("permission")?.takeIf { it.isNotBlank() },
            )
        }
    }

    private fun loadUpgrades() {
        buffs.clear()
        particles.clear()
        scrolls.clear()
        upgradeGroups.clear()
        val yaml = YamlConfiguration.loadConfiguration(File(plugin.dataFolder, "upgrades.yml"))
        yaml.getConfigurationSection("buffs")?.getKeys(false)?.forEach { buffId ->
            val section = yaml.getConfigurationSection("buffs.$buffId") ?: return@forEach
            val type = resolvePotionEffectType(section.getString("type", "") ?: "")
            if (type == null) {
                plugin.logger.warning("未知 Buff 类型: $buffId")
                return@forEach
            }
            buffs[buffId] = BuffDefinition(
                id = buffId,
                type = type,
                display = section.getString("display", buffId) ?: buffId,
                maxLevel = section.getInt("max-level", 1).coerceAtLeast(1),
                duration = section.getInt("duration", 200).coerceAtLeast(1),
                cost = parseCost(section.getConfigurationSection("cost")),
            )
        }
        yaml.getConfigurationSection("groups")?.getKeys(false)?.forEach { groupId ->
            val section = yaml.getConfigurationSection("groups.$groupId") ?: return@forEach
            upgradeGroups[groupId] = UpgradeGroupDefinition(
                id = groupId,
                name = section.getString("name", groupId) ?: groupId,
                display = section.getString("display", groupId) ?: groupId,
                buffs = LinkedHashSet(section.getStringList("buffs")),
                particles = LinkedHashSet(section.getStringList("particles")),
            )
        }
        yaml.getConfigurationSection("particles")?.getKeys(false)?.forEach { particleId ->
            val section = yaml.getConfigurationSection("particles.$particleId") ?: return@forEach
            particles[particleId] = ParticleDefinition(
                id = particleId,
                display = section.getString("display", particleId) ?: particleId,
                pattern = section.getString("pattern", particleId) ?: particleId,
                cost = parseCost(section.getConfigurationSection("cost")),
            )
        }
        yaml.getConfigurationSection("scrolls")?.getKeys(false)?.forEach { scrollKey ->
            val section = yaml.getConfigurationSection("scrolls.$scrollKey") ?: return@forEach
            val kind = ScrollKind.from(section.getString("type")) ?: return@forEach
            val targetId = section.getString("target")?.takeIf { it.isNotBlank() } ?: return@forEach
            scrolls[scrollKey] = ScrollDefinition(
                key = scrollKey,
                kind = kind,
                targetId = targetId,
                material = section.getString("material", if (kind == ScrollKind.BUFF) "ENCHANTED_BOOK" else "NETHER_STAR") ?: "PAPER",
                name = section.getString("name", scrollKey) ?: scrollKey,
                lore = section.getStringList("lore"),
                glow = section.getBoolean("glow", true),
            )
        }
    }

    private fun loadGuiLayout(path: String): GuiLayout {
        val yaml = YamlConfiguration.loadConfiguration(File(plugin.dataFolder, path))
        val plain = yaml.getStringList("GuiPlain")
        val keys = LinkedHashMap<Char, GuiKey>()
        yaml.getConfigurationSection("GuiKey")?.getKeys(false)?.forEach { token ->
            val section = yaml.getConfigurationSection("GuiKey.$token") ?: return@forEach
            keys[token.first()] = GuiKey(
                iconFunction = section.getString("IconFunction"),
                base = templateFromSection(section),
                has = templateFromSection(section.getConfigurationSection("has")),
                normal = templateFromSection(section.getConfigurationSection("normal")),
            )
        }
        val templates = LinkedHashMap<String, GuiTemplate>()
        yaml.getConfigurationSection("templates")?.getKeys(false)?.forEach { key ->
            val section = yaml.getConfigurationSection("templates.$key") ?: return@forEach
            templates[key] = GuiTemplate(
                material = section.getString("material", "PAPER") ?: "PAPER",
                name = section.getString("name", key) ?: key,
                lore = section.getStringList("lore"),
                loreMaxed = section.getStringList("lore-maxed"),
            )
        }
        return GuiLayout(yaml.getString("Title", "&0UpTags") ?: "&0UpTags", plain, keys, templates)
    }

    private fun templateFromSection(section: ConfigurationSection?): ItemTemplate? {
        if (section == null) {
            return null
        }
        return ItemTemplate(
            material = section.getString("Material", "PAPER") ?: "PAPER",
            name = section.getString("Name", " ") ?: " ",
            lore = section.getStringList("Lore"),
        )
    }

    private fun parseCost(section: ConfigurationSection?): CostDefinition {
        if (section == null) {
            return CostDefinition()
        }
        val levels = LinkedHashMap<Int, Double>()
        section.getConfigurationSection("levels")?.getKeys(false)?.forEach { key ->
            key.toIntOrNull()?.let { level ->
                levels[level] = section.getDouble("levels.$key", 0.0)
            }
        }
        return CostDefinition(
            type = CurrencyType.from(section.getString("type", "POINTS")),
            amount = section.getDouble("amount", 0.0),
            levelAmounts = levels,
            conditions = section.getStringList("conditions"),
        )
    }

    private fun resolvePotionEffectType(raw: String): PotionEffectType? {
        val normalized = raw.trim()
        if (normalized.isBlank()) {
            return null
        }
        val key = when (normalized.uppercase()) {
            "INCREASE_DAMAGE" -> "strength"
            "FAST_DIGGING" -> "haste"
            "JUMP" -> "jump_boost"
            "DAMAGE_RESISTANCE" -> "resistance"
            "HEAL" -> "instant_health"
            else -> normalized.lowercase()
        }
        return Registry.EFFECT.get(NamespacedKey.minecraft(key))
    }
}
