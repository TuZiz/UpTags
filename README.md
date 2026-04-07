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
- PostgreSQL 数据存储
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

`/tags create` 支持少参数快捷创建：
- `permission` 留空时默认使用 `uptags.tag.<tagId>`
- `buffGroup` 留空时默认使用 `upgrades.yml` 中第一个升级组
- `particleGroup` 留空时默认跟随 `buffGroup`
- 创建后可继续在 `tags.yml` 中手改显示名、描述与 RGB 颜色文本

---

## 新增：称号商店与自定义称号

### 商店 GUI
商店入口：
- `/tags shop`

当前商店支持两种商品类型：
- `TAG`：直接购买并解锁某个普通称号
- `CUSTOM`：购买一次“自定义专属称号服务”资格，随后进入聊天输入与预览流程

### 自定义称号流程
当玩家购买 `CUSTOM` 类型商品后：
1. 插件提示玩家在聊天栏输入称号文本
2. 输入后自动生成多套随机 RGB 配色方案
3. 聊天栏发送可点击按钮：
   - 上一套
   - 下一套
   - 重随机
   - 确认
   - 取消
4. 玩家可点击按钮循环切换预览效果
5. 确认后写入玩家数据并可立即佩戴

### 当前支持的货币类型
- `MONEY`：金币（Vault）
- `POINTS`：点券（PlayerPoints）
- `TITLE_COIN`：称号币（插件内置货币）

---

## 依赖

可选软依赖：
- PlaceholderAPI
- Vault
- PlayerPoints

运行时依赖：
- PostgreSQL
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

说明：
- `type: TAG` 购买后会直接调用普通称号解锁逻辑
- `type: CUSTOM` 会进入自定义称号流程
- `conditions` 复用现有条件表达式判断链路
- `permission` 可用于限制某商品只对特定玩家可见 / 可买

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

说明：
- `default-title-coin-balance`：玩家首次默认称号币
- `session-timeout-seconds`：聊天输入会话超时秒数
- `allowed-pattern`：允许字符范围
- `blocked-words / blocked-patterns`：敏感词与拦截规则
- `palettes`：随机颜色方案池
- `equip-after-confirm`：确认后是否立即佩戴

---

## 商店 GUI 配置示例

文件：`src/main/resources/gui/shop.yml`

```yml
Title: "&#FDE68A称号&#FF8FD8商店"
GuiPlain:
  - "X#######X"
  - "#@@@@@@@#"
  - "#@@@@@@@#"
  - "#@@@@@@@#"
  - "#@@@@@@@#"
  - "L###B###N"

templates:
  product-available:
    material: "NAME_TAG"
    name: "%product_name%"
    lore:
      - "&#334155&l&m━━━━━━━━━━━━━━━━━━━━━━━━"
      - "%product_lore%"
      - ""
      - " &#CBD5E1价格: &#FDE68A%product_price% %product_currency%"
      - " &#86EFAC▶ 左键购买"
      - "&#334155&l&m━━━━━━━━━━━━━━━━━━━━━━━━"
```

---

## 数据存储

当前统一使用 PostgreSQL。

默认表：
- `uptags_player_data`

存储字段：
- `uuid`
- `data_json`
- `version`
- `updated_at`

当前除了原有称号拥有 / 强化 / 粒子数据外，还会存：
- 插件内称号币余额
- 玩家自定义称号数据
- 当前佩戴的自定义称号

---

## 跨服同步

当 Redis 启用时：
- 当前服写入 PostgreSQL 成功后发布玩家数据失效消息
- 其他服收到消息后将该玩家缓存标记为 stale
- 在线玩家在后续刷新时回源读取最新数据

Redis 只做同步广播，不做主存储。

---

## 界面数据来源

所有 GUI 统一走业务链：

- `MenuService -> TagService / ShopService -> PlayerDataRepository -> PlayerDataStore`

因此：
- 商店商品配置来自 `shop.yml`
- 玩家购买结果与自定义称号状态来自 PostgreSQL
- GUI 本身不直接操作数据库

---

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

---

## 构建

```bash
./gradlew build
```

---

## 主要流程

### 玩家进入服务器
1. 加载玩家数据
2. 同步默认称号 / 权限称号
3. 如启用强制默认称号则自动装备
4. 初始化称号币默认值
5. 启动该玩家的 Buff / 粒子效果任务

### 玩家打开商店
1. 执行 `/tags shop`
2. 打开商店 GUI
3. 读取 `shop.yml` 商品配置
4. 按权限、条件、是否上架筛选可见商品
5. 点击商品触发购买逻辑

### 玩家购买普通称号商品
1. 左键商品
2. 检查权限 / 前置条件 / 金额
3. 扣费成功后解锁称号
4. 返回商店界面刷新状态

### 玩家购买自定义称号商品
1. 左键自定义商品
2. 扣费成功后开启聊天输入流程
3. 玩家输入称号文本
4. 插件生成多套随机 RGB 颜色方案
5. 聊天栏提供点击按钮切换预览
6. 玩家点击确认后保存并可立即佩戴

### 玩家使用自定义称号预览命令
- `/tags custom preview prev`
- `/tags custom preview next`
- `/tags custom preview reroll`
- `/tags custom preview confirm`
- `/tags custom preview cancel`

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

---

## 上服后的手动功能验收清单

### A. 启动阶段先看
1. 启动服务器
2. 检查控制台是否有以下异常：
   - PostgreSQL 连接失败
   - Redis 初始化失败
   - `shop.yml / custom-title.yml / gui/shop.yml` 加载失败
   - PlaceholderAPI / Vault / PlayerPoints 缺失导致的硬错误
3. 确认插件成功启用后再测试功能

### B. 先测基础入口
1. 输入 `/tags`
   - 应能正常打开仓库 GUI
2. 输入 `/tags shop`
   - 应能正常打开商店 GUI
3. 输入 `/tags upgrade newbie`
   - 应能正常打开强化 GUI

### C. 测普通商品购买
1. 在 `shop.yml` 中保留一个 `type: TAG` 商品
2. 给自己足够金币或点券
3. 打开 `/tags shop`
4. 左键购买商品
5. 检查：
   - 是否扣费成功
   - 是否收到购买成功消息
   - 仓库中该称号是否变为已拥有
   - `/tags equip <id>` 是否可佩戴

### D. 测自定义称号流程
1. 在 `shop.yml` 中保留一个 `type: CUSTOM` 商品
2. 确认自己有足够 `TITLE_COIN`
3. 打开 `/tags shop`
4. 左键购买自定义称号商品
5. 观察：
   - 是否收到“请输入称号文本”提示
6. 在聊天栏输入一个合法文本，例如：
   - `星辰旅人`
7. 检查：
   - 是否收到预览消息
   - 是否出现可点击按钮
8. 依次点击：
   - `上一套`
   - `下一套`
   - `重随机`
9. 检查预览是否会变化
10. 点击 `确认`
11. 检查：
   - 是否收到确认消息
   - `%tags_current%` 是否已变成自定义称号显示

### E. 测输入限制
逐条测试：
1. 输入空文本
2. 输入超长文本
3. 输入敏感词（如 `admin`）
4. 输入非法字符
5. 输入 `cancel`

预期：
- 都能收到对应拦截消息
- `cancel` 会中止流程

### F. 测称号币
1. 确认玩家初始是否拿到 `default-title-coin-balance`
2. 购买一次 `CUSTOM` 商品
3. 检查 `%tags_title_coin%` 是否减少
4. 若不足，再次购买时是否提示余额不足

### G. 测回归功能
最后再回归原功能，避免新功能把旧功能带坏：
1. 普通称号佩戴 / 卸下
2. Buff 升级
3. 粒子购买与切换
4. 卷轴使用
5. `/tags create`
6. `/tags admin scroll give`

### H. 跨服场景（如果启用 Redis）
1. 两个服共用同一个 PostgreSQL
2. 启用 Redis 同步
3. 在 A 服购买普通称号或确认自定义称号
4. 去 B 服检查：
   - 玩家数据是否能在刷新后同步到最新状态

---

## 建议的首轮上服测试顺序
最省时间的顺序：
1. 启服看报错
2. `/tags`
3. `/tags shop`
4. 买一个普通称号商品
5. 买一个自定义称号商品
6. 输入文本并点击预览按钮
7. 确认保存
8. 检查 `%tags_current%`
9. 回归 Buff / 粒子 / 卷轴

这样能最快判断：
- 配置有没有加载成功
- GUI 是否都正常打开
- 新经济链路是否可用
- 自定义称号主流程是否打通
