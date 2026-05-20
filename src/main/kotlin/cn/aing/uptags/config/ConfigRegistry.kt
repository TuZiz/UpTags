package cn.aing.uptags.config

import cn.aing.uptags.Support
import cn.aing.uptags.model.config.BuffDefinition
import cn.aing.uptags.model.config.CostDefinition
import cn.aing.uptags.model.config.CurrencyType
import cn.aing.uptags.model.config.CustomTitlePreset
import cn.aing.uptags.model.config.CustomTitleSettings
import cn.aing.uptags.model.config.DetachCostSettings
import cn.aing.uptags.model.config.DetachSettings
import cn.aing.uptags.model.config.GuiKey
import cn.aing.uptags.model.config.GuiLayout
import cn.aing.uptags.model.config.GuiTemplate
import cn.aing.uptags.model.config.ItemTemplate
import cn.aing.uptags.model.config.ParticleDefinition
import cn.aing.uptags.model.config.PluginSettings
import cn.aing.uptags.model.config.ScrollDefinition
import cn.aing.uptags.model.config.ShopProductDefinition
import cn.aing.uptags.model.config.ShopProductType
import cn.aing.uptags.model.config.SubmitItemDefinition
import cn.aing.uptags.model.config.TagDefinition
import cn.aing.uptags.model.config.TagShopDefinition
import cn.aing.uptags.model.config.UpgradeGroupDefinition
import cn.aing.uptags.model.runtime.ScrollKind
import cn.aing.uptags.util.UnicodeText
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
    private val legacyCustomTagPrefix = "custom-"
    val tags: MutableMap<String, TagDefinition> = LinkedHashMap()
    val buffs: MutableMap<String, BuffDefinition> = LinkedHashMap()
    val particles: MutableMap<String, ParticleDefinition> = LinkedHashMap()
    val scrolls: MutableMap<String, ScrollDefinition> = LinkedHashMap()
    val upgradeGroups: MutableMap<String, UpgradeGroupDefinition> = LinkedHashMap()
    val shopProducts: MutableMap<String, ShopProductDefinition> = LinkedHashMap()
    val rarityDisplays: MutableMap<String, String> = LinkedHashMap()
    val rarityUpgradeGroups: MutableMap<String, String> = LinkedHashMap()
    var settings: PluginSettings = PluginSettings(20, true, "newbie")
        private set
    lateinit var warehouseLayout: GuiLayout
        private set
    lateinit var upgradeLayout: GuiLayout
        private set
    lateinit var detachLayout: GuiLayout
        private set
    lateinit var scrollSelectLayout: GuiLayout
        private set
    lateinit var shopLayout: GuiLayout
        private set
    lateinit var customTitleCurrencyLayout: GuiLayout
        private set
    lateinit var customTitleColorLayout: GuiLayout
        private set
    lateinit var customTitleGroupLayout: GuiLayout
        private set
    lateinit var storage: StorageSettings
        private set
    lateinit var sync: SyncSettings
        private set
    lateinit var detach: DetachSettings
        private set

    lateinit var customTitleSettings: CustomTitleSettings
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
        saveDefaultIfAbsent("shop.yml")
        saveDefaultIfAbsent("custom-title.yml")
        saveDefaultIfAbsent("gui/warehouse.yml")
        saveDefaultIfAbsent("gui/upgrade.yml")
        saveDefaultIfAbsent("gui/detach.yml")
        saveDefaultIfAbsent("gui/scroll-select.yml")
        saveDefaultIfAbsent("gui/shop.yml")
        saveDefaultIfAbsent("gui/custom-title-currency.yml")
        saveDefaultIfAbsent("gui/custom-title-color.yml")
        saveDefaultIfAbsent("gui/custom-title-group.yml")
        loadSettings()
        loadTags()
        loadUpgrades()
        loadShop()
        loadCustomTitleSettings()
        warehouseLayout = loadGuiLayout("gui/warehouse.yml")
        upgradeLayout = loadGuiLayout("gui/upgrade.yml")
        detachLayout = loadGuiLayout("gui/detach.yml")
        scrollSelectLayout = loadGuiLayout("gui/scroll-select.yml")
        shopLayout = loadGuiLayout("gui/shop.yml")
        customTitleCurrencyLayout = loadGuiLayout("gui/custom-title-currency.yml")
        customTitleColorLayout = loadGuiLayout("gui/custom-title-color.yml")
        customTitleGroupLayout = loadGuiLayout("gui/custom-title-group.yml")
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
            definition.shop?.let { shop ->
                yaml.set("$path.shop.enabled", shop.enabled)
                yaml.set("$path.shop.permission", shop.permission)
                yaml.set("$path.shop.conditions", shop.conditions)
                yaml.set("$path.shop.cost.type", shop.cost.type.name)
                yaml.set("$path.shop.cost.amount", shop.cost.amount)
                if (shop.cost.levelAmounts.isNotEmpty()) {
                    shop.cost.levelAmounts.forEach { (level, amount) ->
                        yaml.set("$path.shop.cost.levels.$level", amount)
                    }
                }
                yaml.set("$path.shop.cost.conditions", shop.cost.conditions)
                shop.submitItems.forEachIndexed { index, item ->
                    yaml.set("$path.shop.submit-items.$index.material", item.material)
                    yaml.set("$path.shop.submit-items.$index.amount", item.amount)
                    yaml.set("$path.shop.submit-items.$index.name", item.name)
                }
                shop.icon?.let { icon ->
                    yaml.set("$path.shop.icon.material", icon.material)
                    yaml.set("$path.shop.icon.name", icon.name)
                    yaml.set("$path.shop.icon.lore", icon.lore)
                }
            }
        }
        try {
            yaml.save(file)
        } catch (ex: IOException) {
            plugin.logger.warning("Failed to save tags.yml: ${ex.message}")
        }
    }

    fun createTag(id: String): TagDefinition {
        val normalized = id.trim()
        val definition = TagDefinition(
            id = normalized,
            display = "&#FFFFFF[&#AAAAAA$normalized&#FFFFFF]",
            description = listOf("&7\u65b0\u7684\u79f0\u53f7"),
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
            mode = StorageMode.from(yaml.getString("storage.mode", "yml")),
            yml = YamlStorageSettings(
                file = yaml.getString("storage.yml.file", "data/playerdata") ?: "data/playerdata",
            ),
            mysql = MysqlSettings(
                jdbcUrl = yaml.getString("storage.mysql.jdbc-url", "jdbc:mysql://127.0.0.1:3306/minecraft?useSSL=false&characterEncoding=utf8") ?: "",
                username = yaml.getString("storage.mysql.username", "root") ?: "root",
                password = yaml.getString("storage.mysql.password", "") ?: "",
                table = yaml.getString("storage.mysql.table", "uptags_player_data") ?: "uptags_player_data",
            ),
        )
        detach = DetachSettings(
            enabled = yaml.getBoolean("detach.enabled", true),
            buff = DetachCostSettings(
                money = yaml.getDouble("detach.buff.money.amount", 100.0).coerceAtLeast(0.0),
                points = yaml.getDouble("detach.buff.points.amount", 100.0).coerceAtLeast(0.0),
            ),
            particle = DetachCostSettings(
                money = yaml.getDouble("detach.particle.money.amount", 100.0).coerceAtLeast(0.0),
                points = yaml.getDouble("detach.particle.points.amount", 100.0).coerceAtLeast(0.0),
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
        var skippedLegacyCustomTags = false
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
            if (tagId.startsWith(legacyCustomTagPrefix, ignoreCase = true)) {
                skippedLegacyCustomTags = true
                return@forEach
            }
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
                shop = parseTagShop(section.getConfigurationSection("shop")),
            )
        }
        if (skippedLegacyCustomTags) {
            saveTags()
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
                plugin.logger.warning("Unknown buff type: $buffId")
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

    private fun loadShop() {
        shopProducts.clear()
        tags.values.forEach { tag ->
            val shop = tag.shop ?: return@forEach
            val defaultIcon = ItemTemplate(
                material = "NAME_TAG",
                name = tag.display,
                lore = buildList {
                    addAll(tag.description)
                    add(rarityDisplay(tag.rarity))
                },
            )
            val icon = shop.icon?.let {
                ItemTemplate(
                    material = it.material,
                    name = it.name.takeIf(String::isNotBlank) ?: defaultIcon.name,
                    lore = it.lore.ifEmpty { defaultIcon.lore },
                )
            } ?: defaultIcon
            shopProducts[tag.id] = ShopProductDefinition(
                id = tag.id,
                type = ShopProductType.TAG,
                targetId = tag.id,
                enabled = shop.enabled,
                permission = shop.permission,
                conditions = shop.conditions,
                cost = shop.cost,
                submitItems = shop.submitItems,
                icon = icon,
            )
        }
        val yaml = YamlConfiguration.loadConfiguration(File(plugin.dataFolder, "shop.yml"))
        yaml.getConfigurationSection("products")?.getKeys(false)?.forEach { productId ->
            val section = yaml.getConfigurationSection("products.$productId") ?: return@forEach
            val iconSection = section.getConfigurationSection("icon")
            val targetId = section.getString("target-id", productId) ?: productId
            val tag = tags[targetId]
            shopProducts[productId] = ShopProductDefinition(
                id = productId,
                type = ShopProductType.from(section.getString("type")),
                targetId = targetId,
                enabled = section.getBoolean("enabled", true),
                permission = section.getString("permission")?.takeIf { it.isNotBlank() },
                conditions = section.getStringList("conditions"),
                cost = parseCost(section.getConfigurationSection("cost")),
                submitItems = parseSubmitItems(section.getConfigurationSection("submit-items")),
                icon = ItemTemplate(
                    material = iconSection?.getString("material", "NAME_TAG") ?: "NAME_TAG",
                    name = iconSection?.getString("name", tag?.display ?: productId) ?: tag?.display ?: productId,
                    lore = iconSection?.getStringList("lore") ?: tag?.description.orEmpty(),
                ),
            )
        }
    }

    private fun parseTagShop(section: ConfigurationSection?): TagShopDefinition? {
        if (section == null) {
            return null
        }
        val iconSection = section.getConfigurationSection("icon")
        return TagShopDefinition(
            enabled = section.getBoolean("enabled", true),
            permission = section.getString("permission")?.takeIf { it.isNotBlank() },
            conditions = section.getStringList("conditions"),
            cost = parseCost(section.getConfigurationSection("cost")),
            submitItems = parseSubmitItems(section.getConfigurationSection("submit-items")),
            icon = iconSection?.let {
                ItemTemplate(
                    material = it.getString("material", "NAME_TAG") ?: "NAME_TAG",
                    name = it.getString("name", "") ?: "",
                    lore = it.getStringList("lore"),
                )
            },
        )
    }

    private fun parseSubmitItems(section: ConfigurationSection?): List<SubmitItemDefinition> {
        if (section == null) {
            return emptyList()
        }
        return section.getKeys(false).mapNotNull { key ->
            val itemSection = section.getConfigurationSection(key) ?: return@mapNotNull null
            val material = itemSection.getString("material")?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
            SubmitItemDefinition(
                material = material,
                amount = itemSection.getInt("amount", 1).coerceAtLeast(1),
                name = itemSection.getString("name")?.takeIf { it.isNotBlank() },
            )
        }
    }

    private fun loadCustomTitleSettings() {
        val yaml = YamlConfiguration.loadConfiguration(File(plugin.dataFolder, "custom-title.yml"))
        val presets = LinkedHashMap<String, CustomTitlePreset>()
        yaml.getConfigurationSection("presets")?.getKeys(false)?.forEach { presetId ->
            val section = yaml.getConfigurationSection("presets.$presetId") ?: return@forEach
            val palettes = readPaletteLibraries(section).ifEmpty { readPaletteGroups(section, "palettes") }
            val randomColorPool = section.getStringList("random-color-pool")
                .mapNotNull(Support::normalizeHex)
            val allowedPattern = validatedAllowedPattern(
                presetId = presetId,
                pattern = section.getString("allowed-pattern")?.takeIf { it.isNotBlank() },
            )
            presets[presetId] = CustomTitlePreset(
                id = presetId,
                minLength = section.getInt("min-length", 2).coerceAtLeast(1),
                maxLength = section.getInt("max-length", 12).coerceAtLeast(1),
                maxSchemes = section.getInt("random-schemes", 4).coerceAtLeast(1),
                colorsPerScheme = section.getInt("colors-per-scheme", 2).coerceAtLeast(1),
                allowManualColors = section.getBoolean("allow-manual-colors", true),
                allowSpaces = section.getBoolean("allow-spaces", true),
                allowedPattern = allowedPattern,
                blockedWords = section.getStringList("blocked-words").map { UnicodeText.riskText(it) }.toSet(),
                blockedPatterns = section.getStringList("blocked-patterns"),
                palettes = palettes,
                randomColorPool = randomColorPool,
                previewTemplate = section.getString("preview-template", "%title%") ?: "%title%",
                equipAfterConfirm = section.getBoolean("equip-after-confirm", true),
            )
        }
        customTitleSettings = CustomTitleSettings(
            defaultTitleCoinBalance = yaml.getDouble("settings.default-title-coin-balance", 0.0),
            sessionTimeoutSeconds = yaml.getLong("settings.session-timeout-seconds", 120L).coerceAtLeast(15L),
            currencyCosts = linkedMapOf(
                CurrencyType.MONEY to yaml.getDouble("settings.costs.money", 888888.0),
                CurrencyType.POINTS to yaml.getDouble("settings.costs.points", 35.0),
                CurrencyType.TITLE_COIN to yaml.getDouble("settings.costs.title-coin", 100.0),
            ),
            presets = presets,
        )
    }

    private fun validatedAllowedPattern(presetId: String, pattern: String?): String? {
        if (pattern.isNullOrBlank()) {
            return null
        }
        return runCatching {
            Regex(pattern)
            pattern
        }.getOrElse { ex ->
            plugin.logger.warning(
                "Invalid custom-title allowed-pattern for preset '$presetId': $pattern; pattern disabled. Cause: ${ex.message}",
            )
            null
        }
    }

    private fun readPaletteGroups(section: ConfigurationSection, path: String): List<List<String>> {
        return section.getList(path)
            .orEmpty()
            .mapNotNull { entry ->
                when (entry) {
                    is String -> entry.split(',').map(String::trim).filter(String::isNotBlank)
                    is List<*> -> entry.mapNotNull { it?.toString()?.trim() }.filter(String::isNotBlank)
                    else -> emptyList()
                }
                    .mapNotNull(Support::normalizeHex)
                    .ifEmpty { null }
            }
    }

    private fun readPaletteLibraries(section: ConfigurationSection): List<List<String>> {
        val libraries = section.getConfigurationSection("palette-libraries") ?: return emptyList()
        val order = listOf(
            "single" to 1,
            "double" to 2,
            "triple" to 3,
            "quad" to 4,
        )
        return buildList {
            order.forEach { (key, expectedSize) ->
                addAll(readPaletteLibraryGroup(libraries, key, expectedSize))
            }
        }
    }

    private fun readPaletteLibraryGroup(
        libraries: ConfigurationSection,
        key: String,
        expectedSize: Int,
    ): List<List<String>> {
        return libraries.getList(key)
            .orEmpty()
            .mapNotNull { entry ->
                when (entry) {
                    is String -> entry.split(',').map(String::trim).filter(String::isNotBlank)
                    is List<*> -> entry.mapNotNull { it?.toString()?.trim() }.filter(String::isNotBlank)
                    else -> emptyList()
                }
                    .mapNotNull(Support::normalizeHex)
                    .takeIf { it.size == expectedSize }
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
