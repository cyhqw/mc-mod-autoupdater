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
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;

/**
 * 客户端同步逻辑：从 URL 拉取 Modrinth 整合包格式的 modrinth.index.json，
 * 同步本地 mods 目录。
 *
 * <p><b>Git 式追踪机制</b>：模组只管理"自己下载过的 mod"（tracked mod），
 * 不会删除玩家手动添加的 mod。</p>
 *
 * <p>同步算法：</p>
 * <ol>
 *   <li>从 {@link ModUpdaterConfig#manifestUrl} 下载 modrinth.index.json</li>
 *   <li>过滤出 path 以 {@code mods/} 开头、env.client 为 required/optional 的条目</li>
 *   <li>应用 skipMods/onlyMods 文件名过滤</li>
 *   <li>对每个条目：
 *     <ul>
 *       <li>本地已存在且 SHA1 匹配 → 跳过（标记 KEEP）</li>
 *       <li>否则下载到 .part，校验 SHA1，原子重命名（标记 ADD 或 UPDATE）</li>
 *     </ul>
 *   </li>
 *   <li><b>仅删除 tracked mod 中 manifest 不再包含的</b>（玩家手动加的 mod 永不触碰）</li>
 *   <li>更新 tracked_mods 列表（新增成功的加入，删除的移除）</li>
 * </ol>
 */
public final class ModSyncer {

    private final Path modsDir;
    private final ModUpdaterConfig config;
    private final Path trackedModsPath;
    private final Set<String> trackedMods;

    public ModSyncer(Path modsDir, ModUpdaterConfig config, Path trackedModsPath) {
        this.modsDir = modsDir;
        this.config = config;
        this.trackedModsPath = trackedModsPath;
        this.trackedMods = new LinkedHashSet<>(ModUpdaterConfig.loadTrackedMods(trackedModsPath));
    }

    /**
     * 进度回调接口。GUI 实现此接口以实时显示更新进度。
     */
    @FunctionalInterface
    public interface ProgressCallback {
        void onProgress(int current, int total, String filename, ActionStatus status, String detail);
    }

    /**
     * 仅检查版本，不下载文件。
     */
    public CheckResult checkVersion() {
        String url = config.effectiveManifestUrl();
        ModrinthIndex index;
        try {
            String body = HttpUtil.getString(
                    url,
                    config.effectiveHttpTimeoutMs(), 0);
            index = new Gson().fromJson(JsonParser.parseString(body).getAsJsonObject(), ModrinthIndex.class);
        } catch (Exception e) {
            return CheckResult.error("Failed to fetch manifest: " + e.getMessage());
        }
        if (index == null) {
            return CheckResult.error("Manifest was empty or malformed");
        }
        String remote = index.versionId == null ? "" : index.versionId;
        String local = config.currentVersionId == null ? "" : config.currentVersionId;
        boolean needUpdate;
        if (local.isEmpty()) {
            needUpdate = true;
            ModLog.info("Version check: no local version recorded, sync required (remote=%s)", remote);
        } else if (remote.isEmpty()) {
            needUpdate = true;
            ModLog.info("Version check: remote has no versionId, forcing sync (local=%s)", local);
        } else {
            needUpdate = !local.equals(remote);
            ModLog.info("Version check: local=%s remote=%s needUpdate=%s", local, remote, needUpdate);
        }
        return new CheckResult(index, remote, local, needUpdate, null);
    }

    /**
     * 分析 manifest 与本地 mods 目录的差异，不下载文件。
     * 用于在 GUI 中展示"将要发生什么"。
     *
     * <p>差异分类：</p>
     * <ul>
     *   <li>ADD: manifest 有但本地没有，将下载</li>
     *   <li>UPDATE: 本地有但 SHA1 不同，将覆盖</li>
     *   <li>KEEP: 本地有且 SHA1 相同，跳过</li>
     *   <li>REMOVE: tracked mod 中 manifest 不再包含的，将删除（玩家手动加的不会被删除）</li>
     * </ul>
     */
    public DiffResult analyzeDiff(ModrinthIndex index) {
        try {
            Files.createDirectories(modsDir);
        } catch (IOException e) {
            return new DiffResult(new ArrayList<>(), new ArrayList<>(), new ArrayList<>(), new ArrayList<>(),
                    "Could not create mods dir: " + e.getMessage());
        }

        Set<String> skip = config.skipModsSet();
        Set<String> only = config.onlyModsSet();
        Map<String, Path> existing = new HashMap<>();
        try (Stream<Path> stream = Files.list(modsDir)) {
            stream.filter(Files::isRegularFile).forEach(p -> {
                String n = p.getFileName().toString();
                if (n.toLowerCase().endsWith(".jar") || n.toLowerCase().endsWith(".jar.disabled")) {
                    existing.put(n, p);
                }
            });
        } catch (IOException e) {
            return new DiffResult(new ArrayList<>(), new ArrayList<>(), new ArrayList<>(), new ArrayList<>(),
                    "Could not list mods dir: " + e.getMessage());
        }

        List<DiffEntry> toDownload = new ArrayList<>();
        List<DiffEntry> toKeep = new ArrayList<>();
        Set<String> expectedFilenames = new HashSet<>();
        int skippedByFilter = 0;
        int skippedByEnv = 0;

        for (ModrinthFile file : index.files) {
            if (!file.isMod()) continue;
            if (!file.isClientRequired()) {
                skippedByEnv++;
                continue;
            }
            String filename = file.fileName();
            String filenameLower = filename.toLowerCase();
            if (!skip.isEmpty() && skip.contains(filenameLower)) {
                skippedByFilter++;
                continue;
            }
            if (!only.isEmpty() && !only.contains(filenameLower)) {
                skippedByFilter++;
                continue;
            }
            expectedFilenames.add(filename);

            Path target = modsDir.resolve(filename).normalize();
            String remoteSha1 = file.sha1();
            long remoteSize = file.fileSize;
            String downloadUrl = (file.downloads != null && !file.downloads.isEmpty()) ? file.downloads.get(0) : "";

            if (Files.exists(target)) {
                String localSha1;
                try {
                    localSha1 = config.verifyHash && remoteSha1 != null ? HashUtils.sha1(target) : "";
                } catch (IOException e) {
                    localSha1 = "";
                }
                if (config.verifyHash && remoteSha1 != null
                        && !localSha1.isEmpty()
                        && localSha1.equalsIgnoreCase(remoteSha1)) {
                    toKeep.add(new DiffEntry(filename, DiffAction.KEEP, localSha1, remoteSha1,
                            target.toFile().length(), remoteSize, downloadUrl));
                } else {
                    toDownload.add(new DiffEntry(filename, DiffAction.UPDATE,
                            localSha1, remoteSha1,
                            target.toFile().length(), remoteSize, downloadUrl));
                }
                existing.remove(filename);
            } else {
                toDownload.add(new DiffEntry(filename, DiffAction.ADD,
                        "", remoteSha1, 0L, remoteSize, downloadUrl));
            }
        }

        // remaining existing 是本地有但 manifest 没有的
        // 只删除 tracked 的（模组自己下载过的），玩家手动加的不删
        List<DiffEntry> toRemove = new ArrayList<>();
        List<DiffEntry> playerOwned = new ArrayList<>();  // 玩家手动加的，不删
        for (Map.Entry<String, Path> e : existing.entrySet()) {
            String filenameLower = e.getKey().toLowerCase();
            if (trackedMods.contains(filenameLower) && !expectedFilenames.contains(e.getKey())) {
                // tracked 但 manifest 不再有 → 删除
                toRemove.add(new DiffEntry(e.getKey(), DiffAction.REMOVE,
                        "", "", e.getValue().toFile().length(), 0L, ""));
            } else if (!trackedMods.contains(filenameLower)) {
                // 玩家手动加的 → 不删，记录为 PLAYER_OWNED
                playerOwned.add(new DiffEntry(e.getKey(), DiffAction.PLAYER_OWNED,
                        "", "", e.getValue().toFile().length(), 0L, ""));
            }
        }

        ModLog.info("[Diff] tracked=%d, toDownload=%d, toKeep=%d, toRemove=%d, playerOwned=%d",
                trackedMods.size(), toDownload.size(), toKeep.size(), toRemove.size(), playerOwned.size());
        return new DiffResult(toDownload, toKeep, toRemove, playerOwned, null)
                .withSkipped(skippedByFilter, skippedByEnv);
    }

    /**
     * 执行完整同步（无进度回调）。
     */
    public SyncResult sync() {
        return sync(null);
    }

    /**
     * 执行完整同步，带进度回调。
     * 同步成功后调用方应把 {@link SyncResult#remoteVersionId} 回写到配置文件，
     * 并把 {@link SyncResult#newTrackedMods} 保存到 tracked_mods.txt。
     *
     * @param callback 进度回调，可为 null
     */
    public SyncResult sync(ProgressCallback callback) {
        try {
            Files.createDirectories(modsDir);
        } catch (IOException e) {
            SyncResult r = SyncResult.failure("Could not create mods dir: " + e.getMessage());
            return r;
        }

        // 立即回调一次，让 GUI 显示"正在获取清单..."
        if (callback != null) callback.onProgress(0, 1, "正在获取清单...", ActionStatus.SKIPPED, "");

        String url = config.effectiveManifestUrl();
        ModrinthIndex index;
        try {
            String body = HttpUtil.getString(
                    url,
                    config.effectiveHttpTimeoutMs(), 0);
            index = new Gson().fromJson(JsonParser.parseString(body).getAsJsonObject(), ModrinthIndex.class);
        } catch (Exception e) {
            return SyncResult.failure("Failed to fetch manifest from " + url + ": " + e.getMessage());
        }

        if (index == null || index.files == null) {
            return SyncResult.failure("Manifest was empty or malformed");
        }
        ModLog.info("Manifest fetched: %d total file(s), versionId=%s, name=%s",
                index.files.size(), index.versionId, index.name);

        String validationError = validateManifest(index);
        if (validationError != null) {
            return SyncResult.failure(validationError);
        }

        Set<String> skip = config.skipModsSet();
        Set<String> only = config.onlyModsSet();

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

        // 收集要处理的文件列表
        List<ModrinthFile> toProcess = new ArrayList<>();
        Set<String> expectedFilenames = new HashSet<>();
        int skippedByFilter = 0;
        int skippedByEnv = 0;
        for (ModrinthFile file : index.files) {
            if (!file.isMod()) continue;
            if (!file.isClientRequired()) {
                skippedByEnv++;
                continue;
            }
            String filename = file.fileName();
            String filenameLower = filename.toLowerCase();
            if (!skip.isEmpty() && skip.contains(filenameLower)) {
                skippedByFilter++;
                continue;
            }
            if (!only.isEmpty() && !only.contains(filenameLower)) {
                skippedByFilter++;
                continue;
            }
            expectedFilenames.add(filename);
            toProcess.add(file);
        }

        int total = toProcess.size();
        if (callback != null) callback.onProgress(0, total, "正在准备下载列表...", ActionStatus.SKIPPED, "");

        // 多线程并发下载
        Semaphore concurrency = new Semaphore(Math.max(1, config.maxConcurrentDownloads));
        ExecutorService pool = Executors.newFixedThreadPool(Math.max(1, config.maxConcurrentDownloads));
        Map<String, CompletableFuture<SyncAction>> futuresMap = new LinkedHashMap<>();
        for (ModrinthFile file : toProcess) {
            futuresMap.put(file.fileName(), CompletableFuture.supplyAsync(() -> {
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

        // 等待所有 future 完成，按完成顺序回调进度
        AtomicInteger completed = new AtomicInteger(0);
        List<CompletableFuture<Void>> progressFutures = new ArrayList<>();
        for (Map.Entry<String, CompletableFuture<SyncAction>> e : futuresMap.entrySet()) {
            final String filename = e.getKey();
            CompletableFuture<SyncAction> f = e.getValue();
            CompletableFuture<Void> pf = f.thenAccept(action -> {
                int cur = completed.incrementAndGet();
                if (callback != null) {
                    callback.onProgress(cur, total, filename, action.status, action.detail);
                }
            });
            progressFutures.add(pf);
        }
        try {
            CompletableFuture.allOf(progressFutures.toArray(new CompletableFuture[0])).get();
        } catch (Exception e) {
            ModLog.warn("Progress wait interrupted: %s", e.getMessage());
        }
        pool.shutdown();

        // 收集 actions（按 toProcess 顺序）
        List<SyncAction> actions = new ArrayList<>();
        Set<String> newTrackedMods = new LinkedHashSet<>();
        for (ModrinthFile file : toProcess) {
            CompletableFuture<SyncAction> f = futuresMap.get(file.fileName());
            try {
                SyncAction action = f.get();
                actions.add(action);
                // 下载成功或已存在的 mod 都加入 tracked
                if (action.status == ActionStatus.DOWNLOADED || action.status == ActionStatus.SKIPPED) {
                    newTrackedMods.add(file.fileName().toLowerCase());
                }
            } catch (Exception e) {
                actions.add(new SyncAction(file, ActionStatus.FAILED, "future error: " + e.getMessage()));
            }
        }

        // 删除 tracked mod 中 manifest 不再包含的
        // 首次同步时 trackedMods 为空，不会删任何东西
        List<String> removed = new ArrayList<>();
        for (String trackedFilename : trackedMods) {
            // trackedFilename 是小写的，需要还原大小写查找 existing
            String actualFilename = null;
            for (String existingFn : existing.keySet()) {
                if (existingFn.equalsIgnoreCase(trackedFilename)) {
                    actualFilename = existingFn;
                    break;
                }
            }
            if (actualFilename == null) continue;  // 本地已没有这个文件
            if (expectedFilenames.contains(actualFilename)) continue;  // manifest 还有，不删

            // tracked 但 manifest 不再有 → 删除
            Path target = existing.get(actualFilename);
            try {
                if (config.backupOldMods) {
                    Path bak = target.resolveSibling(actualFilename + ".bak");
                    Files.move(target, bak, StandardCopyOption.REPLACE_EXISTING);
                    ModLog.info("Backed up tracked orphan: %s -> %s", actualFilename, bak);
                } else {
                    Files.delete(target);
                    ModLog.info("Removed tracked orphan: %s", actualFilename);
                }
                removed.add(actualFilename);
                // 从 tracked 中移除
                newTrackedMods.remove(trackedFilename);
            } catch (IOException ex) {
                ModLog.warn("Failed to remove tracked orphan %s: %s", actualFilename, ex.getMessage());
            }
        }

        // 保存 tracked_mods.txt
        try {
            ModUpdaterConfig.saveTrackedMods(trackedModsPath, newTrackedMods);
            ModLog.info("[Sync] Saved %d tracked mods to %s", newTrackedMods.size(), trackedModsPath);
        } catch (IOException e) {
            ModLog.warn("[Sync] Failed to save tracked mods: %s", e.getMessage());
        }

        boolean changed = actions.stream().anyMatch(a -> a.status != ActionStatus.SKIPPED) || !removed.isEmpty();
        boolean failed = actions.stream().anyMatch(a -> a.status == ActionStatus.FAILED);
        String remoteVersion = index.versionId == null ? "" : index.versionId;
        return new SyncResult(index, actions, removed, changed, failed, null,
                skippedByFilter, skippedByEnv, remoteVersion, newTrackedMods);
    }

    private SyncAction syncOne(ModrinthFile file, Map<String, Path> existing) {
        String filename = file.fileName();
        Path target = modsDir.resolve(filename).normalize();
        Path part = modsDir.resolve(filename + ".part").normalize();

        if (!target.getParent().equals(modsDir.normalize())) {
            return new SyncAction(file, ActionStatus.FAILED, "unsafe path: " + file.path);
        }

        String expectedSha1 = file.sha1();

        if (Files.exists(target)) {
            try {
                if (!config.verifyHash || expectedSha1 == null) {
                    return new SyncAction(file, ActionStatus.SKIPPED, "exists, hash not verified");
                }
                String localSha1 = HashUtils.sha1(target);
                if (localSha1.equalsIgnoreCase(expectedSha1)) {
                    return new SyncAction(file, ActionStatus.SKIPPED, "sha1 matches");
                }
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

        if (file.downloads == null || file.downloads.isEmpty()) {
            return new SyncAction(file, ActionStatus.FAILED, "no download URLs in manifest");
        }
        int retries = config.effectiveMaxRetries();
        Exception lastErr = null;
        for (int attempt = 0; attempt <= retries; attempt++) {
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
            if (!file.isMod()) continue;
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

    public enum DiffAction { ADD, UPDATE, KEEP, REMOVE, PLAYER_OWNED }

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

    public static final class DiffEntry {
        public final String filename;
        public final DiffAction action;
        public final String localSha1;
        public final String remoteSha1;
        public final long localSize;
        public final long remoteSize;
        public final String downloadUrl;

        public DiffEntry(String filename, DiffAction action,
                         String localSha1, String remoteSha1,
                         long localSize, long remoteSize, String downloadUrl) {
            this.filename = filename;
            this.action = action;
            this.localSha1 = localSha1;
            this.remoteSha1 = remoteSha1;
            this.localSize = localSize;
            this.remoteSize = remoteSize;
            this.downloadUrl = downloadUrl;
        }
    }

    public static final class DiffResult {
        public final List<DiffEntry> toDownload;
        public final List<DiffEntry> toKeep;
        public final List<DiffEntry> toRemove;
        public final List<DiffEntry> playerOwned;
        public final String errorMessage;
        public int skippedByFilter;
        public int skippedByEnv;

        public DiffResult(List<DiffEntry> toDownload, List<DiffEntry> toKeep,
                          List<DiffEntry> toRemove, List<DiffEntry> playerOwned,
                          String errorMessage) {
            this.toDownload = toDownload;
            this.toKeep = toKeep;
            this.toRemove = toRemove;
            this.playerOwned = playerOwned;
            this.errorMessage = errorMessage;
        }

        public DiffResult withSkipped(int byFilter, int byEnv) {
            this.skippedByFilter = byFilter;
            this.skippedByEnv = byEnv;
            return this;
        }

        public boolean hasError() {
            return errorMessage != null;
        }
    }

    public static final class CheckResult {
        public final ModrinthIndex manifest;
        public final String remoteVersionId;
        public final String localVersionId;
        public final boolean needUpdate;
        public final String errorMessage;

        private CheckResult(ModrinthIndex manifest, String remoteVersionId, String localVersionId,
                            boolean needUpdate, String errorMessage) {
            this.manifest = manifest;
            this.remoteVersionId = remoteVersionId;
            this.localVersionId = localVersionId;
            this.needUpdate = needUpdate;
            this.errorMessage = errorMessage;
        }

        public static CheckResult error(String message) {
            return new CheckResult(null, "", "", false, message);
        }

        public boolean fetchFailed() {
            return errorMessage != null;
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
        public final String remoteVersionId;
        /** 本次同步后的新 tracked mod 列表（调用方应保存到 tracked_mods.txt）。 */
        public final Set<String> newTrackedMods;

        private SyncResult(ModrinthIndex manifest, List<SyncAction> actions, List<String> removed,
                           boolean changed, boolean failed, String errorMessage,
                           int skippedByFilter, int skippedByEnv, String remoteVersionId,
                           Set<String> newTrackedMods) {
            this.manifest = manifest;
            this.actions = actions;
            this.removedOrphans = removed;
            this.changed = changed;
            this.failed = failed;
            this.errorMessage = errorMessage;
            this.skippedByFilter = skippedByFilter;
            this.skippedByEnv = skippedByEnv;
            this.remoteVersionId = remoteVersionId;
            this.newTrackedMods = newTrackedMods;
        }

        public static SyncResult failure(String message) {
            return new SyncResult(null, new ArrayList<>(), new ArrayList<>(), false, true, message, 0, 0, "",
                    new LinkedHashSet<>());
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
