# UpTags

一个基于 Kotlin 的称号插件，支持：

- 称号仓库
- 称号佩戴 / 卸下
- Buff 强化与启停
- 粒子购买与切换
- 卷轴解锁 / 升级
- PlaceholderAPI / Vault / PlayerPoints 集成
- PostgreSQL 数据存储
- Redis 跨服失效同步
- Spigot / Folia 兼容运行

## 功能概览

### 玩家功能
- `/tags` 打开称号仓库
- `/tags equip <id>` 佩戴指定称号
- `/tags unequip` 卸下当前称号
- `/tags upgrade [id]` 打开指定称号强化界面

### 管理功能
- `/tags reload`
- `/tags create <tagId> [permission] [buffGroup] [particleGroup]`
- `/tags admin give <player> <tag>`
- `/tags admin take <player> <tag>`
- `/tags admin scroll give <player> <scroll> [amount]`
- `/tags admin tag create <id>`
- `/tags admin tag delete <id>`
- `/tags admin tag setdisplay <id> <text>`
- `/tags admin tag setrarity <id> <rarity>`
- `/tags admin tag setgroups <id> <group1,group2>`
- `/tags admin tag setdefault <id> <true|false>`

`/tags create` 支持少参数快捷创建：
- `permission` 留空时默认使用 `uptags.tag.<tagId>`
- `buffGroup` 留空时默认使用 `upgrades.yml` 中第一个升级组
- `particleGroup` 留空时默认跟随 `buffGroup`
- 创建后可继续在 `tags.yml` 中手改显示名、描述与 RGB 颜色文本

## 依赖

可选软依赖：
- PlaceholderAPI
- Vault
- PlayerPoints

运行时依赖：
- PostgreSQL
- Redis（如果启用跨服同步）

## 配置说明

主配置文件：`src/main/resources/config.yml`

```yml
settings:
  effect-tick-interval: 20
  force-default-tag:
    enabled: true
    tag-id: "newbie"

storage:
  pg:
    jdbc-url: "jdbc:postgresql://127.0.0.1:5432/minecraft"
    username: "postgres"
    password: "change-me"
    table: "uptags_player_data"

sync:
  server-id: "server-1"
  online-refresh-delay-ticks: 20
  stale-max-age-seconds: 3
  redis:
    enabled: false
    uri: "redis://127.0.0.1:6379"
    channel: "uptags:player-sync"
```

### 业务配置
- `tags.yml`：称号定义、稀有度、默认解锁
- `upgrades.yml`：Buff、升级组、粒子、卷轴
- `messages.yml`：消息文本
- `gui/warehouse.yml`：仓库界面布局
- `gui/upgrade.yml`：强化界面布局

## 数据存储

当前统一使用 PostgreSQL。

表结构会在插件启动时自动初始化，默认表名：
- `uptags_player_data`

存储字段：
- `uuid`
- `data_json`
- `version`
- `updated_at`

## 跨服同步

当 Redis 启用时：

- 当前服写入 PostgreSQL 成功后，会发布玩家数据失效消息
- 其他服收到消息后，会将该玩家缓存标记为 stale
- 在线玩家会在后续刷新流程中重新读取最新数据

Redis 只做同步广播，不做主存储。

## 界面数据来源

界面本身不直接读取数据库，统一走业务链：

- `MenuService -> TagService -> PlayerDataRepository -> PlayerDataStore`

因此数据库迁移不会直接影响 GUI 配置格式，只要仓库层行为保持一致即可。

## 项目结构

```text
cn.aing.uptags
├─ command
├─ compat
├─ config
├─ gui
├─ listener
├─ model
│  ├─ config
│  └─ runtime
├─ repository
│  └─ store
├─ service
│  └─ sync
├─ util
├─ Support.kt
└─ UpTagsPlugin.kt
```

## 构建

```bash
./gradlew build
```

## 主要流程

### 玩家进入服务器
1. 插件加载玩家数据
2. 同步默认称号 / 权限称号
3. 如启用强制默认称号，则自动装备默认称号
4. 启动该玩家的 Buff / 粒子效果任务

### 玩家打开仓库
1. 执行 `/tags`
2. 打开仓库 GUI
3. GUI 从 `TagService.visibleTags()` 读取所有称号
4. 根据玩家拥有情况显示为“已拥有 / 未解锁”

### 玩家佩戴称号
1. 在仓库界面左键某个已拥有称号，或执行 `/tags equip <id>`
2. `TagService.equipTag()` 校验是否拥有
3. 更新 `equippedTagId`
4. 通过 `PlayerDataRepository` 异步保存到 PostgreSQL
5. 如启用 Redis，同步广播失效消息

### 玩家强化 Buff
1. 在强化界面左键 Buff 项
2. 检查该称号是否允许该 Buff
3. 检查条件与货币余额
4. 扣费成功后提升等级
5. 默认同时启用该 Buff
6. 异步保存并广播跨服失效

### 玩家切换 Buff 开关
1. 在强化界面右键 Buff 项
2. 已启用则关闭，未启用则开启
3. 异步保存并广播跨服失效

### 玩家购买粒子
1. 在强化界面左键粒子项
2. 检查是否允许购买
3. 检查条件与货币余额
4. 购买成功后加入已拥有粒子列表
5. 如果当前没有选中粒子，则自动选中
6. 异步保存并广播跨服失效

### 玩家切换粒子
1. 在强化界面右键粒子项
2. 若当前已选中，则取消
3. 否则设为当前粒子
4. 异步保存并广播跨服失效

### 玩家使用卷轴
1. 管理员发放卷轴物品
2. 玩家右键卷轴
3. 打开“选择称号”界面
4. 只展示当前可生效的称号
5. 玩家点击目标称号后：
   - Buff 卷轴：提升对应 Buff 等级
   - 粒子卷轴：直接解锁对应粒子
6. 成功后消耗卷轴并保存数据

## PlaceholderAPI 变量

前缀统一：`%tags_...%`

基础变量包括：
- `%tags_current%`
- `%tags_current_id%`
- `%tags_collected%`
- `%tags_total%`
- `%tags_progress%`
- `%tags_active_buffs%`
- `%tags_active_particle%`
- `%tags_active_particle_id%`
- `%tags_points%`
- `%tags_can_upgrade%`

按称号变量包括：
- `%tags_tag_owned_<tagId>%`
- `%tags_tag_buff_count_<tagId>%`
- `%tags_tag_first_buff_<tagId>%`
- `%tags_tag_buffs_<tagId>%`
- `%tags_tag_particle_count_<tagId>%`
- `%tags_tag_particle_<tagId>%`
- `%tags_tag_particles_<tagId>%`

按组变量包括：
- `%tags_track_<trackId>%`
- `%tags_group_level_<groupId>%`
- `%tags_group_name_<groupId>%`
