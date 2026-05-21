# UpTags 称号获取方式说明

本文档用于说明当前版本玩家怎样获得称号、不同获取方式分别检查什么条件，以及服主应该到哪些配置文件里调整规则。

适用版本：当前 `main` 分支默认配置。

## 一、玩家获取入口

玩家常用入口：

```text
/tags
/tags shop
/tags custom
```

管理员常用入口：

```text
/tags admin give <玩家> <称号ID>
/tags admin take <玩家> <称号ID>
/tags admin manage <玩家>
```

如果玩家刚进服时数据还没有加载完成，称号菜单、购买、装备、升级、粒子选择和 Placeholder 都会进入保护状态。此时应提示“数据加载中”或拒绝操作，避免把空数据误保存到 MySQL/YML。

## 二、称号来源总览

当前称号来源分为 7 类。

| 获取来源 | 玩家表现 | 配置位置 | 说明 |
| --- | --- | --- | --- |
| 默认解锁 | 进服后自动拥有 | `tags.yml` | `default-unlocked: true` 的称号会自动加入玩家数据。 |
| 权限解锁 | 拥有权限后自动同步 | `tags.yml` | 配置了权限或命中默认权限规则时，玩家准备数据后会自动解锁。 |
| 商店购买 | 在 `/tags shop` 里消耗货币购买 | `shop.yml` | 用点券、金币或称号币购买，模式通常是 `BUY`。 |
| 物品兑换 | 提交指定材料领取 | `shop.yml` | 背包材料足够时才会扣除，材料不足不会扣钱。 |
| 挑战领取 | 达成挑战后领取 | `shop.yml` | 挖矿、击杀、维度、群系、高度、统计等进度会持久化到玩家数据。 |
| 季节/声望 | 满足活动、权限、货币等综合条件 | `shop.yml` | 用于限时活动称号、长期声望称号。 |
| 管理员发放 | 管理员直接给予 | 命令 | 不走商店条件，直接写入玩家称号数据。 |

自定义称号不属于普通配置称号，通常通过 `/tags custom` 或自定义称号商店流程生成，生成后存入玩家个人数据。

## 三、基础称号

基础称号主要来自 `tags.yml`。其中 `newbie` 是当前默认解锁称号。

| 中文名 | 称号 ID | 获取方式 | 配置说明 |
| --- | --- | --- | --- |
| 萌新 | `newbie` | 进服自动获得 | `default-unlocked: true`。 |

普通称号的获取规则已经集中放到 `shop.yml`。`tags.yml` 只负责定义称号本体，玩家仍然通过 `/tags shop` 领取或兑换这些称号。

| 中文名 | 称号 ID | 获取方式 | 玩家需要做什么 |
| --- | --- | --- | --- |
| 矿洞住民 | `miner_soul` | Placeholder 条件 | 挖掘方块统计达到 `256`，条件为 `%statistic_mine_block%>=256`。 |
| 钓鱼佬 | `fisher_daily` | Placeholder 条件 | 钓鱼成功统计达到 `32`，条件为 `%statistic_fish_caught%>=32`。 |
| 红石学徒 | `redstone_apprentice` | 物品提交 | 提交 `REDSTONE x64` 和 `REPEATER x8`。 |
| 种田人 | `field_keeper` | 物品提交 | 提交 `WHEAT x64` 和 `CARROT x32`。 |
| 夜猫子 | `night_owl` | Placeholder 条件 | 长时间不睡觉，条件为 `%statistic_time_since_rest%>=72000`。 |
| 跑图选手 | `map_runner` | Placeholder 条件 | 步行距离统计达到 `100000cm`，条件为 `%statistic_walk_one_cm%>=100000`。 |
| 插火把的人 | `cave_lighter` | 物品提交 | 提交 `TORCH x128`。 |
| 箱子整理师 | `chest_sorter` | 物品提交 | 提交 `CHEST x16` 和 `BARREL x8`。 |
| 面包守卫 | `bread_guard` | 物品提交 | 提交 `BREAD x32`。 |
| 史莱姆好友 | `slime_friend` | 物品提交 | 提交 `SLIME_BALL x32`。 |

Placeholder 条件会按 fail-closed 处理：条件非法、无法解析或 PlaceholderAPI 返回异常时，不会默认放行，并且应该记录 warning，避免玩家绕过条件。

## 四、高级商店称号

高级称号主要由 `shop.yml` 管理商品规则，由 `tags.yml` 管理显示名、描述、稀有度和升级组。

### 1. 挑战领取类

挑战领取类商品的模式是 `CHALLENGE_CLAIM`。玩家先在服务器内完成行为，插件把进度写入玩家数据，达标后再到 `/tags shop` 领取。

| 中文名 | 称号 ID | 获取条件 | 主题 |
| --- | --- | --- | --- |
| 深暗绘图师 | `abyss_cartographer` | 到达或记录一次深暗群系，条件为 `challenge:biome:deep_dark:1`。 | 深暗探索 |
| 下界巡路人 | `nether_pathfinder` | 进入一次下界，条件为 `challenge:world:the_nether:1`。 | 下界探索 |
| 末地远航者 | `end_voyager` | 进入一次末地，条件为 `challenge:world:the_end:1`。 | 末地探索 |
| 监守回声 | `warden_echo` | 击杀 `WARDEN x1`，条件为 `challenge:kill:warden:1`。 | Boss 挑战 |
| 深层钻脉师 | `diamond_vein_master` | 挖掘 `DEEPSLATE_DIAMOND_ORE x64`，条件为 `challenge:mine:deepslate_diamond_ore:64`。 | 挖矿挑战 |
| 天际构筑者 | `sky_limit_builder` | 在主世界到达高度 `250`，条件为 `challenge:height:overworld:250`。 | 建筑高度 |
| 远行长路 | `long_marcher` | 步行统计达到 `100000cm`，条件为 `challenge:stat:walk_one_cm:100000`。 | 长途探索 |
| 古城潜行者 | `ancient_city_runner` | 在深暗区域累计停留 `300` 秒，条件为 `challenge:deep_dark_stay:300`。 | 古城潜行 |

挑战进度不是临时内存数据，会存入 `PlayerTagData`。服务器重启后，已经完成的挑战进度不会丢失。

### 2. 物品兑换类

物品兑换类商品的模式是 `ITEM_EXCHANGE`。插件会先完整检查商品、权限、条件、余额、提交物品和是否已拥有，确认都通过后才扣材料。

| 中文名 | 称号 ID | 兑换要求 | 附加条件 |
| --- | --- | --- | --- |
| 深海打捞员 | `ocean_salvager` | `PRISMARINE_SHARD x64`、`NAUTILUS_SHELL x4` | 无。 |
| 红石建筑师 | `redstone_architect` | `COMPARATOR x16`、`OBSERVER x16`、`REDSTONE_BLOCK x8` | 无。 |
| 要塞掠行者 | `fortress_raider` | `BLAZE_ROD x32`、`NETHER_BRICK x64` | 需要先进入过下界：`challenge:world:the_nether:1`。 |
| 龙息遗珍猎手 | `dragon_relic_hunter` | `CHORUS_FRUIT x64`、`DRAGON_BREATH x8` | 需要先进入过末地：`challenge:world:the_end:1`。 |

如果材料不足，购买流程会直接拒绝，不会扣金币、点券或称号币。

### 3. 货币购买类

货币购买类商品的模式是 `BUY`。当前默认示例包含点券和金币两种。

| 中文名 | 称号 ID | 消耗 | 说明 |
| --- | --- | --- | --- |
| 珊瑚守望 | `coral_keeper` | `POINTS x1200` | 海洋主题称号，使用点券购买。 |
| 黑曜金库 | `obsidian_banker` | `MONEY x25000` | 财富主题称号，使用经济插件金币购买。 |

### 4. 季节与声望类

季节与声望类商品用于活动服、长期服和跨服身份体系。

| 中文名 | 称号 ID | 模式 | 获取要求 |
| --- | --- | --- | --- |
| 凛冬唤雪者 | `season_snowcaller` | `SEASONAL` | 满足 `%server_season%==winter`，消耗 `POINTS x500`，提交 `SNOW_BLOCK x64`。 |
| 声望奠基者 | `prestige_founder` | `PRESTIGE` | 拥有权限 `uptags.prestige.founder`，在线时长统计 `challenge:stat:play_one_minute:72000` 达标，消耗 `TITLE_COIN x100`。 |

`SEASONAL` 适合限时节日活动，`PRESTIGE` 适合长期声望、赞助、赛季结算、老玩家身份等场景。

## 五、挑战条件格式

挑战条件统一写在 `shop.yml` 的 `conditions` 中。建议一行只写一个条件，便于后期维护。

```yaml
conditions:
  - challenge:mine:deepslate_diamond_ore:64
  - challenge:biome:deep_dark:1
  - challenge:world:the_nether:1
  - challenge:kill:warden:1
  - challenge:height:overworld:250
  - challenge:stat:walk_one_cm:100000
```

常用条件含义如下。

| 条件格式 | 中文含义 | 进度来源 |
| --- | --- | --- |
| `challenge:mine:<material>:<amount>` | 挖掘指定方块达到数量。 | `BlockBreakEvent` |
| `challenge:biome:<biome>:1` | 到达指定群系。 | 限频后的 `PlayerMoveEvent` |
| `challenge:world:<world-key>:1` | 到达指定维度。 | `PlayerChangedWorldEvent` |
| `challenge:kill:<entity>:<amount>` | 击杀指定实体达到数量。 | `EntityDeathEvent` |
| `challenge:height:<world-key>:<height>` | 在指定世界达到指定高度。 | 限频后的 `PlayerMoveEvent` |
| `challenge:stat:<statistic>:<amount>` | Bukkit 统计值达到指定数量。 | `PlayerStatisticIncrementEvent` |
| `challenge:deep_dark_stay:<seconds>` | 在深暗区域累计停留指定秒数。 | 限频后的 `PlayerMoveEvent` |
| `challenge:advancement:<key>:1` | 完成指定进度。 | `PlayerAdvancementDoneEvent` |

所有挑战进度写入都应走异步保存，不能在主线程或 Folia 区域线程里做 MySQL/YML 阻塞 IO。

## 六、称号能力与升级组

称号本身在 `tags.yml` 里绑定 `upgrade-groups`，升级组在 `upgrades.yml` 里定义可用 Buff 和粒子。

示例：

| 升级组 | 适用主题 | 默认弱增益 | 可用粒子示例 |
| --- | --- | --- | --- |
| `ADVENTURE` | 探险、深暗、远行 | 速度、夜视 | 足迹、脉冲、光环 |
| `MINING` | 挖矿、矿脉 | 急迫、夜视 | 星火、足迹 |
| `NETHER` | 下界、要塞 | 抗火、速度 | 烈焰、彗星 |
| `END` | 末地、龙息 | 缓降、跳跃提升 | 环绕、光翼、旋纹 |
| `OCEAN` | 海洋、打捞 | 水下呼吸、幸运 | 灵雨、灵气 |
| `REDSTONE` | 红石、机关 | 急迫、速度 | 脉冲、星环 |
| `BUILDING` | 建筑、高空 | 跳跃提升、缓降 | 护盾、光环 |
| `SEASONAL` | 活动、季节 | 速度、幸运 | 灵雨、星火 |
| `PRESTIGE` | 声望、身份 | 幸运、夜视 | 冠冕、护盾 |

默认配置里的高级主题组只绑定弱 Buff，避免给玩家过强的常驻战斗优势。正式服可以继续通过世界、权限、PVP 状态等配置限制 Buff 生效范围。

## 七、购买安全规则

商店购买使用订单状态机，不是简单地直接扣除。

1. 创建 `PENDING` 订单，并使用严格保存。
2. `PENDING` 保存成功后，才允许扣提交物品和货币。
3. 扣除成功后，订单进入 `PAID`。
4. 发放称号并保存 `GRANTED`。
5. `GRANTED` 保存成功后，才提示玩家购买成功。
6. 发称号失败时会退款或返还材料。
7. 如果玩家中途下线、服务器关闭或补偿失败，订单会保留为 `FAILED` 或 `REFUND_PENDING`，方便恢复或管理员处理。

物品回滚不会整包恢复背包，而是只记录并返还本次实际扣除的物品，避免出现复制物品或覆盖玩家背包变化的问题。

## 八、服主配置位置

| 文件 | 用途 | 常改内容 |
| --- | --- | --- |
| `src/main/resources/tags.yml` | 定义称号本体。 | 显示名、描述、稀有度、默认解锁、升级组。 |
| `src/main/resources/shop.yml` | 定义全部称号商品。 | 商品模式、挑战条件、Placeholder 条件、价格、提交物品、图标、权限。 |
| `src/main/resources/upgrades.yml` | 定义升级体系。 | Buff、粒子、升级组、升级消耗。 |
| `src/main/resources/messages.yml` | 定义提示文本。 | 购买成功、余额不足、数据加载中、失败补偿、退款提示。 |

正式服新增称号时，建议至少同时检查：

1. `tags.yml` 里是否有对应称号 ID。
2. `shop.yml` 的 `target-id` 是否指向真实称号。
3. `mode` 是否符合玩法：购买用 `BUY`，材料兑换用 `ITEM_EXCHANGE`，挑战领取用 `CHALLENGE_CLAIM`。
4. `conditions` 是否能被解析，非法条件不要上线。
5. `submit-items` 是否只写 Bukkit 合法物品名。
6. `cost` 是否符合经济插件或称号币系统。
7. `upgrade-groups` 是否只给合理强度的 Buff 和粒子。
