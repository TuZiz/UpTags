# UpTags

一个基于 Kotlin 的 Minecraft 称号插件，支持：

- 称号仓库、佩戴、卸下与默认称号
- Buff 强化、启停、拆卸和等级卷轴返还
- 粒子购买、选择、拆卸和解锁卷返还
- 卷轴右键选择称号使用
- 称号商店 GUI 与自定义称号流程
- PlaceholderAPI / Vault / PlayerPoints 集成
- 本地 YML 与 MySQL 数据存储
- Redis 跨服在线缓存失效通知
- Spigot / Paper / Folia 兼容运行

## 常用命令

- `/tags` 打开称号仓库
- `/tags shop` 打开称号商店
- `/tags equip <id>` 佩戴指定称号
- `/tags unequip` 卸下当前称号
- `/tags upgrade [id]` 打开指定称号强化界面
- `/tags admin scroll give <player> <scroll|buff_all|particle_all> [amount]` 发放卷轴

## 存储模式

默认存储模式是 `yml`。

支持的存储模式：

- `yml`：本地文件存储，路径默认是 `plugins/UpTags/data/playerdata/<uuid>.yml`
- `mysql`：MySQL 数据库存储，适合多服共享玩家称号数据

当 `storage.mode` 切换为 `mysql` 时，插件会在启动阶段扫描现有 YML 玩家数据并幂等导入 MySQL。数据库中同 UUID 的版本更高或相同会跳过，避免旧文件覆盖跨服新数据。

Redis 仍只负责跨服在线缓存失效通知，不作为主存储。

## 配置入口

- `config.yml` 主配置、存储、Redis、拆卸费用
- `messages.yml` 消息文本
- `tags.yml` 称号定义、稀有度、默认解锁
- `upgrades.yml` Buff、粒子、升级组、卷轴定义
- `shop.yml` 商店商品定义
- `custom-title.yml` 自定义称号规则和配色库
- `gui/warehouse.yml` 称号仓库
- `gui/upgrade.yml` 强化中心
- `gui/detach.yml` 拆卸中心
- `gui/scroll-select.yml` 卷轴选择称号界面
- `gui/shop.yml` 商店界面

## PlaceholderAPI

变量前缀统一为 `%tags_...%`，常用变量包括：

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

## 构建

```bash
./gradlew build
```

构建完成后，带依赖的插件包位于 `build/libs/*-all.jar`。
