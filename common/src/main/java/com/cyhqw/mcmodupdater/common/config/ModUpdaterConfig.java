package com.cyhqw.mcmodupdater.common.config;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Properties;
import java.util.Set;

/**
 * 共享配置。持久化为 {@code config/mcmodupdater/mcmodupdater.properties}。
 *
 * <p>服务端字段在运行 {@code /mcmodupdater generate} 时生效；
 * 客户端字段在每次启动同步时生效。修改后可执行
 * {@code /mcmodupdater reload-config}（服务端）或重启客户端（客户端）使其生效。</p>
 */
public final class ModUpdaterConfig {

    // ------------------------------------------------------------------
    // 服务端配置（仅在运行 /mcmodupdater generate 时使用）
    // ------------------------------------------------------------------

    /** 清单写入的子目录（位于 config/mcmodupdater/ 下）。可被 {@link #outputDir} 覆盖。 */
    public String outputSubdir = "manifest-output";

    /**
     * 清单写入的绝对目录。若设置（非空），优先于 {@link #outputSubdir}。
     * 便于直接指向 Web 服务器根目录（如 /var/www/mc/ 或 nginx 静态目录）。
     */
    public String outputDir = "";

    /** 除 mods/ 外额外扫描的目录，逗号分隔。可以是绝对路径或相对于游戏目录的路径。 */
    public String extraScanDirs = "";

    /** 是否递归扫描子目录。 */
    public boolean scanRecursively = true;

    /** 是否包含 .jar.disabled 文件。 */
    public boolean scanDisabled = false;

    /** 目标 Minecraft 版本（写入 manifest.meta.minecraft_version 并用于过滤）。 */
    public String minecraftVersion = "1.20.1";

    /** 目标加载器（fabric / quilt / forge / neoforge）。 */
    public String modLoader = "fabric";

    /** 是否跳过 CurseForge 查询。 */
    public boolean skipCurseForge = false;

    /** 是否跳过 Modrinth 查询。 */
    public boolean skipModrinth = false;

    /** CurseForge API Key。若为空则回退到环境变量 CURSEFORGE_API_KEY。 */
    public String curseForgeApiKey = "";

    /** 服务端 HTTP 请求超时（毫秒）。0 表示使用默认值。 */
    public int serverHttpTimeoutMs = 15000;

    /** 服务端单个模组查询失败时的最大重试次数。 */
    public int serverMaxRetries = 2;

    // ------------------------------------------------------------------
    // 客户端配置（在每次启动同步时使用）
    // ------------------------------------------------------------------

    /** 指向 manifest.json 的 URL（HTTP/HTTPS）。客户端必填。 */
    public String manifestUrl = "";

    /** 启动时自动同步。 */
    public boolean autoSyncOnLaunch = true;

    /**
     * 周期性同步间隔（分钟）。0 = 禁用；&gt;0 = 每 N 分钟检查一次 manifest 是否更新。
     * 默认禁用，避免多人联机时网络压力。
     */
    public int periodicSyncMinutes = 0;

    /** 期望的 Minecraft 版本。若 manifest 不匹配则记录警告但仍同步。留空则不校验。 */
    public String expectedMinecraftVersion = "";

    /** 期望的加载器。若 manifest 不匹配则记录警告但仍同步。留空则不校验。 */
    public String expectedModLoader = "";

    /** 自定义 mods 目录路径（绝对或相对游戏目录）。为空则用默认的 mods/。 */
    public String modsDir = "";

    /** 删除本地清单中不存在的模组（"干净安装"模式）。 */
    public boolean removeOrphans = false;

    /** 覆盖 / 删除前备份为 .bak。 */
    public boolean backupOldMods = true;

    /** 并发下载线程数。 */
    public int maxConcurrentDownloads = 4;

    /** 下载后校验 SHA1，不匹配则拒绝。 */
    public boolean verifyHash = true;

    /** 客户端 HTTP 请求超时（毫秒）。0 表示使用默认值。 */
    public int clientHttpTimeoutMs = 15000;

    /** 客户端单文件下载失败时的最大重试次数。 */
    public int clientMaxRetries = 2;

    /** 有变化时提示玩家重启。 */
    public boolean promptRestart = true;

    /** 黑名单：跳过指定 mod id（逗号分隔）。这些 mod 不会被下载/更新。 */
    public String skipMods = "";

    /** 白名单：仅同步这些 mod id（逗号分隔）。为空则不过滤。 */
    public String onlyMods = "";

    // ------------------------------------------------------------------
    // 加载 / 保存
    // ------------------------------------------------------------------

    public static ModUpdaterConfig load(Path configPath) {
        ModUpdaterConfig c = new ModUpdaterConfig();
        if (!Files.exists(configPath)) {
            return c;
        }
        Properties props = new Properties();
        try (var in = Files.newInputStream(configPath)) {
            props.load(in);
        } catch (IOException e) {
            return c;
        }

        // 服务端
        c.outputSubdir = props.getProperty("server.outputSubdir", c.outputSubdir);
        c.outputDir = props.getProperty("server.outputDir", c.outputDir);
        c.extraScanDirs = props.getProperty("server.extraScanDirs", c.extraScanDirs);
        c.scanRecursively = parseBool(props, "server.scanRecursively", c.scanRecursively);
        c.scanDisabled = parseBool(props, "server.scanDisabled", c.scanDisabled);
        c.minecraftVersion = props.getProperty("server.minecraftVersion", c.minecraftVersion);
        c.modLoader = props.getProperty("server.modLoader", c.modLoader);
        c.skipCurseForge = parseBool(props, "server.skipCurseForge", c.skipCurseForge);
        c.skipModrinth = parseBool(props, "server.skipModrinth", c.skipModrinth);
        c.curseForgeApiKey = props.getProperty("server.curseForgeApiKey", c.curseForgeApiKey);
        c.serverHttpTimeoutMs = parseInt(props, "server.httpTimeoutMs", c.serverHttpTimeoutMs);
        c.serverMaxRetries = parseInt(props, "server.maxRetries", c.serverMaxRetries);

        // 客户端
        c.manifestUrl = props.getProperty("client.manifestUrl", c.manifestUrl);
        c.autoSyncOnLaunch = parseBool(props, "client.autoSyncOnLaunch", c.autoSyncOnLaunch);
        c.periodicSyncMinutes = parseInt(props, "client.periodicSyncMinutes", c.periodicSyncMinutes);
        c.expectedMinecraftVersion = props.getProperty("client.expectedMinecraftVersion", c.expectedMinecraftVersion);
        c.expectedModLoader = props.getProperty("client.expectedModLoader", c.expectedModLoader);
        c.modsDir = props.getProperty("client.modsDir", c.modsDir);
        c.removeOrphans = parseBool(props, "client.removeOrphans", c.removeOrphans);
        c.backupOldMods = parseBool(props, "client.backupOldMods", c.backupOldMods);
        c.maxConcurrentDownloads = parseInt(props, "client.maxConcurrentDownloads", c.maxConcurrentDownloads);
        c.verifyHash = parseBool(props, "client.verifyHash", c.verifyHash);
        c.clientHttpTimeoutMs = parseInt(props, "client.httpTimeoutMs", c.clientHttpTimeoutMs);
        c.clientMaxRetries = parseInt(props, "client.maxRetries", c.clientMaxRetries);
        c.promptRestart = parseBool(props, "client.promptRestart", c.promptRestart);
        c.skipMods = props.getProperty("client.skipMods", c.skipMods);
        c.onlyMods = props.getProperty("client.onlyMods", c.onlyMods);
        return c;
    }

    public void save(Path configPath) throws IOException {
        Properties props = new Properties();

        // 服务端
        props.setProperty("server.outputSubdir", outputSubdir);
        props.setProperty("server.outputDir", outputDir);
        props.setProperty("server.extraScanDirs", extraScanDirs);
        props.setProperty("server.scanRecursively", String.valueOf(scanRecursively));
        props.setProperty("server.scanDisabled", String.valueOf(scanDisabled));
        props.setProperty("server.minecraftVersion", minecraftVersion);
        props.setProperty("server.modLoader", modLoader);
        props.setProperty("server.skipCurseForge", String.valueOf(skipCurseForge));
        props.setProperty("server.skipModrinth", String.valueOf(skipModrinth));
        props.setProperty("server.curseForgeApiKey", curseForgeApiKey);
        props.setProperty("server.httpTimeoutMs", String.valueOf(serverHttpTimeoutMs));
        props.setProperty("server.maxRetries", String.valueOf(serverMaxRetries));

        // 客户端
        props.setProperty("client.manifestUrl", manifestUrl);
        props.setProperty("client.autoSyncOnLaunch", String.valueOf(autoSyncOnLaunch));
        props.setProperty("client.periodicSyncMinutes", String.valueOf(periodicSyncMinutes));
        props.setProperty("client.expectedMinecraftVersion", expectedMinecraftVersion);
        props.setProperty("client.expectedModLoader", expectedModLoader);
        props.setProperty("client.modsDir", modsDir);
        props.setProperty("client.removeOrphans", String.valueOf(removeOrphans));
        props.setProperty("client.backupOldMods", String.valueOf(backupOldMods));
        props.setProperty("client.maxConcurrentDownloads", String.valueOf(maxConcurrentDownloads));
        props.setProperty("client.verifyHash", String.valueOf(verifyHash));
        props.setProperty("client.httpTimeoutMs", String.valueOf(clientHttpTimeoutMs));
        props.setProperty("client.maxRetries", String.valueOf(clientMaxRetries));
        props.setProperty("client.promptRestart", String.valueOf(promptRestart));
        props.setProperty("client.skipMods", skipMods);
        props.setProperty("client.onlyMods", onlyMods);

        Files.createDirectories(configPath.getParent());
        try (var out = Files.newOutputStream(configPath)) {
            props.store(out, "MC Mod Auto-Updater configuration");
        }
    }

    // ------------------------------------------------------------------
    // 辅助方法
    // ------------------------------------------------------------------

    /** 计算服务端写入清单的实际目录。outputDir 优先于 outputSubdir。 */
    public Path resolveOutputDir(Path gameDir) {
        if (outputDir != null && !outputDir.isBlank()) {
            Path p = Path.of(outputDir);
            return p.isAbsolute() ? p : gameDir.resolve(p).normalize();
        }
        return gameDir.resolve("config").resolve("mcmodupdater").resolve(outputSubdir);
    }

    /** 计算实际要扫描的目录列表。 */
    public List<Path> scanDirs(Path gameDir) {
        List<Path> dirs = new ArrayList<>();
        dirs.add(gameDir.resolve("mods"));
        if (extraScanDirs != null && !extraScanDirs.isBlank()) {
            for (String raw : extraScanDirs.split(",")) {
                String value = raw.trim();
                if (value.isEmpty()) continue;
                Path path = Path.of(value);
                dirs.add(path.isAbsolute() ? path : gameDir.resolve(path).normalize());
            }
        }
        return dirs;
    }

    /** 客户端实际使用的 mods 目录。 */
    public Path resolveModsDir(Path gameDir) {
        if (modsDir != null && !modsDir.isBlank()) {
            Path p = Path.of(modsDir);
            return p.isAbsolute() ? p : gameDir.resolve(p).normalize();
        }
        return gameDir.resolve("mods");
    }

    /** 解析 skipMods 为小写 mod id 集合。 */
    public Set<String> skipModsSet() {
        return parseCsvToSet(skipMods);
    }

    /** 解析 onlyMods 为小写 mod id 集合。空集合表示不过滤。 */
    public Set<String> onlyModsSet() {
        return parseCsvToSet(onlyMods);
    }

    /** 客户端有效的 HTTP 超时（毫秒），保证 &gt; 0。 */
    public int effectiveHttpTimeoutMs() {
        return clientHttpTimeoutMs > 0 ? clientHttpTimeoutMs : 15000;
    }

    /** 客户端有效的重试次数（&gt;= 0）。 */
    public int effectiveMaxRetries() {
        return Math.max(0, clientMaxRetries);
    }

    /** 服务端有效的 HTTP 超时（毫秒），保证 &gt; 0。 */
    public int effectiveServerHttpTimeoutMs() {
        return serverHttpTimeoutMs > 0 ? serverHttpTimeoutMs : 15000;
    }

    /** 服务端有效的重试次数（&gt;= 0）。 */
    public int effectiveServerMaxRetries() {
        return Math.max(0, serverMaxRetries);
    }

    private static Set<String> parseCsvToSet(String csv) {
        if (csv == null || csv.isBlank()) return Collections.emptySet();
        Set<String> set = new LinkedHashSet<>();
        for (String raw : csv.split(",")) {
            String v = raw.trim().toLowerCase();
            if (!v.isEmpty()) set.add(v);
        }
        return set;
    }

    private static boolean parseBool(Properties p, String key, boolean def) {
        return Boolean.parseBoolean(p.getProperty(key, String.valueOf(def)));
    }

    private static int parseInt(Properties p, String key, int def) {
        try {
            return Integer.parseInt(p.getProperty(key, String.valueOf(def)));
        } catch (Exception e) {
            return def;
        }
    }
}
