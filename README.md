# MC Mod Auto-Updater

一个 **Minecraft 1.20.1** 模组，用于客户端模组的自动更新。**服务端**模组扫描自身的 `mods/` 目录，按 **Modrinth 优先、CurseForge 兜底** 的顺序查询每个模组，把结果写入一个 JSON 清单（外加一个未找到清单）。**客户端**模组在游戏启动时从配置的 URL 拉取该清单，自动同步本地 `mods/` 目录 —— 下载、更新、清理过期 jar。

> 同时提供 **Fabric** 与 **Forge** 两个加载器的构建产物，共用一套 Java 核心代码。

- **目标 Minecraft 版本：** 1.20.1
- **Java：** 17
- **支持的加载器：** Fabric（Loader 0.15.x + Fabric API 0.92.x）、Forge（47.x）
- **源站优先级：** Modrinth → CurseForge
- **模组匹配策略：** 优先读取 jar 内元数据（`fabric.mod.json` / `META-INF/mods.toml` / `mcmod.info`），文件名作为兜底
- **客户端触发时机：** 游戏启动时自动检查更新
- **许可证：** MIT
- **仓库：** https://github.com/cyhqw/mc-mod-autoupdater

---

## 工作原理

```
┌──────────────────────────────────────────────────────────────────────────┐
│  服务端（你的 MC 专用服，安装服务端模组）                                  │
│                                                                          │
│   mods/*.jar                                                              │
│      │  扫描 + 读取 jar 元数据（modId / name / version / loader）         │
│      ▼                                                                   │
│   Modrinth 查询 ──►（未找到？）──► CurseForge 查询                        │
│      │                                  （都没找到？）                    │
│      ▼                                  ▼                                 │
│   manifest.json                       missing.json                        │
│   （下载 URL、sha1、sha512、            （未在任一平台找到的模组——         │
│    游戏版本、加载器列表）                私有 / 自定义模组）                │
│                                                                          │
│   发布到一个 URL（如 GitHub Pages、S3、你自己的 CDN）                     │
└──────────────────────────────────────────────────────────────────────────┘
                                  │
                                  ▼  HTTPS GET manifest.json
┌──────────────────────────────────────────────────────────────────────────┐
│  客户端（每个玩家的 MC 客户端，安装客户端模组）                            │
│                                                                          │
│   游戏启动时：                                                            │
│   1. 从配置的 URL 拉取 manifest.json                                     │
│   2. 对每个条目：                                                         │
│        - 本地已存在且 SHA1 匹配 → 跳过                                   │
│        - 否则下载 → 校验 SHA1 → 原子重命名                               │
│   3. 可选：清理 manifest 中不存在的孤儿 jar                              │
│   4. 如有变化，提示玩家重启游戏                                          │
└──────────────────────────────────────────────────────────────────────────┘
```

---

## 远程构建（GitHub Actions）

本仓库已配置两个 GitHub Actions 工作流，**不需要本地配置 Gradle 环境**：

### 1. `build.yml` —— 持续构建

- **触发条件：** push 到 `main` / `master` 分支、提交 PR、或手动触发（`workflow_dispatch`）
- **执行内容：** 在 `ubuntu-latest` 上用 Temurin JDK 17 + Gradle 8.7 构建 `common` + `fabric` + `forge` 三个模块
- **构建产物：** 自动上传为 GitHub Actions artifacts，保留 30 天，可在工作流运行页面下载

**手动触发方式：** 仓库页面 → `Actions` 标签 → 左侧选 `Build Mods` → 右上角 `Run workflow`

### 2. `release.yml` —— 发布 Release

- **触发条件：** 推送形如 `v0.1.0` 的 tag，或手动触发并输入 tag 名
- **执行内容：** 与 `build.yml` 相同的构建流程，然后将 Fabric / Forge / common 三个 jar 上传为 GitHub Release 的资产
- **Release notes：** 自动从 commit 历史生成

**触发示例：**
```bash
git tag v0.1.0
git push origin v0.1.0
# 或在 GitHub 仓库的 Actions → Release → Run workflow 中手动触发
```

### 构建产物命名

| 模块    | 产物文件名                                       | 用途           |
| ------- | ------------------------------------------------ | -------------- |
| fabric  | `mc-mod-autoupdater-fabric-1.20.1-0.1.0.jar`     | 放入 Fabric 客户端/服务端的 `mods/` |
| forge   | `mc-mod-autoupdater-forge-1.20.1-0.1.0.jar`      | 放入 Forge 客户端/服务端的 `mods/`  |
| common  | `mc-mod-autoupdater-common-0.1.0.jar`            | 已被 Fabric/Forge jar 内嵌，通常无需单独使用 |

---

## 仓库结构

```
mc-mod-autoupdater/
├── .github/workflows/
│   ├── build.yml             # 持续构建工作流
│   └── release.yml           # tag 触发的发布工作流
├── build.gradle              # 根构建文件（为子项目应用 Java 插件）
├── settings.gradle           # 引入 common / fabric / forge 三个子项目
├── gradle.properties         # MC / Fabric / Forge 版本号
├── common/                   # 共享 Java 代码，不依赖 MC 类
│   └── src/main/java/com/cyhqw/mcmodupdater/common/
│       ├── Constants.java
│       ├── config/           # ModUpdaterConfig（.properties 配置文件）
│       ├── curseforge/       # CurseForgeClient（slug、搜索、指纹查询）
│       ├── http/             # HttpUtil（java.net.http 封装）
│       ├── manifest/         # ManifestBuilder（服务端：扫描 + 查询 + 写清单）
│       ├── model/            # Manifest / ManifestMeta / ModEntry / Missing*
│       ├── modrinth/         # ModrinthClient（slug、搜索、版本文件）
│       ├── scanner/          # ModScanner + ModMetadata（jar 元数据解析）
│       ├── syncer/           # ModSyncer（客户端：下载 + 校验 + 清理）
│       └── util/             # HashUtils / JarReader / ModLog
├── fabric/                   # Fabric 1.20.1 模组
│   ├── build.gradle          # 使用 fabric-loom 1.6
│   └── src/main/
│       ├── java/com/cyhqw/mcmodupdater/fabric/
│       │   ├── McModUpdaterFabricClient.java   # 客户端：启动时同步
│       │   ├── McModUpdaterFabricServer.java   # 服务端：/mcmodupdater generate 命令
│       │   └── Slf4jSink.java
│       └── resources/
│           ├── fabric.mod.json
│           └── pack.mcmeta
├── forge/                    # Forge 1.20.1（47.x）模组
│   ├── build.gradle          # 使用 ForgeGradle 6
│   └── src/main/
│       ├── java/com/cyhqw/mcmodupdater/forge/
│       │   ├── McModUpdaterForge.java          # @Mod 入口
│       │   ├── McModUpdaterForgeClient.java    # 客户端：启动时同步
│       │   ├── McModUpdaterForgeServer.java    # 服务端：/mcmodupdater generate 命令
│       │   ├── Log4jSink.java
│       │   └── Slf4jSink.java
│       └── resources/META-INF/mods.toml
├── docs/
│   └── manifest-schema.md    # JSON 清单结构文档
└── examples/
    ├── mcmodupdater.properties.example   # 配置文件示例
    └── manifest.json.example             # 清单文件示例
```

---

## 服务端使用流程

### 1. 安装服务端模组

从 [Releases 页面](https://github.com/cyhqw/mc-mod-autoupdater/releases) 下载与你的服务端加载器匹配的 jar（Fabric 或 Forge），放入 `<服务端>/mods/`。

### 2.（可选）配置 CurseForge API Key

CurseForge 要求免费 API Key。到 <https://console.curseforge.com/> 注册并申请一个，然后在启动服务端前设置环境变量：

```bash
export CURSEFORGE_API_KEY="你的-key"
```

如果不设置，服务端依然可用 —— 只是不会查询 CurseForge，任何 Modrinth 上没有的模组都会进 `missing.json`。

### 3. 生成清单

在服务端控制台（或 OP 聊天框）运行：

```
/mcmodupdater generate
```

输出文件位于：

```
<服务端>/config/mcmodupdater/manifest-output/manifest.json
<服务端>/config/mcmodupdater/manifest-output/missing.json
```

### 4. 发布清单

将 `manifest.json`（可选 `missing.json`）上传到任意 HTTP/HTTPS 主机。常见选择：

- **GitHub Pages** —— 推到 `gh-pages` 分支或单独的仓库
- **S3 / CloudFront** —— `aws s3 cp manifest.json s3://my-bucket/mc/manifest.json`
- **Cloudflare R2 / Netlify / Vercel** —— 丢进静态资源目录

客户端只需要 `manifest.json` 的 URL。`missing.json` 给服主自己参考，不需要发布。

### 5. 模组变更后重新生成

每次在服务端添加 / 更新 / 删除模组后，重新运行 `/mcmodupdater generate` 并重新上传清单。

---

## 客户端使用流程

### 1. 安装客户端模组

从 [Releases 页面](https://github.com/cyhqw/mc-mod-autoupdater/releases) 下载与你的客户端加载器匹配的 jar，放入 `<客户端>/mods/`。

### 2. 配置清单 URL

首次启动时，模组会自动创建 `<客户端>/config/mcmodupdater/mcmodupdater.properties`。编辑它：

```properties
# ----- 服务端配置 -----
server.outputSubdir=manifest-output
server.extraScanDirs=
server.scanDisabled=false
server.skipCurseForge=false
server.skipModrinth=false

# ----- 客户端配置 -----
client.manifestUrl=https://your-host.example.com/mc/manifest.json
client.autoSyncOnLaunch=true
client.removeOrphans=false
client.backupOldMods=true
client.maxConcurrentDownloads=4
client.verifyHash=true
client.promptRestart=true
```

重启客户端。模组会：

1. 从 `client.manifestUrl` 拉取清单
2. 对每个条目：本地已存在且 SHA1 匹配则跳过；否则下载并校验
3. 可选清理孤儿 jar（`client.removeOrphans=true` 时）
4. 在聊天框显示同步摘要
5. 如有变化，提示重启

---

## 清单 JSON 结构

完整结构详见 [`docs/manifest-schema.md`](docs/manifest-schema.md)。简要示例：

```jsonc
{
  "meta": {
    "schema_version": 1,
    "generated_at": "2026-06-27T15:37:08Z",
    "minecraft_version": "1.20.1",
    "mod_loader": "fabric",
    "mod_count": 12,
    "generator": "mc-mod-autoupdater 0.1.0"
  },
  "mods": [
    {
      "id": "fabric-api",
      "name": "Fabric API",
      "source": "modrinth",
      "source_url": "https://modrinth.com/mod/fabric-api",
      "download_url": "https://cdn.modrinth.com/.../fabric-api-0.92.2.jar",
      "filename": "fabric-api-0.92.2+1.20.1.jar",
      "version": "0.92.2+1.20.1",
      "file_size": 1234567,
      "sha1": "abcdef0123456789...",
      "sha512": "0123456789abcdef...",
      "game_versions": ["1.20", "1.20.1"],
      "loaders": ["fabric", "quilt"]
    }
    // ...
  ]
}
```

配套的 `missing.json`：

```jsonc
{
  "meta": { ..., "missing_count": 2 },
  "missing": [
    {
      "filename": "my-private-mod-1.0.jar",
      "mod_id": "my_private_mod",
      "version": "1.0",
      "sha1": "abcdef0123456789...",
      "reason": "not_found_on_modrinth_or_curseforge",
      "attempts": [
        { "source": "modrinth:slug:my_private_mod", "ok": false, "detail": "no match" },
        { "source": "curseforge", "ok": false, "detail": "no API key (CURSEFORGE_API_KEY env var not set)" }
      ]
    }
  ]
}
```

---

## 配置项参考

| 配置项                              | 默认值              | 说明                                                              |
| ----------------------------------- | ------------------- | ----------------------------------------------------------------- |
| `server.outputSubdir`               | `manifest-output`   | `config/mcmodupdater/` 下写入清单的子目录                         |
| `server.extraScanDirs`              | (空)                | 除 `mods/` 外额外扫描的目录，逗号分隔                              |
| `server.scanDisabled`               | `false`             | 是否包含 `.jar.disabled` 文件                                     |
| `server.skipCurseForge`             | `false`             | 完全跳过 CurseForge 查询                                          |
| `server.skipModrinth`               | `false`             | 完全跳过 Modrinth 查询（很少用）                                  |
| `client.manifestUrl`                | (空)                | **客户端必填。** 指向 manifest.json 的 URL                        |
| `client.autoSyncOnLaunch`           | `true`              | 客户端启动时自动同步                                              |
| `client.removeOrphans`              | `false`             | 删除本地清单中不存在的模组                                        |
| `client.backupOldMods`              | `true`              | 覆盖 / 删除时先备份为 `.bak`                                      |
| `client.maxConcurrentDownloads`     | `4`                 | 并发下载线程数                                                    |
| `client.verifyHash`                 | `true`              | 下载后校验 SHA1，不匹配则拒绝                                     |
| `client.promptRestart`              | `true`              | 有变化时提示玩家重启                                              |

---

## 为什么 Modrinth 优先？

- Modrinth 的 API **公开且无需 Key** 即可读取 —— 零配置可用
- Modrinth 的 slug 形式项目 URL 稳定可读
- CurseForge 作为兜底，因为需要 API Key 且限流更严

如果你想反转顺序（CF 优先），直接修改 [`common/src/main/java/com/cyhqw/mcmodupdater/common/manifest/ManifestBuilder.java`](common/src/main/java/com/cyhqw/mcmodupdater/common/manifest/ManifestBuilder.java) 中 `resolveOne` 方法内的查询顺序即可。

---

## 已知限制

- **初始化匹配未使用哈希查询。** CurseForge 的指纹 API 使用非标准的 murmur hash，目前尚未实现。Slug + 名称搜索对绝大多数公开模组已足够。
- **不支持版本锁定覆盖。** 服务端总是选择 `gameVersion` + `loader` 匹配的最新文件。如需锁定旧版本，生成后手动编辑 `manifest.json`。
- **客户端无法在玩家进入世界前显示 GUI。** 启动时同步在后台线程运行，玩家进入世界后通过聊天框反馈。失败的同步会写入游戏日志。
- **不验证清单签名。** 我们对下载内容做 SHA1 校验，但不验证清单本身是否来自可信发布者。请使用 HTTPS 并保护好你的清单主机。

---

## 本地构建（可选）

如果你想本地构建而不依赖 GitHub Actions：

```bash
# 前置：JDK 17
java -version  # 应显示 17.x

# 生成 Gradle wrapper
gradle wrapper --gradle-version 8.7 --distribution-type bin

# 构建全部
./gradlew clean build

# 单独构建 Fabric 或 Forge
./gradlew :fabric:build
./gradlew :forge:build
```

构建产物位于：

- `fabric/build/libs/mc-mod-autoupdater-fabric-1.20.1-0.1.0.jar`
- `forge/build/libs/mc-mod-autoupdater-forge-1.20.1-0.1.0.jar`

---

## 贡献

欢迎提 PR。提交前请运行 `./gradlew check`。

---

## 许可证

MIT —— 详见 [LICENSE](LICENSE)。
