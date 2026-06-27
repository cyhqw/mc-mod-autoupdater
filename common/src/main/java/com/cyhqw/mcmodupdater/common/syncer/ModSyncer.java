package com.cyhqw.mcmodupdater.common.syncer;

import com.cyhqw.mcmodupdater.common.config.ModUpdaterConfig;
import com.cyhqw.mcmodupdater.common.http.HttpUtil;
import com.cyhqw.mcmodupdater.common.modrinth.ModrinthFile;
import com.cyhqw.mcmodupdater.common.modrinth.ModrinthIndex;
import com.cyhqw.mcmodupdater.common.util.HashUtils;
import com.cyhqw.mcmodupdater.common.util.ModLog;
import com.google.gson.Gson;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;
import java.util.stream.Stream;

/**
 * 客户端同步逻辑：从 URL 拉取 Modrinth 整合包格式的 modrinth.index.json，
 * 同步本地 mods 目录。
 *
 * <p>同步算法：</p>
 * <ol>
 *   <li>从 {@link ModUpdaterConfig#manifestUrl} 下载 modrinth.index.json</li>
 *   <li>过滤出 path 以 {@code mods/} 开头、env.client 为 required/optional 的条目</li>
 *   <li>应用 skipMods/onlyMods 文件名过滤</li>
 *   <li>对每个条目：
 *     <ul>
 *       <li>本地已存在且 SHA1 匹配 → 跳过</li>
 *       <li>否则下载到 .part，校验 SHA1，原子重命名（支持重试）</li>
 *       <li>覆盖前可选备份为 .bak</li>
 *     </ul>
 *   </li>
 *   <li>若 {@code removeOrphans=true}，删除本地 manifest 中不存在的 .jar（可选备份）</li>
 *   <li>返回 {@link SyncResult}，供平台层提示玩家重启</li>
 * </ol>
 */
public final class ModSyncer {

    private final Path modsDir;
    private final ModUpdaterConfig config;

    public ModSyncer(Path modsDir, ModUpdaterConfig config) {
        this.modsDir = modsDir;
        this.config = config;
    }

    public SyncResult sync() {
        try {
            Files.createDirectories(modsDir);
        } catch (IOException e) {
            return SyncResult.failure("Could not create mods dir: " + e.getMessage());
        }

        if (config.manifestUrl == null || config.manifestUrl.isBlank()) {
            return SyncResult.failure("manifestUrl is not set in config");
        }

        ModrinthIndex index;
        try {
            String body = HttpUtil.getString(
                    config.manifestUrl,
                    config.effectiveHttpTimeoutMs(), 0);
            index = new Gson().fromJson(JsonParser.parseString(body).getAsJsonObject(), ModrinthIndex.class);
        } catch (Exception e) {
            return SyncResult.failure("Failed to fetch manifest from " + config.manifestUrl + ": " + e.getMessage());
        }

        if (index == null || index.files == null) {
            return SyncResult.failure("Manifest was empty or malformed");
        }
        ModLog.info("Manifest fetched: %d total file(s), versionId=%s, name=%s",
                index.files.size(), index.versionId, index.name);
        if (index.dependencies != null && !index.dependencies.isEmpty()) {
            ModLog.info("Manifest dependencies: %s", index.dependencies);
        }

        String validationError = validateManifest(index);
        if (validationError != null) {
            return SyncResult.failure(validationError);
        }

        // 解析黑白名单
        Set<String> skip = config.skipModsSet();
        Set<String> only = config.onlyModsSet();

        // Build a map of existing files by filename.
        Map<String, Path> existing = new HashMap<>();
        try (Stream<Path> stream = Files.list(modsDir)) {
            stream.filter(Files::isRegularFile).forEach(p -> {
                String n = p.getFileName().toString();
                if (n.toLowerCase().endsWith(".jar") || n.toLowerCase().endsWith(".jar.disabled")) {
                    existing.put(n, p);
                }
            });
        } catch (IOException e) {
            return SyncResult.failure("Could not list mods dir: " + e.getMessage());
        }

        // Track which files the manifest expects, so we can remove orphans.
        Set<String> expectedFilenames = new HashSet<>();
        List<CompletableFuture<SyncAction>> futures = new ArrayList<>();
        Semaphore concurrency = new Semaphore(Math.max(1, config.maxConcurrentDownloads));
        ExecutorService pool = Executors.newFixedThreadPool(Math.max(1, config.maxConcurrentDownloads));

        int skippedByFilter = 0;
        int skippedByEnv = 0;
        for (ModrinthFile file : index.files) {
            // 仅同步 mods/ 下的文件
            if (!file.isMod()) continue;
            // 仅同步客户端需要的文件
            if (!file.isClientRequired()) {
                skippedByEnv++;
                continue;
            }
            // 黑白名单过滤
            String filename = file.fileName();
            String filenameLower = filename.toLowerCase();
            if (!skip.isEmpty() && skip.contains(filenameLower)) {
                ModLog.info("[filter:skip] %s", filename);
                skippedByFilter++;
                continue;
            }
            if (!only.isEmpty() && !only.contains(filenameLower)) {
                ModLog.info("[filter:only] %s not in whitelist", filename);
                skippedByFilter++;
                continue;
            }

            expectedFilenames.add(filename);
            futures.add(CompletableFuture.supplyAsync(() -> {
                try {
                    concurrency.acquire();
                    try {
                        return syncOne(file, existing);
                    } finally {
                        concurrency.release();
                    }
                } catch (Exception e) {
                    return new SyncAction(file, ActionStatus.FAILED, e.getMessage());
                }
            }, pool));
        }

        List<SyncAction> actions = new ArrayList<>();
        for (CompletableFuture<SyncAction> f : futures) {
            try {
                actions.add(f.get());
            } catch (Exception e) {
                actions.add(new SyncAction(null, ActionStatus.FAILED, "future error: " + e.getMessage()));
            }
        }
        pool.shutdown();

        // Remove orphans
        List<String> removed = new ArrayList<>();
        if (config.removeOrphans) {
            for (Map.Entry<String, Path> e : existing.entrySet()) {
                if (!expectedFilenames.contains(e.getKey())) {
                    try {
                        if (config.backupOldMods) {
                            Path bak = e.getValue().resolveSibling(e.getKey() + ".bak");
                            Files.move(e.getValue(), bak, StandardCopyOption.REPLACE_EXISTING);
                            ModLog.info("Backed up orphan: %s -> %s", e.getKey(), bak);
                        } else {
                            Files.delete(e.getValue());
                            ModLog.info("Removed orphan: %s", e.getKey());
                        }
                        removed.add(e.getKey());
                    } catch (IOException ex) {
                        ModLog.warn("Failed to remove orphan %s: %s", e.getKey(), ex.getMessage());
                    }
                }
            }
        }

        boolean changed = actions.stream().anyMatch(a -> a.status != ActionStatus.SKIPPED) || !removed.isEmpty();
        boolean failed = actions.stream().anyMatch(a -> a.status == ActionStatus.FAILED);
        return new SyncResult(index, actions, removed, changed, failed, null, skippedByFilter, skippedByEnv);
    }

    private SyncAction syncOne(ModrinthFile file, Map<String, Path> existing) {
        String filename = file.fileName();
        Path target = modsDir.resolve(filename).normalize();
        Path part = modsDir.resolve(filename + ".part").normalize();

        // 路径安全：目标必须在 mods 目录内
        if (!target.getParent().equals(modsDir.normalize())) {
            return new SyncAction(file, ActionStatus.FAILED, "unsafe path: " + file.path);
        }

        String expectedSha1 = file.sha1();

        // Skip if file exists with matching hash.
        if (Files.exists(target)) {
            try {
                if (!config.verifyHash || expectedSha1 == null) {
                    ModLog.info("[skip] %s (hash check disabled or missing)", filename);
                    return new SyncAction(file, ActionStatus.SKIPPED, "exists, hash not verified");
                }
                String localSha1 = HashUtils.sha1(target);
                if (localSha1.equalsIgnoreCase(expectedSha1)) {
                    ModLog.info("[skip] %s (sha1 matches)", filename);
                    return new SyncAction(file, ActionStatus.SKIPPED, "sha1 matches");
                }
                ModLog.info("[update] %s: local=%s remote=%s", filename, localSha1, expectedSha1);
                if (config.backupOldMods) {
                    Path bak = target.resolveSibling(filename + ".bak");
                    Files.move(target, bak, StandardCopyOption.REPLACE_EXISTING);
                } else {
                    Files.deleteIfExists(target);
                }
            } catch (IOException e) {
                return new SyncAction(file, ActionStatus.FAILED, "hash check failed: " + e.getMessage());
            }
        }

        // Download with retry
        if (file.downloads == null || file.downloads.isEmpty()) {
            return new SyncAction(file, ActionStatus.FAILED, "no download URLs in manifest");
        }
        int retries = config.effectiveMaxRetries();
        Exception lastErr = null;
        for (int attempt = 0; attempt <= retries; attempt++) {
            // 依次尝试 downloads 列表中的每个 URL
            for (String url : file.downloads) {
                try {
                    Files.deleteIfExists(part);
                    HttpUtil.downloadFile(url, part,
                            config.effectiveHttpTimeoutMs(), 0);
                    if (config.verifyHash && expectedSha1 != null) {
                        String got = HashUtils.sha1(part);
                        if (!got.equalsIgnoreCase(expectedSha1)) {
                            Files.deleteIfExists(part);
                            throw new IOException("sha1 mismatch after download: got=" + got + " expected=" + expectedSha1);
                        }
                    }
                    Files.move(part, target, StandardCopyOption.REPLACE_EXISTING);
                    return new SyncAction(file, ActionStatus.DOWNLOADED, "ok");
                } catch (Exception e) {
                    lastErr = e;
                    try { Files.deleteIfExists(part); } catch (IOException ignored) {}
                    ModLog.info("[retry] %s url=%s attempt=%d/%d: %s",
                            filename, url, attempt + 1, retries + 1, e.getMessage());
                }
            }
            if (attempt < retries) {
                try { Thread.sleep(250L * (1L << Math.min(attempt, 5))); }
                catch (InterruptedException ie) { Thread.currentThread().interrupt(); break; }
            }
        }
        return new SyncAction(file, ActionStatus.FAILED,
                lastErr != null ? lastErr.getMessage() : "download failed");
    }

    private String validateManifest(ModrinthIndex index) {
        if (index.formatVersion != 1) {
            return "Unsupported manifest formatVersion: " + index.formatVersion + " (expected 1)";
        }
        Set<String> filenames = new HashSet<>();
        for (int i = 0; i < index.files.size(); i++) {
            ModrinthFile file = index.files.get(i);
            if (file == null) {
                return "Manifest contains a null file entry at index " + i;
            }
            if (file.path == null || file.path.isBlank()) {
                return "Manifest file entry at index " + i + " is missing path";
            }
            if (!file.isMod()) continue; // 非 mods/ 下的文件不校验，直接跳过
            String filename = file.fileName();
            if (filename.isEmpty() || filename.equals("mods")) {
                return "Manifest file path is malformed: " + file.path;
            }
            Path target = modsDir.resolve(filename).normalize();
            if (!target.getParent().equals(modsDir.normalize())) {
                return "Manifest filename escapes mods dir: " + file.path;
            }
            String lower = filename.toLowerCase();
            if (!lower.endsWith(".jar") && !lower.endsWith(".jar.disabled")) {
                return "Manifest filename is not a jar: " + filename;
            }
            if (!filenames.add(filename)) {
                return "Manifest contains duplicate filename: " + filename;
            }
            if (file.downloads == null || file.downloads.isEmpty()) {
                return "Manifest entry " + filename + " has no downloads";
            }
            if (config.verifyHash && (file.sha1() == null || file.sha1().isBlank())) {
                return "Manifest entry " + filename + " is missing sha1 while hash verification is enabled";
            }
        }
        return null;
    }

    // ------------------------------------------------------------------

    public enum ActionStatus { SKIPPED, DOWNLOADED, FAILED }

    public static final class SyncAction {
        public final ModrinthFile file;
        public final ActionStatus status;
        public final String detail;

        public SyncAction(ModrinthFile file, ActionStatus status, String detail) {
            this.file = file;
            this.status = status;
            this.detail = detail;
        }
    }

    public static final class SyncResult {
        public final ModrinthIndex manifest;
        public final List<SyncAction> actions;
        public final List<String> removedOrphans;
        public final boolean changed;
        public final boolean failed;
        public final String errorMessage;
        public final int skippedByFilter;
        public final int skippedByEnv;

        private SyncResult(ModrinthIndex manifest, List<SyncAction> actions, List<String> removed,
                           boolean changed, boolean failed, String errorMessage,
                           int skippedByFilter, int skippedByEnv) {
            this.manifest = manifest;
            this.actions = actions;
            this.removedOrphans = removed;
            this.changed = changed;
            this.failed = failed;
            this.errorMessage = errorMessage;
            this.skippedByFilter = skippedByFilter;
            this.skippedByEnv = skippedByEnv;
        }

        public static SyncResult failure(String message) {
            return new SyncResult(null, new ArrayList<>(), new ArrayList<>(), false, true, message, 0, 0);
        }

        public int downloadedCount() {
            return (int) actions.stream().filter(a -> a.status == ActionStatus.DOWNLOADED).count();
        }

        public int skippedCount() {
            return (int) actions.stream().filter(a -> a.status == ActionStatus.SKIPPED).count();
        }

        public int failedCount() {
            return (int) actions.stream().filter(a -> a.status == ActionStatus.FAILED).count();
        }
    }
}
