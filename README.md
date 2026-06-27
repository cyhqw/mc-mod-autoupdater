# MC Mod Auto-Updater

一个 **Minecraft 1.20.1 客户端模组**，从给定 URL 拉取 **Modrinth 整合包格式的 `modrinth.index.json`**，在游戏启动时自动同步本地 `mods/` 目录。支持周期性同步、SHA1 校验、并发下载、备份、黑白名单。

> 同时提供 **Fabric** 和 **Forge** 两个加载器的构建产物，共用一套 Java 核心代码。**纯客户端运行**，不在专用服上加载。

- **目标 Minecraft 版本：** 1.20.1
- **Java：** 17
- **加载器：** Fabric（Loader 0.15.x + Fabric API 0.92.x）、Forge（47.x）
- **JSON 格式：** 严格遵循 [Modrinth 整合包格式定义](https://docs.modrinth.com/docs/modpacks/format_definition/)
- **触发时机：** 游戏启动时自动检查；可选周期性同步
- **CurseForge：** 默认关闭（仅从 Modrinth CDN 下载）
- **许可证：** MIT
- **仓库：** https://github.com/cyhqw/mc-mod-autoupdater

---

## 工作原理

```
┌─────────────────────────────────────────────────────────────────────┐
│  整合包作者 / 服主                                                    │
│                                                                     │
│   1. 用 Modrinth 整合包工具（pack-toml / mrpack）或手动生成          │
│      modrinth.index.json                                            │
│      （包含每个 mod 的 path / sha1 / sha512 / downloads URL）        │
│   2. 上传到任意 HTTP/HTTPS 主机（GitHub Pages、S3、Cloudflare R2...） │
└─────────────────────────────────────────────────────────────────────┘
                                │
                                ▼  HTTPS GET modrinth.index.json
┌─────────────────────────────────────────────────────────────────────┐
│  玩家客户端（安装本模组）                                             │
│                                                                     │
│   游戏启动时：                                                       │
│   1. 从 manifestUrl 拉取 modrinth.index.json                        │
│   2. 过滤出 path 以 mods/ 开头、env.client=required/optional 的条目  │
│   3. 对每个条目：                                                    │
│        - 本地已存在且 SHA1 匹配 → 跳过                              │
│        - 否则从 downloads URL 列表下载 → 校验 SHA1 → 原子重命名     │
│        - 覆盖前可选备份为 .bak                                       │
│   4. 若 removeOrphans=true，删除本地 manifest 中不存在的 .jar       │
│   5. 在聊天框反馈同步结果；有变化时提示玩家重启                      │
│                                                                     │
│   若 periodicSyncMinutes>0，则后台按周期重复同步。                  │
└─────────────────────────────────────────────────────────────────────┘
```

---

## 远程构建（GitHub Actions）

本仓库已配置两个 GitHub Actions 工作流，**不需要本地配置 Gradle 环境**：

### 1. `build.yml` —— 持续构建

- **触发条件：** push 到 `main` 分支、提交 PR、或手动触发
- **执行内容：** 在 `ubuntu-latest` 上用 Temurin JDK 17 + Gradle 8.8 构建 `common` + `fabric` + `forge` 三个模块
- **构建产物：** 自动上传为 GitHub Actions artifacts，保留 30 天

### 2. `release.yml` —— 发布 Release

- **触发条件：** 推送形如 `v0.2.0` 的 tag，或手动触发并输入 tag 名
- **执行内容：** 与 `build.yml` 相同的构建流程，然后将 Fabric / Forge / common 三个 jar 上传为 GitHub Release 的资产

**触发示例：**
```bash
git tag v0.2.0
git push origin v0.2.0
```

### 构建产物命名

| 模块    | 产物文件名                                       | 用途                                  |
| ------- | ------------------------------------------------ | ------------------------------------- |
| fabric  | `mc-mod-autoupdater-fabric-1.20.1-0.2.0.jar`     | 放入 Fabric 客户端的 `mods/`          |
| forge   | `mc-mod-autoupdater-forge-1.20.1-0.2.0.jar`      | 放入 Forge 客户端的 `mods/`           |
| common  | `mc-mod-autoupdater-common-0.2.0.jar`            | 已被 Fabric/Forge jar 内嵌，无需单独用 |

---

## 仓库结构

```
mc-mod-autoupdater/
├── .github/workflows/
│   ├── build.yml             # 持续构建工作流
│   └── release.yml           # tag 触发的发布工作流
├── build.gradle              # 根构建文件
├── settings.gradle           # 引入 common / fabric / forge 三个子项目
├── gradle.properties         # MC / Fabric / Forge 版本号
├── common/                   # 共享 Java 代码，不依赖 MC 类
│   └── src/main/java/com/cyhqw/mcmodupdater/common/
│       ├── Constants.java
│       ├── config/           # ModUpdaterConfig（精简的 .properties 配置）
│       ├── http/             # HttpUtil（java.net.http 封装 + 重试）
│       ├── modrinth/         # ModrinthIndex / ModrinthFile（modrinth.index.json 数据模型）
│       ├── syncer/           # ModSyncer（同步核心逻辑）
│       └── util/             # HashUtils / ModLog
├── fabric/                   # Fabric 1.20.1 客户端模组
│   ├── build.gradle
│   └── src/main/
│       ├── java/com/cyhqw/mcmodupdater/fabric/
│       │   ├── McModUpdaterFabricClient.java   # 客户端入口
│       │   └── Slf4jSink.java
│       └── resources/
│           ├── fabric.mod.json                 # environment: "client"
│           └── pack.mcmeta
├── forge/                    # Forge 1.20.1（47.x）客户端模组
│   ├── build.gradle
│   └── src/main/
│       ├── java/com/cyhqw/mcmodupdater/forge/
│       │   ├── McModUpdaterForge.java          # @Mod 入口
│       │   ├── McModUpdaterForgeClient.java    # 客户端事件处理器
│       │   └── Log4jSink.java
│       └── resources/META-INF/mods.toml        # side: "CLIENT"
└── examples/
    ├── mcmodupdater.properties.example         # 配置文件示例
    └── modrinth.index.json.example             # modrinth.index.json 示例
```

---

## 使用流程

### 1. 安装客户端模组

从 [Releases 页面](https://github.com/cyhqw/mc-mod-autoupdater/releases) 下载与你的客户端加载器匹配的 jar（Fabric 或 Forge），放入 `<客户端>/mods/`。

### 2. 准备 modrinth.index.json

**方式 A：用 Modrinth 官方工具生成**

用 [packwiz](https://packwiz.infra.link/) 或 Modrinth 的 [mrpack CLI](https://github.com/modrinth/modpackdl) 生成标准 modrinth.index.json。

**方式 B：手动编写**

参考 [`examples/modrinth.index.json.example`](examples/modrinth.index.json.example)。每个 mod 条目需要：

- `path` — 必须以 `mods/` 开头，例如 `mods/sodium.jar`
- `hashes.sha1` + `hashes.sha512` — 用于校验
- `downloads` — 一个或多个下载 URL，按顺序尝试
- `fileSize` — 可选
- `env.client` — `"required"` / `"optional"` / `"unsupported"`，控制是否同步到客户端

### 3. 发布 modrinth.index.json

上传到任意 HTTP/HTTPS 主机：

- **GitHub Pages** —— 推到 `gh-pages` 分支或单独的仓库
- **S3 / CloudFront / Cloudflare R2** —— 静态资源目录
- **Netlify / Vercel** —— 拖入 static 目录

记下 URL，例如 `https://your-host.example.com/mc/modrinth.index.json`。

### 4. 配置客户端

首次启动时，模组自动创建 `<客户端>/config/mcmodupdater/mcmodupdater.properties`。编辑它：

```properties
manifestUrl=https://your-host.example.com/mc/modrinth.index.json
autoSyncOnLaunch=true
periodicSyncMinutes=0
verifyHash=true
backupOldMods=true
```

重启客户端。模组会：

1. 从 `manifestUrl` 拉取 modrinth.index.json
2. 对每个 mods/ 下的条目：本地已存在且 SHA1 匹配则跳过；否则下载并校验
3. 可选清理孤儿 jar（`removeOrphans=true` 时）
4. 在聊天框显示同步摘要
5. 如有变化，提示重启

### 5. 后续更新

整合包作者更新 `modrinth.index.json` 后，玩家只需重启游戏（或开启 `periodicSyncMinutes` 让模组自动周期检查）即可同步到最新版本。

---

## modrinth.index.json 格式

完整规范见 [Modrinth 文档](https://docs.modrinth.com/docs/modpacks/format_definition/)。简要结构：

```jsonc
{
  "formatVersion": 1,
  "game": "minecraft",
  "versionId": "1.0",
  "name": "My Modpack",
  "files": [
    {
      "path": "mods/fabric-api.jar",
      "hashes": {
        "sha1": "abcdef0123456789abcdef0123456789abcdef01",
        "sha512": "0123456789abcdef..."
      },
      "downloads": [
        "https://cdn.modrinth.com/data/P7dR8mSH/versions/.../fabric-api-0.92.2+1.20.1.jar"
      ],
      "fileSize": 2031582,
      "env": {
        "client": "required",
        "server": "optional"
      }
    }
  ],
  "dependencies": {
    "minecraft": "1.20.1",
    "fabric-loader": "0.15.11"
  }
}
```

本模组的处理规则：

- **`formatVersion`** 必须为 `1`
- **`files[]`** 仅处理 `path` 以 `mods/` 开头的条目；其它路径（如 `config/`、`kubejs/`）目前不处理
- **`env.client`** 为 `"required"` 或 `"optional"`（或字段缺失）时同步；为 `"unsupported"` 时跳过
- **`hashes.sha1`** 用于校验；若 `verifyHash=true` 则缺失 sha1 的条目会被拒绝
- **`downloads[]`** 按列表顺序尝试，全部失败才算该文件下载失败
- **`dependencies`** 仅用于日志展示，不影响同步逻辑（玩家客户端自行保证 MC / 加载器版本）

---

## 配置项参考

完整示例见 [`examples/mcmodupdater.properties.example`](examples/mcmodupdater.properties.example)。配置文件路径：`<游戏目录>/config/mcmodupdater/mcmodupdater.properties`。

| 配置项                       | 默认值     | 说明                                                          |
| ---------------------------- | ---------- | ------------------------------------------------------------- |
| `manifestUrl`                | (空)       | **必填。** 指向 modrinth.index.json 的 URL                    |
| `autoSyncOnLaunch`           | `true`     | 客户端启动时自动同步                                          |
| `periodicSyncMinutes`        | `0`        | 周期同步间隔（分钟）；`0` 禁用，`>0` 每 N 分钟检查一次        |
| `modsDir`                    | (空)       | 自定义 mods 目录路径；为空用默认的 `mods/`                    |
| `removeOrphans`              | `false`    | 删除本地 manifest 中不存在的 mod                              |
| `backupOldMods`              | `true`     | 覆盖 / 删除时先备份为 `.bak`                                  |
| `maxConcurrentDownloads`     | `4`        | 并发下载线程数                                                |
| `verifyHash`                 | `true`     | 下载后校验 SHA1，不匹配则拒绝                                 |
| `httpTimeoutMs`              | `15000`    | HTTP 请求超时（毫秒）                                         |
| `maxRetries`                 | `2`        | 单文件下载失败时的最大重试次数                                |
| `promptRestart`              | `true`     | 有变化时提示玩家重启                                          |
| `skipMods`                   | (空)       | 黑名单：跳过指定 mod 文件名（逗号分隔，不带路径）             |
| `onlyMods`                   | (空)       | 白名单：仅同步这些 mod 文件名（逗号分隔）；为空则不过滤       |

### 常见配置场景

**场景 1：仅同步白名单内的几个核心 mod**

```properties
manifestUrl=https://example.com/mc/modrinth.index.json
onlyMods=fabric-api.jar,sodium.jar,jei.jar
```

**场景 2：每 30 分钟检查一次 manifest 更新**

```properties
manifestUrl=https://example.com/mc/modrinth.index.json
autoSyncOnLaunch=true
periodicSyncMinutes=30
```

**场景 3：严格匹配 manifest，删除本地多余 mod**

```properties
manifestUrl=https://example.com/mc/modrinth.index.json
removeOrphans=true
backupOldMods=true
```

---

## 已知限制

- **仅同步 mods/ 目录。** Modrinth 整合包格式支持 `config/`、`kubejs/` 等任意路径，但本模组目前只处理 `mods/` 下的文件。
- **不验证 manifest 签名。** 仅做下载内容的 SHA1 校验，不验证 manifest 本身是否来自可信发布者。请使用 HTTPS 并保护好你的 manifest 主机。
- **客户端无法在玩家进入世界前显示 GUI。** 启动时同步在后台线程运行，玩家进入世界后通过聊天框反馈。失败的同步会写入游戏日志。
- **周期同步无法自动重启游戏。** 若 manifest 在游戏运行期间更新并下载了新文件，玩家仍需手动重启游戏才能让新 mod 生效（模组会在聊天框提示）。

---

## 本地构建（可选）

如果你想本地构建而不依赖 GitHub Actions：

```bash
# 前置：JDK 17
java -version  # 应显示 17.x

# 生成 Gradle wrapper
gradle wrapper --gradle-version 8.8 --distribution-type bin

# 构建全部
./gradlew clean build

# 单独构建 Fabric 或 Forge
./gradlew :fabric:build
./gradlew :forge:build
```

构建产物位于：

- `fabric/build/libs/mc-mod-autoupdater-fabric-1.20.1-0.2.0.jar`
- `forge/build/libs/mc-mod-autoupdater-forge-1.20.1-0.2.0.jar`

---

## 贡献

欢迎提 PR。提交前请确保 `./gradlew build` 通过（或 GitHub Actions CI 绿色）。

---

## 许可证

MIT —— 详见 [LICENSE](LICENSE)。
