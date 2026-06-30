# MC Mod Auto-Updater

一个 Minecraft 1.20.1 客户端模组，启动时自动检查并更新你的 mods。

## 工作方式

1. 用 `scan_mods.py` 扫描 mods 目录，生成 `modrinth.index.json`
2. 把 JSON 提交到本仓库根目录（或上传到任意 HTTP 主机）
3. 玩家安装本模组，启动游戏时自动拉取 JSON 并同步 mods
4. 有更新时弹窗显示差异，玩家确认后下载

## 快速开始

### 玩家

1. 从 [Releases](https://github.com/cyhqw/mc-mod-autoupdater/releases) 下载对应加载器的 jar
2. 放入 `mods/` 目录
3. 启动游戏 — 首次会自动同步，后续启动对比版本号决定是否更新

默认从本仓库根目录的 `modrinth.index.json` 拉取。换源时编辑 `config/mcmodupdater/mcmodupdater.properties` 的 `manifestUrl`。

### 整合包作者

```bash
# 扫描 mods 目录生成 JSON
python scan_mods.py --loader forge --loader-version 47.3.0

# 把生成的 modrinth.index.json 提交到一个网页
git add modrinth.index.json
git commit -m "更新整合包清单"
git push
```
指导玩家换源

每次更新 mod 后，递增 `--modpack-version`（如 `1.0` → `1.1`），玩家下次启动就会检测到更新。

## 配置

配置文件位于 `<游戏目录>/config/mcmodupdater/mcmodupdater.properties`：

| 配置项 | 默认值 | 说明 |
|---|---|---|
| `manifestUrl` | (空) | modrinth.index.json 的 URL；留空用本仓库默认 |
| `autoSyncOnLaunch` | `true` | 启动时自动检查更新 |
| `maxConcurrentDownloads` | `4` | 并发下载线程数 |
| `verifyHash` | `true` | 下载后校验 SHA1 |
| `backupOldMods` | `true` | 覆盖前备份为 `.bak` |

## scan_mods.py

扫描 mods 目录，查询 Modrinth（优先）和 CurseForge（回退），生成 `modrinth.index.json`。

```bash
python scan_mods.py --help              # 查看所有参数
python scan_mods.py -i                  # 交互式向导
python scan_mods.py --no-curseforge     # 只查 Modrinth
```

## 许可证

MIT
