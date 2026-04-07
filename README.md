# UpTags

一个基于 Kotlin 的称号插件，当前支持：

- 称号仓库
- 称号佩戴 / 卸下
- Buff 强化与启停
- 粒子购买与切换
- 卷轴解锁 / 升级
- 称号商店 GUI
- 自定义专属称号购买与聊天输入
- 多套 RGB 颜色方案预览与点击切换
- PlaceholderAPI / Vault / PlayerPoints 集成
- PostgreSQL / MySQL / 本地 YML 数据存储
- Redis 跨服失效同步
- Spigot / Folia 兼容运行

---

## 功能概览

### 玩家功能
- `/tags` 打开称号仓库
- `/tags shop` 打开称号商店
- `/tags equip <id>` 佩戴指定称号
- `/tags unequip` 卸下当前称号
- `/tags upgrade [id]` 打开指定称号强化界面
- `/tags custom preview <prev|next|reroll|confirm|cancel>`
  - 用于自定义称号预览链路中的颜色方案切换 / 确认 / 取消

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

---

## 存储模式

当前支持三种存储模式：
- `yml`：本地文件存储
- `mysql`：MySQL 数据库存储
- `pg`：PostgreSQL 数据库存储

默认模式：
- `yml`

说明：
- Redis 仍然只负责跨服失效广播，不是主存储
- 三种存储模式都会保存最近新增的：
  - 称号币余额
  - 自定义称号数据
  - 当前佩戴的自定义称号

---

## 依赖

可选软依赖：
- PlaceholderAPI
- Vault
- PlayerPoints

运行时依赖：
- 本地 yml 模式：无额外数据库依赖
- mysql 模式：MySQL
- pg 模式：PostgreSQL
- Redis（如果启用跨服同步）

---

## 配置说明

### 主配置文件
文件：`src/main/resources/config.yml`

```yml
settings:
  effect-tick-interval: 20
  force-default-tag:
    enabled: true
    tag-id: "newbie"

storage:
  mode: "yml"
  yml:
    file: "data/playerdata"
  mysql:
    jdbc-url: "jdbc:mysql://127.0.0.1:3306/minecraft?useSSL=false&characterEncoding=utf8"
    username: "root"
    password: "change-me"
    table: "uptags_player_data"
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

### 业务配置文件
- `tags.yml`：称号定义、稀有度、默认解锁
- `upgrades.yml`：Buff、升级组、粒子、卷轴
- `shop.yml`：商店商品定义
- `custom-title.yml`：自定义称号规则、敏感词、长度、随机方案设置
- `messages.yml`：消息文本
- `gui/warehouse.yml`：仓库界面布局
- `gui/upgrade.yml`：强化界面布局
- `gui/shop.yml`：商店界面布局

---

## 商店配置示例

文件：`src/main/resources/shop.yml`

```yml
products:
  warrior_tag:
    type: TAG
    target-id: newbie
    enabled: true
    permission: ''
    conditions: []
    cost:
      type: MONEY
      amount: 1000
      conditions: []
    icon:
      material: NAME_TAG
      name: '&#FDE68A战士称号'
      lore:
        - '&#CBD5E1内容: &#F8FAFC%tag_display%'
        - '&#CBD5E1价格: &#FDE68A1000 金币'
        - '&#86EFAC左键购买并解锁该称号'

  custom_basic:
    type: CUSTOM
    target-id: basic
    enabled: true
    permission: ''
    conditions: []
    cost:
      type: TITLE_COIN
      amount: 5
      conditions: []
    icon:
      material: BOOK
      name: '&#FF8FD8自定义专属称号'
      lore:
        - '&#CBD5E1支持输入自定义文本'
        - '&#CBD5E1支持随机多套 RGB 配色'
        - '&#CBD5E1价格: &#FDE68A5 称号币'
        - '&#86EFAC左键开始定制'
```

---

## 自定义称号配置示例

文件：`src/main/resources/custom-title.yml`

```yml
settings:
  default-title-coin-balance: 10
  session-timeout-seconds: 120

presets:
  basic:
    min-length: 2
    max-length: 12
    random-schemes: 4
    colors-per-scheme: 2
    allow-manual-colors: true
    allow-spaces: true
    allowed-pattern: '^[A-Za-z0-9_\u4e00-\u9fa5 ]+$'
    blocked-words:
      - admin
      - gm
      - op
    blocked-patterns:
      - '(?i)owner'
    palettes:
      - '#FDE68A,#FF8FD8'
      - '#7DD3FC,#C084FC'
      - '#86EFAC,#FDE68A'
      - '#F8FAFC,#93C5FD'
    preview-template: '&#FDE68A当前预览: %title%'
    equip-after-confirm: true
```

---

## 数据存储说明

三种模式对外行为统一走：
- `MenuService -> TagService / ShopService -> PlayerDataRepository -> PlayerDataStore`

本地 yml 模式建议目录：
- `plugins/UpTags/data/playerdata/<uuid>.yml`

数据库模式默认表名：
- `uptags_player_data`

核心持久化字段语义：
- 拥有称号
- 当前佩戴称号
- Buff / 粒子进度
- 称号币余额
- 自定义称号集合
- 当前佩戴的自定义称号
- version / updated_at

---

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
- `%tags_title_coin%`
- `%tags_can_upgrade%`

---

## 构建

```bash
./gradlew build
```
