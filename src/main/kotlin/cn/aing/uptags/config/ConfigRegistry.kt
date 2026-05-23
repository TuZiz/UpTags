package cn.aing.uptags.config

import cn.aing.uptags.Support
import cn.aing.uptags.config.shop.ShopProductParser
import cn.aing.uptags.config.shop.TagProductResolver
import cn.aing.uptags.model.config.BuffDefinition
import cn.aing.uptags.model.config.ConfigIssue
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
import cn.aing.uptags.model.config.ShopCategoryTextDefinition
import cn.aing.uptags.model.config.ShopProductDefinition
import cn.aing.uptags.model.config.ShopProductMode
import cn.aing.uptags.model.config.ShopProductType
import cn.aing.uptags.model.config.SubmitItemDefinition
import cn.aing.uptags.model.config.TagDefinition
import cn.aing.uptags.model.config.TagShopDefinition
import cn.aing.uptags.model.config.TitleCollectionCategoryDefinition
import cn.aing.uptags.model.config.TitleCollectionSettings
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
import java.nio.file.Files
import java.io.InputStreamReader
import java.util.LinkedHashMap
import java.util.LinkedHashSet
import java.util.Locale

class ConfigRegistry(private val plugin: JavaPlugin) {
    private val legacyCustomTagPrefix = "custom-"
    val tags: MutableMap<String, TagDefinition> = LinkedHashMap()
    val buffs: MutableMap<String, BuffDefinition> = LinkedHashMap()
    val particles: MutableMap<String, ParticleDefinition> = LinkedHashMap()
    val scrolls: MutableMap<String, ScrollDefinition> = LinkedHashMap()
    val upgradeGroups: MutableMap<String, UpgradeGroupDefinition> = LinkedHashMap()
    val shopProducts: MutableMap<String, ShopProductDefinition> = LinkedHashMap()
    private val explicitShopProductIds: MutableSet<String> = LinkedHashSet()
    private val configurationIssues: MutableList<ConfigIssue> = ArrayList()
    val rarityDisplays: MutableMap<String, String> = LinkedHashMap()
    val rarityUpgradeGroups: MutableMap<String, String> = LinkedHashMap()
    private val displayNames: MutableMap<String, String> = LinkedHashMap()
    private val shopTexts: MutableMap<String, String> = LinkedHashMap()
    private val shopCategories: MutableMap<String, ShopCategoryTextDefinition> = LinkedHashMap()
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
    lateinit var collectionLayout: GuiLayout
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
    var collection: TitleCollectionSettings = TitleCollectionSettings()
        private set

    lateinit var customTitleSettings: CustomTitleSettings
        private set
    var defaultTagRarity: String = "COMMON"
        private set
    var defaultTagUnlocked: Boolean = false
        private set

    fun load() {
        configurationIssues.clear()
        explicitShopProductIds.clear()
        saveDefaultIfAbsent("config.yml")
        saveDefaultIfAbsent(DEFAULT_LANGUAGE_PATH)
        saveDefaultIfAbsent(DEFAULT_NAMES_PATH)
        saveDefaultIfAbsent("tags.yml")
        saveDefaultIfAbsent("upgrades.yml")
        saveDefaultIfAbsent("shop.yml")
        saveDefaultIfAbsent("custom-title.yml")
        saveDefaultIfAbsent("gui/warehouse.yml")
        saveDefaultIfAbsent("gui/upgrade.yml")
        saveDefaultIfAbsent("gui/detach.yml")
        saveDefaultIfAbsent("gui/scroll-select.yml")
        saveDefaultIfAbsent("gui/shop.yml")
        saveDefaultIfAbsent("gui/collection.yml")
        saveDefaultIfAbsent("gui/custom-title-currency.yml")
        saveDefaultIfAbsent("gui/custom-title-color.yml")
        saveDefaultIfAbsent("gui/custom-title-group.yml")
        loadSettings()
        loadLocalization()
        loadCollectionSettings()
        loadTags()
        migrateLegacyTagShopProducts()
        loadUpgrades()
        loadShop()
        loadCustomTitleSettings()
        warehouseLayout = loadGuiLayout("gui/warehouse.yml")
        upgradeLayout = loadGuiLayout("gui/upgrade.yml")
        detachLayout = loadGuiLayout("gui/detach.yml")
        scrollSelectLayout = loadGuiLayout("gui/scroll-select.yml")
        shopLayout = loadGuiLayout("gui/shop.yml")
        collectionLayout = loadGuiLayout("gui/collection.yml")
        customTitleCurrencyLayout = loadGuiLayout("gui/custom-title-currency.yml")
        customTitleColorLayout = loadGuiLayout("gui/custom-title-color.yml")
        customTitleGroupLayout = loadGuiLayout("gui/custom-title-group.yml")
    }

    fun saveTags(): Boolean {
        val file = File(plugin.dataFolder, "tags.yml")
        val yaml = YamlConfiguration()
        yaml.options().header("UpTags title catalog. Keep tags under tags.<id>; shop listing can live in shop.yml.")
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
            yaml.set("$path.hidden", definition.hidden)
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
            return true
        } catch (ex: IOException) {
            plugin.logger.warning("Failed to save tags.yml: ${ex.message}")
            return false
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

    fun displayName(raw: String): String {
        val normalized = displayLookupKey(raw)
        return displayNames[normalized] ?: raw.trim()
    }

    fun shopText(key: String): String = shopTexts[key] ?: key

    fun shopText(key: String, placeholders: Map<String, String>): String =
        Support.apply(shopText(key), placeholders)

    fun shopCategory(id: String): ShopCategoryTextDefinition {
        val normalized = id.trim().lowercase(Locale.ROOT)
        return shopCategories[normalized] ?: ShopCategoryTextDefinition(normalized, normalized, normalized)
    }

    fun hasShopCategory(id: String): Boolean = shopCategories.containsKey(id.trim().lowercase(Locale.ROOT))

    fun configurationIssues(): List<ConfigIssue> = configurationIssues.toList()

    fun createShopProductForTag(tagId: String): Boolean {
        val product = TagProductResolver(tags, ::rarityDisplay).defaultProductForTag(tagId) ?: return false
        shopProducts[product.id] = product
        explicitShopProductIds += product.id
        return saveShop()
    }

    fun applyTagAndProductAtomic(tag: TagDefinition, product: ShopProductDefinition?): Boolean {
        val tagsFile = File(plugin.dataFolder, "tags.yml")
        val shopFile = File(plugin.dataFolder, "shop.yml")
        val previousTag = tags[tag.id]
        val previousProduct = product?.let { shopProducts[it.id] }
        val previousExplicit = product?.let { it.id in explicitShopProductIds } ?: false
        val tagsBackup = tagsFile.readBytesIfExists()
        val shopBackup = shopFile.readBytesIfExists()

        tags[tag.id] = tag
        if (product != null) {
            shopProducts[product.id] = product
            explicitShopProductIds += product.id
        }

        val saved = saveTags() && saveShop()
        if (saved) {
            return true
        }

        if (previousTag == null) {
            tags.remove(tag.id)
        } else {
            tags[tag.id] = previousTag
        }
        if (product != null) {
            if (previousProduct == null) {
                shopProducts.remove(product.id)
            } else {
                shopProducts[product.id] = previousProduct
            }
            if (!previousExplicit) {
                explicitShopProductIds.remove(product.id)
            }
        }
        tagsFile.restoreBytes(tagsBackup)
        shopFile.restoreBytes(shopBackup)
        return false
    }

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
        settings = PluginSettings(
            effectTickInterval = interval,
            forceDefaultTag = enabled,
            forcedTagId = tagId,
            particleFrequencyTicks = yaml.getLong("settings.particles.frequency-ticks", interval).coerceAtLeast(1L),
            particleCountMultiplier = yaml.getInt("settings.particles.count-multiplier", 1).coerceAtLeast(1),
            particleViewDistance = yaml.getDouble("settings.particles.view-distance", 32.0).coerceAtLeast(1.0),
            disabledBuffWorlds = yaml.getStringList("settings.buffs.disabled-worlds").map { it.lowercase() }.toSet(),
            disabledBuffPermission = yaml.getString("settings.buffs.disabled-permission")?.takeIf { it.isNotBlank() },
            disableBuffsInPvp = yaml.getBoolean("settings.buffs.disable-in-pvp", false),
        )
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

    private fun loadLocalization() {
        displayNames.clear()
        shopTexts.clear()
        shopCategories.clear()
        loadDisplayNamesYaml(resourceYaml(DEFAULT_NAMES_PATH))
        loadShopLocalizationYaml(resourceYaml(DEFAULT_LANGUAGE_PATH))
        val legacyDisplayNames = File(plugin.dataFolder, LEGACY_DISPLAY_NAMES_PATH)
        if (legacyDisplayNames.exists()) {
            loadDisplayNamesYaml(YamlConfiguration.loadConfiguration(legacyDisplayNames))
            loadShopLocalizationYaml(YamlConfiguration.loadConfiguration(legacyDisplayNames))
        }
        val namesFile = File(plugin.dataFolder, DEFAULT_NAMES_PATH)
        if (namesFile.exists()) {
            loadDisplayNamesYaml(YamlConfiguration.loadConfiguration(namesFile))
        }
        val languageFile = File(plugin.dataFolder, DEFAULT_LANGUAGE_PATH)
        if (languageFile.exists()) {
            loadShopLocalizationYaml(YamlConfiguration.loadConfiguration(languageFile))
        }
    }

    private fun resourceYaml(path: String): YamlConfiguration? {
        val stream = plugin.getResource(path) ?: return null
        return runCatching {
            InputStreamReader(stream, Charsets.UTF_8).use { reader ->
                YamlConfiguration.loadConfiguration(reader)
            }
        }.getOrNull()
    }

    private fun loadDisplayNamesYaml(yaml: YamlConfiguration?) {
        if (yaml == null) {
            return
        }
        yaml.getConfigurationSection("names")?.getKeys(false)?.forEach { key ->
            val value = yaml.getString("names.$key")?.takeIf { it.isNotBlank() } ?: return@forEach
            displayNames[displayLookupKey(key)] = value
        }
    }

    private fun loadShopLocalizationYaml(yaml: YamlConfiguration?) {
        if (yaml == null) {
            return
        }
        yaml.getConfigurationSection("shop.text")?.let { collectShopTexts(it, "") }
        yaml.getConfigurationSection("shop.categories")?.getKeys(false)?.forEach { key ->
            val section = yaml.getConfigurationSection("shop.categories.$key") ?: return@forEach
            val id = key.trim().lowercase(Locale.ROOT)
            shopCategories[id] = ShopCategoryTextDefinition(
                id = id,
                display = section.getString("display", id) ?: id,
                hint = section.getString("hint", id) ?: id,
            )
        }
    }

    private fun displayLookupKey(raw: String): String =
        raw.trim().replace('-', '_').replace(' ', '_').uppercase(Locale.ROOT)

    private fun collectShopTexts(section: ConfigurationSection, prefix: String) {
        section.getKeys(false).forEach { key ->
            val path = if (prefix.isBlank()) key else "$prefix.$key"
            val nested = section.getConfigurationSection(key)
            if (nested != null) {
                collectShopTexts(nested, path)
            } else {
                val value = section.getString(key) ?: return@forEach
                val normalized = value.replace("\\n", "\n")
                shopTexts[path] = normalized
                shopTexts["shop.$path"] = normalized
            }
        }
    }

    private companion object {
        const val DEFAULT_LANGUAGE_PATH = "lang/zh_cn.yml"
        const val DEFAULT_NAMES_PATH = "names.yml"
        const val LEGACY_DISPLAY_NAMES_PATH = "display-names.yml"
    }

    private fun loadCollectionSettings() {
        val yaml = YamlConfiguration.loadConfiguration(File(plugin.dataFolder, "config.yml"))
        val root = yaml.getConfigurationSection("collection.categories")
            ?: yaml.getConfigurationSection("codex.categories")
        val categories = ArrayList<TitleCollectionCategoryDefinition>()
        root?.getKeys(false)?.forEach { categoryId ->
            val section = root.getConfigurationSection(categoryId) ?: return@forEach
            categories += TitleCollectionCategoryDefinition(
                id = categoryId,
                display = section.getString("display", categoryId) ?: categoryId,
                material = section.getString("material", "BOOK") ?: "BOOK",
                completedMaterial = section.getString("completed-material")?.takeIf { it.isNotBlank() },
                description = section.getStringList("description"),
                productCategories = section.getStringList("product-categories")
                    .map { it.trim().lowercase() }
                    .filter(String::isNotBlank)
                    .toSet(),
                modes = section.getStringList("modes")
                    .map(ShopProductMode::from)
                    .toSet(),
                tagIds = section.getStringList("tags").map(String::trim).filter(String::isNotBlank).toSet(),
                rewardTagId = section.getString("reward-tag")?.takeIf { it.isNotBlank() },
            )
        }
        collection = TitleCollectionSettings(categories)
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
                hidden = section.getBoolean("hidden", false),
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
        explicitShopProductIds.clear()
        val resolver = TagProductResolver(tags, ::rarityDisplay)
        tags.values.forEach { tag ->
            val shop = tag.shop ?: return@forEach
            shopProducts[tag.id] = resolver.productFromLegacyTagShop(tag, shop)
        }
        val yaml = YamlConfiguration.loadConfiguration(File(plugin.dataFolder, "shop.yml"))
        val parsed = ShopProductParser(
            resolver = resolver,
            tagExists = tags::containsKey,
            categoryExists = ::hasShopCategory,
            issueSink = ::recordConfigIssue,
        ).parse(yaml)
        shopProducts.putAll(parsed.products)
        explicitShopProductIds += parsed.explicitProductIds
    }

    fun saveShop(): Boolean {
        val file = File(plugin.dataFolder, "shop.yml")
        val yaml = YamlConfiguration()
        yaml.options().header("UpTags shop catalog. Omitted fields use defaults from tags.yml where possible.")
        val resolver = TagProductResolver(tags, ::rarityDisplay)
        explicitShopProductIds.forEach { productId ->
            val product = shopProducts[productId] ?: return@forEach
            val path = "products.${product.id}"
            yaml.createSection(path)
            if (product.type != ShopProductType.TAG) {
                yaml.set("$path.type", product.type.name)
            }
            if (product.targetId != product.id) {
                yaml.set("$path.target-id", product.targetId)
            }
            if (product.mode != ShopProductMode.BUY) {
                yaml.set("$path.mode", product.mode.name)
            }
            product.category?.let { yaml.set("$path.category", it) }
            if (!product.enabled) {
                yaml.set("$path.enabled", false)
            }
            product.permission?.let { yaml.set("$path.permission", it) }
            if (product.conditions.isNotEmpty()) {
                yaml.set("$path.conditions", product.conditions)
            }
            writeCost(yaml, path, product.cost)
            writeSubmitItems(yaml, path, product.submitItems)
            writeIcon(yaml, path, product, resolver)
        }
        return try {
            yaml.save(file)
            true
        } catch (ex: IOException) {
            plugin.logger.warning("Failed to save shop.yml: ${ex.message}")
            false
        }
    }

    private fun writeCost(yaml: YamlConfiguration, path: String, cost: CostDefinition) {
        val defaultCost = CostDefinition()
        if (cost == defaultCost) {
            return
        }
        if (cost.levelAmounts.isEmpty() && cost.conditions.isEmpty()) {
            yaml.set("$path.price", "${cost.type.name}:${Support.formatDouble(cost.amount)}")
            return
        }
        yaml.set("$path.cost.type", cost.type.name)
        yaml.set("$path.cost.amount", cost.amount)
        cost.levelAmounts.forEach { (level, amount) -> yaml.set("$path.cost.levels.$level", amount) }
        if (cost.conditions.isNotEmpty()) {
            yaml.set("$path.cost.conditions", cost.conditions)
        }
    }

    private fun writeSubmitItems(yaml: YamlConfiguration, path: String, items: List<SubmitItemDefinition>) {
        items.forEachIndexed { index, item ->
            val key = item.material.uppercase(Locale.ROOT).replace(Regex("[^A-Z0-9_]+"), "_").ifBlank { "ITEM" }
            val itemPath = "$path.submit-items.$key"
            if (item.name == null && items.count { other -> other.material.equals(item.material, ignoreCase = true) } == 1) {
                yaml.set(itemPath, item.amount)
            } else {
                yaml.set("$itemPath-$index.material", item.material)
                yaml.set("$itemPath-$index.amount", item.amount)
                yaml.set("$itemPath-$index.name", item.name)
            }
        }
    }

    private fun writeIcon(
        yaml: YamlConfiguration,
        path: String,
        product: ShopProductDefinition,
        resolver: TagProductResolver,
    ) {
        val defaultIcon = resolver.defaultIcon(product.targetId, product.id)
        if (product.icon == defaultIcon) {
            return
        }
        if (product.icon.name == defaultIcon.name && product.icon.lore == defaultIcon.lore) {
            yaml.set("$path.icon", product.icon.material)
            return
        }
        yaml.set("$path.icon.material", product.icon.material)
        yaml.set("$path.icon.name", product.icon.name)
        yaml.set("$path.icon.lore", product.icon.lore)
    }

    private fun migrateLegacyTagShopProducts() {
        val legacyTags = tags.values.filter { it.shop != null }
        if (legacyTags.isEmpty()) {
            return
        }
        val shopFile = File(plugin.dataFolder, "shop.yml")
        val shopYaml = YamlConfiguration.loadConfiguration(shopFile)
        var migrated = 0
        legacyTags.forEach { tag ->
            val shop = tag.shop ?: return@forEach
            val productPath = "products.${tag.id}"
            if (!shopYaml.isConfigurationSection(productPath)) {
                writeLegacyTagShopProduct(shopYaml, productPath, tag, shop)
                migrated++
            }
            tag.shop = null
        }
        if (migrated > 0) {
            try {
                shopYaml.save(shopFile)
            } catch (ex: IOException) {
                plugin.logger.warning("Failed to migrate tag shop sections into shop.yml: ${ex.message}")
                return
            }
        }
        saveTags()
        plugin.logger.info("Migrated ${legacyTags.size} legacy tags.yml shop section(s) into shop.yml.")
    }

    private fun writeLegacyTagShopProduct(
        yaml: YamlConfiguration,
        path: String,
        tag: TagDefinition,
        shop: TagShopDefinition,
    ) {
        yaml.set("$path.type", ShopProductType.TAG.name)
        yaml.set("$path.target-id", tag.id)
        yaml.set("$path.mode", legacyShopMode(shop).name)
        yaml.set("$path.enabled", shop.enabled)
        yaml.set("$path.permission", shop.permission)
        yaml.set("$path.conditions", shop.conditions)
        yaml.set("$path.cost.type", shop.cost.type.name)
        yaml.set("$path.cost.amount", shop.cost.amount)
        if (shop.cost.levelAmounts.isNotEmpty()) {
            shop.cost.levelAmounts.forEach { (level, amount) ->
                yaml.set("$path.cost.levels.$level", amount)
            }
        }
        yaml.set("$path.cost.conditions", shop.cost.conditions)
        shop.submitItems.forEachIndexed { index, item ->
            val itemKey = item.material.lowercase().replace(Regex("[^a-z0-9_]+"), "_").ifBlank { "item" }
            val itemPath = "$path.submit-items.${itemKey}_$index"
            yaml.set("$itemPath.material", item.material)
            yaml.set("$itemPath.amount", item.amount)
            yaml.set("$itemPath.name", item.name)
        }
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
        yaml.set("$path.icon.material", icon.material)
        yaml.set("$path.icon.name", icon.name)
        yaml.set("$path.icon.lore", icon.lore)
    }

    private fun legacyShopMode(shop: TagShopDefinition): ShopProductMode {
        if (shop.submitItems.isNotEmpty()) {
            return ShopProductMode.ITEM_EXCHANGE
        }
        if (shop.conditions.isNotEmpty()) {
            return ShopProductMode.CHALLENGE_CLAIM
        }
        return ShopProductMode.BUY
    }

    private fun recordConfigIssue(issue: ConfigIssue) {
        configurationIssues += issue
        plugin.logger.warning("[${issue.source}] ${issue.path}: ${issue.message}")
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

private fun File.readBytesIfExists(): ByteArray? =
    if (exists()) Files.readAllBytes(toPath()) else null

private fun File.restoreBytes(bytes: ByteArray?) {
    if (bytes == null) {
        if (exists()) {
            delete()
        }
        return
    }
    parentFile?.mkdirs()
    Files.write(toPath(), bytes)
}
