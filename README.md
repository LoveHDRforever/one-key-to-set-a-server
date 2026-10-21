# One Key To Set A Server

一个 **服务端** Forge mod（Minecraft **1.20.1** / Forge **47.x**）：把 Bukkit 服务器上常见的一整套"开服功能"做成一个 mod——
多元世界、每世界独立背包、领地、商店（含 NBT 商品）、自由市场、邮箱、售卖区、箱子菜单、聊天栏按钮、Essentials 风格指令。

**纯服务端 mod**：原版客户端可以直接进服（所有界面都是原版箱子 `GENERIC_9xN`）。
只有聊天栏侧边按钮需要一个可选的客户端补丁（`chatbridge`）。

---

## 功能

| 模块 | 能做什么 |
|---|---|
| 多元世界 `/mv` | 世界 = 根目录下的文件夹；在线创建/加载/卸载/删除/重置；每世界游戏模式、边界、PvP、自动重置、独立背包 |
| 每世界独立背包 | 切世界自动换背包（含 Curios 饰品栏，Curios 为软依赖）；写文件原子替换 + 保留一代 `.bak` |
| 领地 `/res` | Residence 风格：金锄圈地、按体积收费、7 个保护标志、成员/转让、`/res admin` 管理 |
| 服务器商店 `/shop` | 分类 → 商品 → 数量三页；可买可卖（独立回收价表）；**支持 NBT 商品**（附魔书/药水单独定价）；图形化改价 `/shop edit` |
| 售卖区 `/sell` | 45 格箱子界面，放入任意物品 → 「确认出售」点两次 → 自动卖能卖的、原样退回不能卖的 |
| 自由市场 `/market` | 一口价挂单、永久单、分类/搜索/我的挂单、购买、撤单、到期退回邮箱、黑名单 |
| 邮箱 `/mail` | 玩家离线也能收的东西都在这里；登录自动领取 |
| 菜单 `/menu` | 纯箱子菜单 + 在线编辑器（op4）；按钮可绑定指令/子菜单 |
| 聊天栏按钮 | 客户端补丁 `chatbridge` 在 e33chat 侧边栏画指令按钮，列表由服务端下发 |
| Essentials 平替 | 约 60 条常用指令：`/spawn /home /warp /tpa /back /rtp /sethome /mute …` |
| 经济 | `/money`（两位小数）、`/money pay|give|take|set` |
| 其它 | 世界规则豁免（op3+）、定时重置、跨维度安全传送、出生点地表化/地底救援、KubeJS 接口 |

> 所有界面/指令的完整说明在 [`docs/总说明书.md`](docs/总说明书.md)（中文，约 120 KB，含逐字段配置说明）。

---

## 环境

| 项 | 要求 |
|---|---|
| Minecraft | 1.20.1 |
| Forge | 47.x（开发/自测用 47.4.23） |
| Java | **17**（构建与运行都需要） |
| 软依赖 | Curios（饰品栏）、KubeJS（脚本接口）、e33chat（仅聊天栏按钮补丁需要）；不装会自动降级，不会拒绝启动 |

---

## 安装

1. 服务端：把 `onekeytosetaserver-<版本>.jar` 放进 `mods/`，启动即可；
2. 客户端（可选）：`oktsas_chatbridge-<版本>.jar`（+ e33chat 本体）才显示聊天侧边按钮；
   把主 jar 也放进客户端只会让头颅皮肤/图标更完整，不装也能进服；
3. 首次启动会在 `<服务器根目录>/multiverse/` 下生成配置与商店价目表，日志里能看到
   `[Shop] 11 categor(ies), 833 item(s), 814 recycle price(s)`。

发布包请到本仓库的 **Releases** 下载（源码仓库里不放编译好的 jar）。

---

## 从源码构建

需要 **JDK 17**（`JAVA_HOME` 指向它，或在 `~/.gradle/gradle.properties` 里设置 `org.gradle.java.home`）。

```bash
./gradlew build          # 产物：build/libs/onekeytosetaserver-<version>.jar
```

* 依赖全部从公开 Maven 解析（Curios：`maven.theillusivec4.top`；KubeJS：`maven.latvian.dev`；
  KubeJS 的 Architectury 依赖：`maven.architectury.dev`），**不需要**在本地放第三方 jar；
* 第一次构建会下载 Forge/Minecraft 依赖，耗时较长，属正常现象；
* 客户端补丁是独立子工程，需要先把 `e33chat-forge-2.4.17.jar`（CurseForge 下载）放进
  `chatbridge/libs/`，再：

```bash
cd chatbridge
./gradlew build          # 产物：chatbridge/build/libs/oktsas_chatbridge-<version>.jar
```

---

## 常用入口

```
/menu            箱子主菜单（世界列表 / 商店 / 市场 / 邮箱 / 垃圾桶 / 售卖区）
/shop            服务器商店
/sell            售卖区
/market          自由市场
/mail            邮箱
/trash           垃圾桶
/res             领地（/res wand 领法杖）
/mv              多元世界
/money           经济
```

权限沿用原版 OP 等级：**op3 = 世界管理员**，**op4 = 全局管理员**（见说明书第三章）。

---

## 文档

* [`docs/总说明书.md`](docs/总说明书.md) —— 完整手册（安装、逐模块、逐字段配置、指令总表、故障排查）
* [`docs/使用说明.md`](docs/使用说明.md) —— 最新版本的新功能速查
* [`docs/商店品类清单.md`](docs/商店品类清单.md) —— 商店 833 件商品的完整价目
* [`docs/玩家数据存放位置.md`](docs/玩家数据存放位置.md) —— 每个数据文件在哪、删了会丢什么

---

## 许可

**All Rights Reserved**（保留所有权利）。源码公开供学习与参考，未经许可请勿重新分发编译产物或用于商业用途。
如果你希望改成开源许可（MIT / Apache-2.0 等），改 `gradle.properties` 的 `mod_license`、`LICENSE` 与本节说明即可。

第三方依赖各自遵循其原许可：Minecraft Forge（LGPL-2.1）、Curios（MIT）、KubeJS、e33chat。
本仓库**不包含**任何第三方 jar。

---

## 致谢

* Minecraft Forge / ForgeGradle
* Curios（`top.theillusivec4`）、KubeJS（`dev.latvian.mods`）、e33chat（E33EPUS）
