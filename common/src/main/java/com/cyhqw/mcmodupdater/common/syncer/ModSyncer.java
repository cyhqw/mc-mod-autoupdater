package com.cyhqw.mcmodupdater.common.syncer;

import com.cyhqw.mcmodupdater.common.config.ModUpdaterConfig;
import com.cyhqw.mcmodupdater.common.http.HttpUtil;
import com.cyhqw.mcmodupdater.common.modrinth.ModrinthFile;
import com.cyhqw.mcmodupdater.common.modrinth.ModrinthIndex;
import com.cyhqw.mcmodupdater.common.util.HashUtils;
import com.cyhqw.mcmodupdater.common.util.ModLog;
import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ExecutionException;
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

    private static final Gson GSON = new Gson();
    private static final String JAR_SUFFIX = ".jar";
    private static final String DISABLED_JAR_SUFFIX = ".jar.disabled";

    private final Path modsDir;
    private final Path normalizedModsDir;
    private final ModUpdaterConfig config;
    private final Path trackedModsPath;
    private final Set<String> trackedMods;

    public ModSyncer(Path modsDir, ModUpdaterConfig config, Path trackedModsPath) {
        this.modsDir = modsDir;
        this.normalizedModsDir = modsDir.normalize();
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
            index = fetchManifest(url);
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
            // 版本号相同时，通过哈希校验检测文件内容是否被静默更新
            if (!needUpdate && config.verifyHash) {
                needUpdate = hasContentChanged(index);
                if (needUpdate) {
                    ModLog.info("Version matches (%s) but content hash mismatch detected, forcing update", remote);
                }
            }
        }
        return new CheckResult(index, remote, local, needUpdate, null);
    }

    /**
     * 当版本号未变时，通过哈希校验检测文件内容是否发生变化。
     *
     * <p>遍历清单中所有选中的 mod 文件，对本地存在的文件计算哈希并与清单中的远端哈希对比。
     * 若任一文件本地缺失或哈希不匹配，返回 true（需要更新）。
     * 仅在 {@code config.verifyHash} 为 true 时调用。</p>
     *
     * @param index 已拉取的清单
     * @return true 表示检测到内容变化（文件缺失或哈希不匹配）
     */
    private boolean hasContentChanged(ModrinthIndex index) {
        ManifestSelection selection = selectManifestMods(index);
        int checked = 0, mismatched = 0, missing = 0;
        for (ModrinthFile file : selection.files) {
            String filename = file.fileName();
            Path target = modsDir.resolve(filename).normalize();
            if (!target.startsWith(modsDir)) {
                continue;
            }
            if (!Files.exists(target)) {
                missing++;
                continue;
            }
            String remoteHash = file.sha1();
            String algo;
            if (remoteHash != null && !remoteHash.isBlank()) {
                algo = "sha1";
            } else {
                remoteHash = file.md5();
                if (remoteHash == null || remoteHash.isBlank()) {
                    continue; // 无哈希信息，跳过
                }
                algo = "md5";
            }
            try {
                String localHash = "sha1".equals(algo) ? HashUtils.sha1(target) : HashUtils.md5(target);
                checked++;
                if (!localHash.equalsIgnoreCase(remoteHash)) {
                    mismatched++;
                    ModLog.info("[ContentCheck] hash mismatch: %s (local=%s remote=%s algo=%s)",
                            filename, localHash.substring(0, 8), remoteHash.substring(0, 8), algo);
                }
            } catch (IOException ignored) {
            }
        }
        boolean changed = missing > 0 || mismatched > 0;
        ModLog.info("[ContentCheck] %d checked, %d missing, %d mismatch, %d total -> %s",
                checked, missing, mismatched, selection.files.size(), changed ? "UPDATE NEEDED" : "all match");
        return changed;
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
        String validationError = validateManifest(index);
        if (validationError != null) {
            return DiffResult.failure(validationError);
        }

        try {
            Files.createDirectories(modsDir);
        } catch (IOException e) {
            return DiffResult.failure("Could not create mods dir: " + e.getMessage());
        }

        Map<String, Path> existing;
        try {
            existing = listExistingMods();
        } catch (IOException e) {
            return DiffResult.failure("Could not list mods dir: " + e.getMessage());
        }

        ManifestSelection selection = selectManifestMods(index);
        List<DiffEntry> toDownload = new ArrayList<>();
        List<DiffEntry> toKeep = new ArrayList<>();

        for (ModrinthFile file : selection.files) {
            String filename = file.fileName();
            Path target = modsDir.resolve(filename).normalize();
            String remoteSha1 = file.sha1();
            long remoteSize = file.fileSize;
            String downloadUrl = firstDownloadUrl(file);

            if (Files.exists(target)) {
                boolean hashMatch = false;
                String localHash = "";
                String remoteHashValue = "";

                if (config.verifyHash) {
                    if (remoteSha1 != null && !remoteSha1.isBlank()) {
                        remoteHashValue = remoteSha1;
                        try {
                            localHash = HashUtils.sha1(target);
                            hashMatch = localHash.equalsIgnoreCase(remoteSha1);
                        } catch (IOException ignored) {}
                    }
                }

                if (hashMatch) {
                    toKeep.add(new DiffEntry(filename, DiffAction.KEEP, localHash, remoteHashValue,
                            target.toFile().length(), remoteSize, downloadUrl));
                } else {
                    toDownload.add(new DiffEntry(filename, DiffAction.UPDATE,
                            localHash, remoteHashValue,
                            target.toFile().length(), remoteSize, downloadUrl));
                }
                removeExistingIgnoreCase(existing, filename);
            } else {
                String remoteHashValue = remoteSha1 != null ? remoteSha1 : "";
                toDownload.add(new DiffEntry(filename, DiffAction.ADD,
                        "", remoteHashValue, 0L, remoteSize, downloadUrl));
            }
        }

        // remaining existing 是本地有但 manifest 没有的
        // 只删除 tracked 的（模组自己下载过的），玩家手动加的不删
        List<DiffEntry> toRemove = new ArrayList<>();
        List<DiffEntry> playerOwned = new ArrayList<>();  // 玩家手动加的，不删
        for (Map.Entry<String, Path> e : existing.entrySet()) {
            String filenameKey = lower(e.getKey());
            if (trackedMods.contains(filenameKey) && !selection.expectedFilenameKeys.contains(filenameKey)) {
                // tracked 但 manifest 不再有 → 删除
                toRemove.add(new DiffEntry(e.getKey(), DiffAction.REMOVE,
                        "", "", e.getValue().toFile().length(), 0L, ""));
            } else if (!trackedMods.contains(filenameKey)) {
                // 玩家手动加的 → 不删，记录为 PLAYER_OWNED
                playerOwned.add(new DiffEntry(e.getKey(), DiffAction.PLAYER_OWNED,
                        "", "", e.getValue().toFile().length(), 0L, ""));
            }
        }

        ModLog.info("[Diff] tracked=%d, toDownload=%d, toKeep=%d, toRemove=%d, playerOwned=%d",
                trackedMods.size(), toDownload.size(), toKeep.size(), toRemove.size(), playerOwned.size());
        return new DiffResult(toDownload, toKeep, toRemove, playerOwned, null)
                .withSkipped(selection.skippedByFilter, selection.skippedByEnv);
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
            return SyncResult.failure("Could not create mods dir: " + e.getMessage());
        }

        // 立即回调一次，让 GUI 显示"正在获取清单..."
        notifyProgress(callback, 0, 1, "正在获取清单...", ActionStatus.SKIPPED, "");

        String url = config.effectiveManifestUrl();
        ModrinthIndex index;
        try {
            index = fetchManifest(url);
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

        Map<String, Path> existing;
        try {
            existing = listExistingMods();
        } catch (IOException e) {
            return SyncResult.failure("Could not list mods dir: " + e.getMessage());
        }

        // 收集要处理的文件列表
        ManifestSelection selection = selectManifestMods(index);
        List<ModrinthFile> toProcess = selection.files;

        int total = toProcess.size();
        notifyProgress(callback, 0, total, "正在准备下载列表...", ActionStatus.SKIPPED, "");

        // 多线程并发下载
        int maxConcurrentDownloads = Math.max(1, config.maxConcurrentDownloads);
        ExecutorService pool = Executors.newFixedThreadPool(maxConcurrentDownloads);
        Map<String, CompletableFuture<SyncAction>> futuresMap = new LinkedHashMap<>();
        for (ModrinthFile file : toProcess) {
            futuresMap.put(file.fileName(), CompletableFuture.supplyAsync(() -> {
                try {
                    return syncOne(file);
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
                notifyProgress(callback, cur, total, filename, action.status, action.detail);
            });
            progressFutures.add(pf);
        }
        try {
            CompletableFuture.allOf(progressFutures.toArray(new CompletableFuture[0])).get();
        } catch (InterruptedException e) {
            ModLog.warn("Progress wait interrupted: %s", e.getMessage());
            Thread.currentThread().interrupt();
        } catch (ExecutionException e) {
            ModLog.warn("Progress wait failed: %s", e.getMessage());
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
                    newTrackedMods.add(lower(file.fileName()));
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
            if (actualFilename == null) {
                continue;  // 本地已没有这个文件
            }
            if (selection.expectedFilenameKeys.contains(lower(actualFilename))) {
                continue;  // manifest 还有，不删
            }

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
                selection.skippedByFilter, selection.skippedByEnv, remoteVersion, newTrackedMods);
    }

    private SyncAction syncOne(ModrinthFile file) {
        String filename = file.fileName();
        Path target = modsDir.resolve(filename).normalize();
        Path part = modsDir.resolve(filename + ".part").normalize();

        if (!isDirectChildOfModsDir(target)) {
            return new SyncAction(file, ActionStatus.FAILED, "unsafe path: " + file.path);
        }

        String expectedSha1 = file.sha1();

        if (Files.exists(target)) {
            try {
                if (!config.verifyHash) {
                    return new SyncAction(file, ActionStatus.SKIPPED, "exists, hash not verified");
                }
                if (expectedSha1 != null) {
                    String localSha1 = HashUtils.sha1(target);
                    if (localSha1.equalsIgnoreCase(expectedSha1)) {
                        return new SyncAction(file, ActionStatus.SKIPPED, "sha1 matches");
                    }
                } else {
                    // no hash to compare with, skip by existence
                    return new SyncAction(file, ActionStatus.SKIPPED, "exists, no hash to verify");
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
                    if (config.verifyHash) {
                        if (expectedSha1 != null) {
                            String got = HashUtils.sha1(part);
                            if (!got.equalsIgnoreCase(expectedSha1)) {
                                Files.deleteIfExists(part);
                                throw new IOException("sha1 mismatch after download: got=" + got + " expected=" + expectedSha1);
                            }
                        }
                    }
                    Files.move(part, target, StandardCopyOption.REPLACE_EXISTING);
                    return new SyncAction(file, ActionStatus.DOWNLOADED, "ok");
                } catch (Exception e) {
                    lastErr = e;
                    deleteQuietly(part);
                }
            }
            if (attempt < retries) {
                try {
                    Thread.sleep(250L * (1L << Math.min(attempt, 5)));
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        }
        return new SyncAction(file, ActionStatus.FAILED,
                lastErr != null ? lastErr.getMessage() : "download failed");
    }

    private String validateManifest(ModrinthIndex index) {
        if (index == null || index.files == null) {
            return "Manifest was empty or malformed";
        }
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
            if (!file.isMod()) {
                continue;
            }
            String filename = file.fileName();
            if (filename.isEmpty() || filename.equals("mods")) {
                return "Manifest file path is malformed: " + file.path;
            }
            Path target = modsDir.resolve(filename).normalize();
            if (!isDirectChildOfModsDir(target)) {
                return "Manifest filename escapes mods dir: " + file.path;
            }
            String filenameKey = lower(filename);
            if (!isJarModName(filenameKey)) {
                return "Manifest filename is not a jar: " + filename;
            }
            if (!filenames.add(filenameKey)) {
                return "Manifest contains duplicate filename: " + filename;
            }
            if (file.downloads == null || file.downloads.isEmpty()) {
                return "Manifest entry " + filename + " has no downloads";
            }
            if (config.verifyHash && isMissingHash(file)) {
                return "Manifest entry " + filename + " is missing sha1 while hash verification is enabled";
            }
        }
        return null;
    }

    private ModrinthIndex fetchManifest(String url) throws IOException, InterruptedException {
        if (url == null || url.isBlank()) {
            throw new IOException("manifestUrl 未配置：请在 config/mcmodupdater/mcmodupdater.properties 中设置 manifestUrl");
        }
        String body = HttpUtil.getString(url, config.effectiveHttpTimeoutMs(), 0);
        JsonObject root = JsonParser.parseString(body).getAsJsonObject();
        if (!root.has("formatVersion")) {
            throw new IOException("Unknown manifest format: expected 'formatVersion' (Modrinth). Raw keys: " + root.keySet());
        }
        return GSON.fromJson(root, ModrinthIndex.class);
    }

    private Map<String, Path> listExistingMods() throws IOException {
        Map<String, Path> existing = new HashMap<>();
        try (Stream<Path> stream = Files.list(modsDir)) {
            stream.filter(Files::isRegularFile).forEach(path -> {
                String filename = path.getFileName().toString();
                if (isJarModName(lower(filename))) {
                    existing.put(filename, path);
                }
            });
        }
        return existing;
    }

    private ManifestSelection selectManifestMods(ModrinthIndex index) {
        Set<String> skip = config.skipModsSet();
        Set<String> only = config.onlyModsSet();
        List<ModrinthFile> selected = new ArrayList<>();
        Set<String> expectedFilenameKeys = new HashSet<>();
        int skippedByFilter = 0;
        int skippedByEnv = 0;

        for (ModrinthFile file : index.files) {
            if (!file.isMod()) {
                continue;
            }
            if (!file.isClientRequired()) {
                skippedByEnv++;
                continue;
            }
            String filenameKey = lower(file.fileName());
            if (!skip.isEmpty() && skip.contains(filenameKey)) {
                skippedByFilter++;
                continue;
            }
            if (!only.isEmpty() && !only.contains(filenameKey)) {
                skippedByFilter++;
                continue;
            }
            expectedFilenameKeys.add(filenameKey);
            selected.add(file);
        }

        return new ManifestSelection(selected, expectedFilenameKeys, skippedByFilter, skippedByEnv);
    }

    private static String firstDownloadUrl(ModrinthFile file) {
        return (file.downloads != null && !file.downloads.isEmpty()) ? file.downloads.get(0) : "";
    }

    private static void removeExistingIgnoreCase(Map<String, Path> existing, String filename) {
        String keyToRemove = null;
        for (String existingFilename : existing.keySet()) {
            if (existingFilename.equalsIgnoreCase(filename)) {
                keyToRemove = existingFilename;
                break;
            }
        }
        if (keyToRemove != null) {
            existing.remove(keyToRemove);
        }
    }

    private void notifyProgress(ProgressCallback callback, int current, int total,
                                String filename, ActionStatus status, String detail) {
        if (callback != null) {
            callback.onProgress(current, total, filename, status, detail);
        }
    }

    private void deleteQuietly(Path path) {
        try {
            Files.deleteIfExists(path);
        } catch (IOException ignored) {
        }
    }

    private boolean isDirectChildOfModsDir(Path target) {
        Path parent = target.getParent();
        return parent != null && parent.equals(normalizedModsDir);
    }

    private static boolean isJarModName(String filename) {
        return filename.endsWith(JAR_SUFFIX) || filename.endsWith(DISABLED_JAR_SUFFIX);
    }

    /**
     * 检查文件条目是否缺少可用的 sha1 哈希值。
     */
    private static boolean isMissingHash(ModrinthFile file) {
        String sha1 = file.sha1();
        return sha1 == null || sha1.isBlank();
    }

    private static String lower(String value) {
        return value.toLowerCase(Locale.ROOT);
    }

    private static final class ManifestSelection {
        private final List<ModrinthFile> files;
        private final Set<String> expectedFilenameKeys;
        private final int skippedByFilter;
        private final int skippedByEnv;

        private ManifestSelection(List<ModrinthFile> files, Set<String> expectedFilenameKeys,
                                  int skippedByFilter, int skippedByEnv) {
            this.files = Collections.unmodifiableList(files);
            this.expectedFilenameKeys = Collections.unmodifiableSet(expectedFilenameKeys);
            this.skippedByFilter = skippedByFilter;
            this.skippedByEnv = skippedByEnv;
        }
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

        public static DiffResult failure(String message) {
            return new DiffResult(new ArrayList<>(), new ArrayList<>(), new ArrayList<>(), new ArrayList<>(),
                    message);
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
