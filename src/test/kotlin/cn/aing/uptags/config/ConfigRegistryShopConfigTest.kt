package cn.aing.uptags.config

import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import org.bukkit.configuration.file.YamlConfiguration
import org.bukkit.plugin.java.JavaPlugin
import io.mockk.every
import io.mockk.mockk
import java.io.File

class ConfigRegistryShopConfigTest {
    @Test
    fun loadMigratesLegacyTagShopSectionsIntoShopProductsFile() {
        val pluginDir = createTempDirectory("uptags-config-shop").toFile()
        val plugin = mockPlugin(pluginDir)
        writeMinimalConfig(pluginDir)
        File(pluginDir, "tags.yml").writeText(
            """
            rarity-display:
              COMMON: '&#E2E8F0[&#7DD3FC普通&#E2E8F0]'
            rarity-upgrade-group:
              COMMON: COMMON
            tag-template:
              rarity: COMMON
              default-unlocked: false
            tags:
              miner_soul:
                display: '&#E2E8F0[&#A3A3A3矿洞住民&#E2E8F0]'
                description:
                  - '&#94A3B8常年出没于地下矿道的探索者'
                rarity: COMMON
                default-unlocked: false
                upgrade-groups:
                  - COMMON
                shop:
                  enabled: true
                  submit-items:
                    redstone:
                      material: REDSTONE
                      amount: 64
                  icon:
                    material: DIAMOND
            """.trimIndent(),
            Charsets.UTF_8,
        )
        File(pluginDir, "shop.yml").writeText("products:\n", Charsets.UTF_8)

        val registry = ConfigRegistry(plugin)
        registry.load()

        val product = assertNotNull(registry.shopProducts["miner_soul"])
        assertEquals("miner_soul", product.targetId)
        assertEquals(0.0, product.cost.amount)
        assertEquals("REDSTONE", product.submitItems.single().material)
        assertEquals(64, product.submitItems.single().amount)
        assertEquals("DIAMOND", product.icon.material)
        assertEquals("&#E2E8F0[&#A3A3A3矿洞住民&#E2E8F0]", product.icon.name)
        assertTrue(product.icon.lore.any { it.contains("普通") })

        val tagsYaml = YamlConfiguration.loadConfiguration(File(pluginDir, "tags.yml"))
        assertFalse(tagsYaml.isConfigurationSection("tags.miner_soul.shop"))
        val shopYaml = YamlConfiguration.loadConfiguration(File(pluginDir, "shop.yml"))
        assertTrue(shopYaml.isConfigurationSection("products.miner_soul"))
        assertEquals("ITEM_EXCHANGE", shopYaml.getString("products.miner_soul.mode"))
    }

    @Test
    fun shopProductsFileOverridesGeneratedTagShopProduct() {
        val pluginDir = createTempDirectory("uptags-config-shop-override").toFile()
        val plugin = mockPlugin(pluginDir)
        writeMinimalConfig(pluginDir)
        File(pluginDir, "tags.yml").writeText(
            """
            rarity-display:
              COMMON: '&#E2E8F0[&#7DD3FC普通&#E2E8F0]'
            rarity-upgrade-group:
              COMMON: COMMON
            tag-template:
              rarity: COMMON
              default-unlocked: false
            tags:
              miner_soul:
                display: '&#E2E8F0[&#A3A3A3矿洞住民&#E2E8F0]'
                description:
                  - '&#94A3B8常年出没于地下矿道的探索者'
                rarity: COMMON
                shop:
                  cost:
                    type: POINTS
                    amount: 800
            """.trimIndent(),
            Charsets.UTF_8,
        )
        File(pluginDir, "shop.yml").writeText(
            """
            products:
              miner_soul:
                target-id: miner_soul
                category: challenge
                cost:
                  type: POINTS
                  amount: 1500
                icon:
                  material: DIAMOND
                  name: '&#FFFFFF覆盖商品名'
                  lore:
                    - '&#94A3B8覆盖描述'
            """.trimIndent(),
            Charsets.UTF_8,
        )

        val registry = ConfigRegistry(plugin)
        registry.load()

        val product = assertNotNull(registry.shopProducts["miner_soul"])
        assertEquals(1500.0, product.cost.amount)
        assertEquals("challenge", product.category)
        assertEquals("DIAMOND", product.icon.material)
        assertEquals("&#FFFFFF覆盖商品名", product.icon.name)

        val tagsYaml = YamlConfiguration.loadConfiguration(File(pluginDir, "tags.yml"))
        assertFalse(tagsYaml.isConfigurationSection("tags.miner_soul.shop"))
    }

    private fun mockPlugin(pluginDir: File): JavaPlugin {
        val plugin = mockk<JavaPlugin>(relaxed = true)
        every { plugin.dataFolder } returns pluginDir
        return plugin
    }

    private fun writeMinimalConfig(pluginDir: File) {
        File(pluginDir, "config.yml").writeText(
            """
            settings:
              effect-tick-interval: 20
              force-default-tag:
                enabled: false
                tag-id: newbie
            storage:
              mode: yml
            detach:
              enabled: true
            sync:
              server-id: test
              redis:
                enabled: false
            """.trimIndent(),
            Charsets.UTF_8,
        )
        File(pluginDir, "upgrades.yml").writeText(
            """
            buffs: {}
            particles: {}
            scrolls: {}
            groups:
              COMMON:
                name: 普通组
                display: COMMON
                buffs: []
                particles: []
            """.trimIndent(),
            Charsets.UTF_8,
        )
        File(pluginDir, "custom-title.yml").writeText(
            """
            settings:
              default-title-coin-balance: 0
              session-timeout-seconds: 120
              costs:
                money: 0
                points: 0
                title-coin: 0
            presets: {}
            """.trimIndent(),
            Charsets.UTF_8,
        )
        listOf(
            "messages.yml",
            "gui/warehouse.yml",
            "gui/upgrade.yml",
            "gui/detach.yml",
            "gui/scroll-select.yml",
            "gui/shop.yml",
            "gui/custom-title-currency.yml",
            "gui/custom-title-color.yml",
            "gui/custom-title-group.yml",
        ).forEach { path ->
            val file = File(pluginDir, path)
            file.parentFile?.mkdirs()
            file.writeText(
                """
                Title: Test
                GuiPlain: []
                GuiKey: {}
                templates: {}
                """.trimIndent(),
                Charsets.UTF_8,
            )
        }
    }
}
