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
│   1. 把所有 mod 放进 mods/ 目录                                      │
│   2. 运行本仓库的 scan_mods.py                                       │
│      → 自动计算每个 jar 的 SHA1/SHA512                               │
│      → 通过 Modrinth API 反查下载 URL                                │
│      → 输出 modrinth.index.json（含 versionId 字段）                 │
│   3. 每次更新 mod 后递增 versionId（如 1.0 → 1.1）                    │
│   4. 上传到任意 HTTP/HTTPS 主机（GitHub Pages、S3、Cloudflare R2...） │
└─────────────────────────────────────────────────────────────────────┘
                                │
                                ▼  HTTPS GET modrinth.index.json
┌─────────────────────────────────────────────────────────────────────┐
│  玩家客户端（游戏加载之前，阻塞执行）                                 │
│                                                                     │
│   1. 拉取 modrinth.index.json                                       │
│   2. 对比 manifest.versionId 与本地 currentVersionId                │
│   3. 版本相同 → 静默继续加载                                         │
│   4. 首次运行（无 local versionId） → 静默直接同步                   │
│   5. 发现新版本 → 弹 Swing 对话框，玩家确认后下载                     │
│   6. 同步完成 → 弹结果对话框，玩家关闭后游戏继续加载                  │
│   7. 自动回写 currentVersionId 到配置文件                            │
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
├── scan_mods.py              # Python 脚本：扫描 mods/ 生成 modrinth.index.json
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

**方式 A（推荐）：用本仓库的 `scan_mods.py` 脚本**

把所有 mod 放进一个 `mods/` 目录，在仓库根目录运行：

```bash
python scan_mods.py
```

脚本会自动：
- 递归扫描 `mods/` 下所有 `.jar`
- 计算每个 jar 的 SHA1 和 SHA512
- 通过 Modrinth API 用 SHA1 反查下载 URL（`GET /version_file/{sha1}?algorithm=sha1`）
- 生成 `modrinth.index.json`（符合 Modrinth 整合包格式）
- 未在 Modrinth 上找到的 mod 写入 `missing.txt`

常用参数示例：

```bash
# 指定加载器版本（写入 dependencies）
python scan_mods.py --loader fabric --loader-version 0.15.11

# 自定义整合包信息
python scan_mods.py --modpack-name "My Pack" --modpack-version 1.0 --mc-version 1.20.1

# 不递归子目录、同时扫描 .jar.disabled
python scan_mods.py --no-recursive --include-disabled

# 自定义输入输出路径
python scan_mods.py --mods-dir /opt/server/mods --output /var/www/mc/modrinth.index.json
```

> 脚本仅用 Python 3.8+ 标准库，**无需 pip install**。完整参数说明见 [脚本章节](#scan_modspy-参数参考) 或运行 `python scan_mods.py --help`。

**方式 B：用 Modrinth 官方工具**

用 [packwiz](https://packwiz.infra.link/) 或 Modrinth 的 [mrpack CLI](https://github.com/modrinth/modpackdl) 生成标准 modrinth.index.json。

**方式 C：手动编写**

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
verifyHash=true
backupOldMods=true
```

重启客户端。模组会在**游戏加载之前**执行以下流程（阻塞，加载屏幕暂停）：

1. 从 `manifestUrl` 拉取 modrinth.index.json
2. 对比 manifest 中的 `versionId` 与本地 `currentVersionId`（自动维护）
3. **版本相同** → 静默继续加载
4. **首次运行**（本地无 `currentVersionId`）→ **静默直接同步**，不弹"发现新版本"对话框，同步完成后弹一次结果对话框
5. **发现新版本** → 弹 Swing 对话框告知"本地 vX → 远端 vY"，玩家点"确定"后下载，点"取消"跳过本次更新
6. **拉取失败** → 弹警告对话框告知，使用本地模组继续加载
7. 同步完成后弹"下载 N 个、跳过 N 个、失败 N 个"结果对话框，玩家关闭后游戏才继续加载

> 注意：模组会在配置文件中自动维护 `currentVersionId` 字段，玩家通常无需手动编辑。

### 5. 后续更新

整合包作者更新 modrinth.index.json 时，记得递增 `versionId`（例如 `1.0` → `1.1`）。玩家下次启动游戏时模组会检测到版本变化并弹窗提示更新。

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

## `scan_mods.py` 参数参考

脚本位于仓库根目录 [`scan_mods.py`](scan_mods.py)，仅依赖 Python 3.8+ 标准库。

### 命令行参数

| 参数                      | 默认值                  | 说明                                                            |
| ------------------------- | ----------------------- | --------------------------------------------------------------- |
| `--mods-dir`              | `mods`                  | 要扫描的 mods 目录路径                                          |
| `--output`, `-o`          | `modrinth.index.json`   | 输出文件路径                                                    |
| `--mc-version`            | `1.20.1`                | Minecraft 版本，写入 `dependencies.minecraft`                   |
| `--modpack-name`          | `My Modpack`            | 整合包名称，写入 `name`                                         |
| `--modpack-version`       | `1.0`                   | 整合包版本号，写入 `versionId`                                  |
| `--loader`                | (空)                    | 加载器类型：`fabric` / `quilt` / `forge` / `neoforge`，与 `--loader-version` 同时使用 |
| `--loader-version`        | (空)                    | 加载器版本，与 `--loader` 同时使用                              |
| `--client-env`            | `required`              | 每个文件 `env.client` 的默认值：`required` / `optional` / `unsupported` |
| `--server-env`            | `optional`              | 每个文件 `env.server` 的默认值                                  |
| `--include-disabled`      | (flag)                  | 同时扫描 `.jar.disabled` 文件                                   |
| `--no-recursive`          | (flag)                  | 不递归子目录，仅扫描 `--mods-dir` 顶层                          |
| `--missing-output`        | `missing.txt`           | 未找到 mod 列表的输出文件；留空则不写                           |

> `--loader` 和 `--loader-version` 必须同时指定或同时省略。

### 退出码

| 退出码 | 含义                                                              |
| ------ | ----------------------------------------------------------------- |
| `0`    | 全部 mod 都成功在 Modrinth 上找到，`modrinth.index.json` 已生成  |
| `1`    | 严重错误（mods 目录不存在、JSON 写入失败等）                      |
| `2`    | 部分文件未在 Modrinth 上找到，`modrinth.index.json` 仍已生成      |

### `missing.txt` 格式

未找到的 mod 以 TSV（制表符分隔）写入：

```
# 未在 Modrinth 上找到的 mod 列表
# 格式: filename <TAB> sha1 <TAB> path <TAB> reason

my-private-mod.jar   a1b2c3d4...   mods/my-private-mod.jar   not_found_on_modrinth
```

`reason` 可能的值：
- `not_found_on_modrinth` — Modrinth API 返回 404，该 SHA1 不在 Modrinth 数据库中
- `sha1_not_in_version_files` — 找到了 version 但其 `files[]` 中没有匹配的 SHA1（罕见，通常意味着该 jar 是私有构建）
- `no_download_url` — version 响应中匹配的文件条目缺少 `url` 字段
- `exception: <msg>` — 网络/解析异常

### 工作原理

```
对每个 .jar 文件:
  1. 计算 SHA1 和 SHA512
  2. GET https://api.modrinth.com/v2/version_file/{sha1}?algorithm=sha1
       - 200 → 返回 version JSON
       - 404 → 该 jar 不在 Modrinth 上，记入 missing.txt
  3. 在 version.files[] 中找到 hashes.sha1 匹配的条目
  4. 提取该条目的 url 字段作为 downloads[0]
  5. 组装 Modrinth file 条目写入 modrinth.index.json
```

> Modrinth 的 SHA1 反查 API 是最准确的匹配方式 —— 不依赖文件名或 mod id，直接以 jar 内容哈希为准。即使你重命名了文件，只要内容相同就能正确匹配。

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
