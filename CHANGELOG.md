# 更新日志

## 0.2.6

* **商店支持 NBT**：商品可带 `nbt`（附魔书 / 药水的具体附魔、效果），买入按模板铸造，回收价表升级为
  `{itemId, nbt?, price}` 两级匹配（`nbt` 缺省 = 通配，写了 = 精确，精确优先；比较时忽略 `Damage`/`RepairCost`）；
  老的纯 id 价目表**自动迁移**（留 `recycle.json.bak`），语义不变。
* 新增 `books_potions`（附魔与药水）分类：15 本常用附魔书 + 9 种常用药水 + 5 条整类通配回收价。
* `/shop edit`、`/shop price`、`/shop recycle` 改为按手持物品的**完整 NBT** 操作；新增只读 `/shop nbt`。
* **售卖区** `/sell`：45 格放任意物品 → 「确认出售」点两次 → 自动卖出可回收的、原样退回不可回收的；
  **没确认就关界面一件不卖**；Shift 双向搬运；商店 46 槽与菜单 24 槽也指向它。
* **永久禁交易**：盔甲 / 武器 / 工具 / 原矿方块 / 刷怪蛋，商店与自由市场都不能买不能卖（代码写死，
  黑名单改不动）；升级时从价目表删 157 件、市场在售的撤下并邮寄回卖家。
* 修 `/sellall` 实际会连身上盔甲和副手一起卖的问题（现在只卖主背包 36 格）。
* 自检新增 `nbt shop` / `sell gui` / `trade blacklist` / `shop upgrade` / `sellall slots`。

## 0.2.5

* **修 Curios 饰品丢失**：快照不再清空玩家（`saveInventory(true)` 的副作用）、先整份读成功才允许清空、
  装不回去的饰品**进邮箱**、写文件原子替换 + `<uuid>.nbt.bak`、装回后补发客户端同步包、
  修正删除世界时读错的键（`CosmeticStacks` → `Cosmetics`）。
* **数据安全写锁**：`res.json` 与 `market/{listings,mailbox,blacklist}.json` 读不出来就拒绝写，
  锁定期间建/删领地被拒且**不扣费**、市场买卖被拒；`/res admin reload` 跳过坏记录改黄色提示。
* 新增运维指令 `/mv inventory inspect <玩家>`、`/mv inventory recover <玩家>`。

## 0.2.4

* 自由市场**永久挂单**：`/market sell <价格> 0` 永不到期；时长可省略；新增 `market/config.json`
  （`allowPermanent` / `permanentFeeRate` / `maxHours` / `defaultHours` / `maxListings`）与 `/market config reload`。
* `/res admin reload` 改成真正的重读（原先是"只保存"）。

## 0.2.3

* 修「垃圾桶 / 售卖区界面放不进物品」：前 5 行是真容器格却被当展示格拒绝，且重同步会撤回客户端预测。

## 0.2.2

* 修客户端启动崩溃（两个 jar 抢网络通道）、菜单吞物品、服务端/客户端格子数错位（统一 90 格）。
* 出生点地表化 + 地底救援；删除 4 个商店方块；商店分类页按截图重做。

## 0.2.1

* 删除 NPC 模块；商店价目表内置（11 类）；菜单「一键出售背包」加二次确认；商品名走客户端翻译。

## 0.2.0

* 商店**可卖**（数量页确认出售，走回收价表）；`/domain` → `/res`（金锄圈地、按体积收费、7 个保护标志）；
* `/mv` 权限收紧（除 `/mv tp` 外都要 op3）；op3+ 世界规则豁免；Essentials 平替约 60 条 + 禁言；e33chat 侧边栏按钮补丁。

## 0.1.0

* 首个版本：多元世界、跨维度传送、世界独立背包（含 Curios）、箱子菜单框架 + 编辑器、货币、
  官方商店、一键售卖、垃圾桶、自由市场、邮箱、领地、KubeJS 接口。
