package com.cyhqw.mcmodupdater.common.config;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Properties;
import java.util.Set;

/**
 * 客户端配置。持久化为 {@code config/mcmodupdater/mcmodupdater.properties}。
 *
 * <p>本模组纯客户端运行：从 {@link #manifestUrl} 拉取 Modrinth 整合包格式的
 * modrinth.index.json，同步本地 mods 目录。配置项已尽量精简，只保留玩家真正需要调的项。</p>
 *
 * <p><b>Git 式追踪机制</b>：模组只管理"自己下载过的 mod"，不会删除玩家手动添加的 mod。
 * tracked mod 列表存储在 {@code config/mcmodupdater/tracked_mods.txt}，
 * 每次 add/update 时追加，remove 时移除。详见 {@link #loadTrackedMods} / {@link #saveTrackedMods}。</p>
 */
public final class ModUpdaterConfig {

    /**
     * 指向 modrinth.index.json 的 URL（HTTP/HTTPS）。
     * 留空时使用 {@link #DEFAULT_MANIFEST_URL}（指向本仓库根目录下的 modrinth.index.json）。
     * 玩家可自行填写其它 URL 覆盖默认值。
     */
    public String manifestUrl = "";

    /**
     * 默认 manifest URL：指向本仓库根目录下的 modrinth.index.json。
     * 整合包作者会把生成好的 modrinth.index.json 提交到仓库根目录，
     * 通过 GitHub raw URL 提供给客户端拉取。
     */
    public static final String DEFAULT_MANIFEST_URL =
            "https://raw.githubusercontent.com/cyhqw/mc-mod-autoupdater/main/modrinth.index.json";

    /** 获取实际使用的 manifest URL（用户未配置时回退到默认）。 */
    public String effectiveManifestUrl() {
        return (manifestUrl != null && !manifestUrl.isBlank()) ? manifestUrl : DEFAULT_MANIFEST_URL;
    }

    /**
     * 上次成功同步的 manifest versionId（由模组自动维护，玩家通常无需手动编辑）。
     * 启动时模组会拉取远端 manifest，对比 versionId 判断是否需要更新。
     * 为空表示从未同步过，触发首次同步。
     */
    public String currentVersionId = "";

    /** 启动时自动同步。 */
    public boolean autoSyncOnLaunch = true;

    /** 周期性同步间隔（分钟）。0 = 禁用；>0 = 每 N 分钟检查一次 manifest 是否更新。 */
    public int periodicSyncMinutes = 0;

    /** 自定义 mods 目录路径（绝对或相对游戏目录）。为空则用默认的 mods/。 */
    public String modsDir = "";

    /**
     * 覆盖 / 删除前备份为 .bak。
     * 注意：删除只针对 tracked mod（模组自己下载过的），玩家手动加的 mod 永不删除。
     */
    public boolean backupOldMods = true;

    /** 并发下载线程数。 */
    public int maxConcurrentDownloads = 4;

    /** 下载后校验 SHA1，不匹配则拒绝。 */
    public boolean verifyHash = true;

    /** HTTP 请求超时（毫秒）。0 表示使用默认值（15000）。 */
    public int httpTimeoutMs = 15000;

    /** 单文件下载失败时的最大重试次数。 */
    public int maxRetries = 2;

    /** 有变化时提示玩家重启。 */
    public boolean promptRestart = true;

    /** 黑名单：跳过指定 mod 文件名（逗号分隔，不带路径）。这些文件不会被下载/更新。 */
    public String skipMods = "";

    /** 白名单：仅同步这些 mod 文件名（逗号分隔）。为空则不过滤。 */
    public String onlyMods = "";

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
        c.manifestUrl = props.getProperty("manifestUrl", c.manifestUrl);
        c.currentVersionId = props.getProperty("currentVersionId", c.currentVersionId);
        c.autoSyncOnLaunch = parseBool(props, "autoSyncOnLaunch", c.autoSyncOnLaunch);
        c.periodicSyncMinutes = parseInt(props, "periodicSyncMinutes", c.periodicSyncMinutes);
        c.modsDir = props.getProperty("modsDir", c.modsDir);
        c.backupOldMods = parseBool(props, "backupOldMods", c.backupOldMods);
        c.maxConcurrentDownloads = parseInt(props, "maxConcurrentDownloads", c.maxConcurrentDownloads);
        c.verifyHash = parseBool(props, "verifyHash", c.verifyHash);
        c.httpTimeoutMs = parseInt(props, "httpTimeoutMs", c.httpTimeoutMs);
        c.maxRetries = parseInt(props, "maxRetries", c.maxRetries);
        c.promptRestart = parseBool(props, "promptRestart", c.promptRestart);
        c.skipMods = props.getProperty("skipMods", c.skipMods);
        c.onlyMods = props.getProperty("onlyMods", c.onlyMods);
        return c;
    }

    public void save(Path configPath) throws IOException {
        Properties props = new Properties();
        props.setProperty("manifestUrl", manifestUrl);
        props.setProperty("currentVersionId", currentVersionId);
        props.setProperty("autoSyncOnLaunch", String.valueOf(autoSyncOnLaunch));
        props.setProperty("periodicSyncMinutes", String.valueOf(periodicSyncMinutes));
        props.setProperty("modsDir", modsDir);
        props.setProperty("backupOldMods", String.valueOf(backupOldMods));
        props.setProperty("maxConcurrentDownloads", String.valueOf(maxConcurrentDownloads));
        props.setProperty("verifyHash", String.valueOf(verifyHash));
        props.setProperty("httpTimeoutMs", String.valueOf(httpTimeoutMs));
        props.setProperty("maxRetries", String.valueOf(maxRetries));
        props.setProperty("promptRestart", String.valueOf(promptRestart));
        props.setProperty("skipMods", skipMods);
        props.setProperty("onlyMods", onlyMods);

        Files.createDirectories(configPath.getParent());
        try (var out = Files.newOutputStream(configPath)) {
            props.store(out, "MC Mod Auto-Updater client configuration");
        }
    }

    // ------------------------------------------------------------------
    // tracked_mods.txt 管理（Git 式追踪）
    // ------------------------------------------------------------------

    /**
     * 加载 tracked mod 列表。tracked mod 是模组自己下载过的 mod，
     * 后续同步时只对这些 mod 做 add/update/remove，玩家手动加的 mod 永不触碰。
     *
     * @param trackedPath tracked_mods.txt 路径（位于 config/mcmodupdater/ 下）
     * @return 文件名集合（小写），空集表示从未同步过
     */
    public static Set<String> loadTrackedMods(Path trackedPath) {
        if (!Files.exists(trackedPath)) {
            return Collections.emptySet();
        }
        Set<String> set = new LinkedHashSet<>();
        try {
            for (String line : Files.readAllLines(trackedPath)) {
                String v = line.trim().toLowerCase();
                if (!v.isEmpty() && !v.startsWith("#")) {
                    set.add(v);
                }
            }
        } catch (IOException e) {
            return Collections.emptySet();
        }
        return set;
    }

    /**
     * 保存 tracked mod 列表。会覆盖整个文件。
     *
     * @param trackedPath tracked_mods.txt 路径
     * @param trackedMods 文件名集合（会被转为小写）
     */
    public static void saveTrackedMods(Path trackedPath, Set<String> trackedMods) throws IOException {
        Files.createDirectories(trackedPath.getParent());
        Set<String> sorted = new LinkedHashSet<>();
        for (String m : trackedMods) {
            String v = m.trim().toLowerCase();
            if (!v.isEmpty()) {
                sorted.add(v);
            }
        }
        StringBuilder sb = new StringBuilder();
        sb.append("# MC Mod Auto-Updater — tracked mods list\n");
        sb.append("# 这个文件记录了模组自己下载/管理过的 mod 文件名。\n");
        sb.append("# 后续同步时只对这些 mod 做 add/update/remove，\n");
        sb.append("# 玩家手动添加的 mod 永不删除。请勿手动编辑。\n\n");
        for (String m : sorted) {
            sb.append(m).append("\n");
        }
        Files.writeString(trackedPath, sb.toString());
    }

    // ------------------------------------------------------------------
    // 辅助方法
    // ------------------------------------------------------------------

    /** 客户端实际使用的 mods 目录。 */
    public Path resolveModsDir(Path gameDir) {
        if (modsDir != null && !modsDir.isBlank()) {
            Path p = Path.of(modsDir);
            return p.isAbsolute() ? p : gameDir.resolve(p).normalize();
        }
        return gameDir.resolve("mods");
    }

    /** 解析 skipMods 为小写文件名集合。 */
    public Set<String> skipModsSet() {
        return parseCsvToSet(skipMods);
    }

    /** 解析 onlyMods 为小写文件名集合。空集合表示不过滤。 */
    public Set<String> onlyModsSet() {
        return parseCsvToSet(onlyMods);
    }

    /** 有效的 HTTP 超时（毫秒），保证 > 0。 */
    public int effectiveHttpTimeoutMs() {
        return httpTimeoutMs > 0 ? httpTimeoutMs : 15000;
    }

    /** 有效的重试次数（>= 0）。 */
    public int effectiveMaxRetries() {
        return Math.max(0, maxRetries);
    }

    private static Set<String> parseCsvToSet(String csv) {
        if (csv == null || csv.isBlank()) {
            return Collections.emptySet();
        }
        Set<String> set = new LinkedHashSet<>();
        for (String raw : csv.split(",")) {
            String v = raw.trim().toLowerCase();
            if (!v.isEmpty()) {
                set.add(v);
            }
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
