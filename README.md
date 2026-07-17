# MC Mod Auto-Updater（Kerong 服务器专用版）

一个 Minecraft 1.20.1 客户端模组，启动时自动检查并更新你的 mods。

> 这是 **Kerong 服务器专用版**（`feat/kerong-format-compat` 分支）：开箱即用，默认从 Kerong 官网拉取更新。
> 通用版（仅标准 Modrinth 格式、需自行配置清单 URL）见 [`main` 分支](https://github.com/cyhqw/mc-mod-autoupdater/tree/main)。

## 工作方式

1. Kerong 整合包服务器托管 `version.json` 版本清单与模组文件
2. 玩家安装本模组，启动游戏时自动从 Kerong 官网拉取清单并同步 mods
3. 有更新时弹窗显示差异，玩家确认后下载

无需任何配置即可使用 —— `manifestUrl` 默认指向 `https://kerong.xin/modpack/version.json`。

## 快速开始

### 玩家

1. 从 [Releases](https://github.com/cyhqw/mc-mod-autoupdater/releases) 下载对应加载器的 jar
2. 放入 `mods/` 目录
3. 启动游戏 — 首次会自动同步，后续启动对比版本号决定是否更新

默认从 Kerong 官网拉取更新。如需切换到其它 Kerong 服务器，编辑 `config/mcmodupdater/mcmodupdater.properties` 的 `manifestUrl`。

## 支持的清单格式

本版同时支持两种清单格式，模组会自动检测：

### Kerong 格式（默认）

科融整合包更新器使用的 `version.json`（参见 [kerong-modpack-updater](https://github.com/suzhe014/kerong-modpack-updater)）。下载 URL 由模组按 `{manifest_url}/files/{relativePath}` 构造，并对路径逐段百分号编码以支持中文/空格文件名。

| 方面 | Kerong 格式 | Modrinth 格式 |
|------|-------------|---------------|
| 文件名 | `version.json` | `modrinth.index.json` |
| 版本字段 | `version` (int) | `versionId` (String) |
| 显示名 | `versionName` | `name` |
| 文件列表 | 分三组：`modFiles[]` / `configFiles[]` / `resourceFiles[]` | 扁平 `files[]` |
| 哈希 | `md5` | `hashes.sha1` / `hashes.sha512` |
| 下载 URL | 由模组构造 | `downloads[]`（直接 URL） |

**注意**：Kerong 的 `configFiles` 和 `resourceFiles` 会适配进内部文件列表，但当前同步机制仅管理 `mods/` 下的 `.jar` 文件，这些条目会被自然跳过。

### Modrinth 格式

标准 Modrinth 整合包格式（含 `formatVersion` 字段的 `modrinth.index.json`）。将 `manifestUrl` 指向此类清单即可使用。

## 配置

配置文件位于 `<游戏目录>/config/mcmodupdater/mcmodupdater.properties`：

| 配置项 | 默认值 | 说明 |
|---|---|---|
| `manifestUrl` | Kerong 官网 | 整合包清单 URL；留空用 Kerong 官网默认（`https://kerong.xin/modpack/version.json`） |
| `autoSyncOnLaunch` | `true` | 启动时自动检查更新 |
| `maxConcurrentDownloads` | `4` | 并发下载线程数 |
| `verifyHash` | `true` | 下载后校验哈希（Kerong 用 MD5，Modrinth 用 SHA1） |
| `backupOldMods` | `true` | 覆盖前备份为 `.bak` |

## 许可证

MIT
