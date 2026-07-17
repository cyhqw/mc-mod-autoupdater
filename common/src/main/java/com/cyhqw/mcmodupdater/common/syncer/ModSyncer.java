package com.cyhqw.mcmodupdater.common.syncer;

import com.cyhqw.mcmodupdater.common.config.ModUpdaterConfig;
import com.cyhqw.mcmodupdater.common.http.HttpUtil;
import com.cyhqw.mcmodupdater.common.modrinth.KerongManifest;
import com.cyhqw.mcmodupdater.common.modrinth.KerongManifestAdapter;
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
 * 同步引擎：从 manifest 拉取文件列表，同步本地 {@code mods/}、{@code config/}、
 * {@code resourcepacks/} 三个目录。
 *
 * <p><b>追踪机制</b>：模组只管理"自己下载过的文件"（tracked files），
 * 不会删除玩家自行添加的文件。所有文件按相对路径追踪
 * （如 {@code mods/jei.jar}、{@code config/jei.toml}）。
 * 首次同步时 tracked 为空，只会下载不会删除。</p>
 *
 * <p>支持 Modrinth 和 Kerong 两种清单格式。</p>
 */
public final class ModSyncer {

    private static final Gson GSON = new Gson();
    private static final String JAR_SUFFIX = ".jar";
    private static final String DISABLED_JAR_SUFFIX = ".jar.disabled";

    private static final Set<String> MANAGED_PREFIXES = Set.of("mods/", "config/", "resourcepacks/");

    private final Path gameDir;
    private final Path normalizedGameDir;
    private final Path modsDir;
    private final Path configDir;
    private final Path resourcepacksDir;
    private final ModUpdaterConfig config;
    private final Path trackedFilePath;
    private final Set<String> trackedFiles;

    /** Kerong 格式的 filesToKeep，仅当清单为 Kerong 格式时非空。 */
    private Set<String> keepPaths = Collections.emptySet();

    public ModSyncer(Path gameDir, ModUpdaterConfig config, Path trackedFilePath) {
        this.gameDir = gameDir;
        this.normalizedGameDir = gameDir.normalize();
        this.modsDir = gameDir.resolve("mods").normalize();
        this.configDir = gameDir.resolve("config").normalize();
        this.resourcepacksDir = gameDir.resolve("resourcepacks").normalize();
        this.config = config;
        this.trackedFilePath = trackedFilePath;
        this.trackedFiles = new LinkedHashSet<>(ModUpdaterConfig.loadTrackedMods(trackedFilePath));
    }

    @FunctionalInterface
    public interface ProgressCallback {
        void onProgress(int current, int total, String relativePath, ActionStatus status, String detail);
    }

    /** 仅检查版本，不下载文件。 */
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
        }
        return new CheckResult(index, remote, local, needUpdate, null);
    }

    /**
     * 分析 manifest 与本地文件系统的差异，不下载文件。
     *
     * <p>差异分类：</p>
     * <ul>
     *   <li>ADD: manifest 有但本地没有，将下载</li>
     *   <li>UPDATE: 本地有但哈希不同，将覆盖</li>
     *   <li>KEEP: 本地有且哈希相同，跳过</li>
     *   <li>REMOVE: tracked 中 manifest 不再包含的，将删除</li>
     *   <li>PLAYER_OWNED: 玩家自行添加、不受本模组管理的文件</li>
     * </ul>
     */
    public DiffResult analyzeDiff(ModrinthIndex index) {
        this.keepPaths = extractKeepPaths(index);

        String validationError = validateManifest(index);
        if (validationError != null) {
            return DiffResult.failure(validationError);
        }

        ensureDirectoriesExist();

        Map<String, Path> existing;
        try {
            existing = listExistingFiles();
        } catch (IOException e) {
            return DiffResult.failure("Could not list managed directories: " + e.getMessage());
        }

        ManagedSelection selection = selectManagedFiles(index);

        // 构建 manifest 路径 → ModrinthFile 映射（用于查询远程哈希）
        Map<String, ModrinthFile> manifestFileMap = new HashMap<>();
        for (ModrinthFile f : selection.allFiles) {
            manifestFileMap.put(normalizePath(f.path), f);
        }
        // 也包含 selection.files 中实际入选的文件
        Map<String, ModrinthFile> selectedFileMap = new HashMap<>();
        for (ModrinthFile f : selection.files) {
            selectedFileMap.put(normalizePath(f.path), f);
        }

        List<DiffEntry> toDownload = new ArrayList<>();
        List<DiffEntry> toKeep = new ArrayList<>();

        for (ModrinthFile file : selection.files) {
            String relPath = normalizePath(file.path);
            Path target = gameDir.resolve(relPath).normalize();

            if (!isUnderManagedDir(target)) {
                ModLog.warn("[Diff] skip file outside managed dirs: %s", relPath);
                continue;
            }

            boolean hashMatch = false;
            String localHash = "";
            String remoteHashValue = "";

            if (config.verifyHash) {
                String remoteSha1 = file.sha1();
                if (remoteSha1 != null && !remoteSha1.isBlank()) {
                    remoteHashValue = remoteSha1;
                    if (Files.exists(target)) {
                        try {
                            localHash = HashUtils.sha1(target);
                            hashMatch = localHash.equalsIgnoreCase(remoteSha1);
                        } catch (IOException ignored) {}
                    }
                } else {
                    String remoteMd5 = file.md5();
                    if (remoteMd5 != null && !remoteMd5.isBlank()) {
                        remoteHashValue = remoteMd5;
                        if (Files.exists(target)) {
                            try {
                                localHash = HashUtils.md5(target);
                                hashMatch = localHash.equalsIgnoreCase(remoteMd5);
                            } catch (IOException ignored) {}
                        }
                    }
                }
            }

            if (Files.exists(target) && hashMatch) {
                toKeep.add(new DiffEntry(relPath, DiffAction.KEEP, localHash, remoteHashValue,
                        target.toFile().length(), file.fileSize, firstDownloadUrl(file)));
                removeExistingIgnoreCase(existing, relPath);
            } else {
                String rh = remoteHashValue.isEmpty()
                        ? (file.sha1() != null ? file.sha1() : (file.md5() != null ? file.md5() : ""))
                        : remoteHashValue;
                DiffAction action = Files.exists(target) ? DiffAction.UPDATE : DiffAction.ADD;
                toDownload.add(new DiffEntry(relPath, action,
                        localHash, rh,
                        Files.exists(target) ? target.toFile().length() : 0L,
                        file.fileSize, firstDownloadUrl(file)));
                removeExistingIgnoreCase(existing, relPath);
            }
        }

        // remaining existing：本地有但 manifest 中没有的
        List<DiffEntry> toRemove = new ArrayList<>();
        List<DiffEntry> playerOwned = new ArrayList<>();
        for (Map.Entry<String, Path> e : existing.entrySet()) {
            String relPath = e.getKey();
            if (keepPaths.contains(relPath)) {
                continue; // Kerong filesToKeep，不删
            }
            // filesToKeep 可能为目录前缀，匹配所有以该前缀开头的路径
            if (isKeptByPrefix(relPath)) {
                continue;
            }
            if (trackedFiles.contains(lower(relPath)) && !selection.expectedRelPathKeys.contains(lower(relPath))) {
                toRemove.add(new DiffEntry(relPath, DiffAction.REMOVE,
                        "", "", e.getValue().toFile().length(), 0L, ""));
            } else if (!trackedFiles.contains(lower(relPath))) {
                playerOwned.add(new DiffEntry(relPath, DiffAction.PLAYER_OWNED,
                        "", "", e.getValue().toFile().length(), 0L, ""));
            }
        }

        ModLog.info("[Diff] tracked=%d, toDownload=%d, toKeep=%d, toRemove=%d, playerOwned=%d",
                trackedFiles.size(), toDownload.size(), toKeep.size(), toRemove.size(), playerOwned.size());
        return new DiffResult(toDownload, toKeep, toRemove, playerOwned, null)
                .withSkipped(selection.skippedByFilter, selection.skippedByEnv);
    }

    public SyncResult sync() {
        return sync(null);
    }

    public SyncResult sync(ProgressCallback callback) {
        ensureDirectoriesExist();

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

        this.keepPaths = extractKeepPaths(index);

        String validationError = validateManifest(index);
        if (validationError != null) {
            return SyncResult.failure(validationError);
        }

        Map<String, Path> existing;
        try {
            existing = listExistingFiles();
        } catch (IOException e) {
            return SyncResult.failure("Could not list managed directories: " + e.getMessage());
        }

        ManagedSelection selection = selectManagedFiles(index);
        List<ModrinthFile> toProcess = selection.files;

        int total = toProcess.size();
        notifyProgress(callback, 0, total, "正在准备下载列表...", ActionStatus.SKIPPED, "");

        // 多线程并发下载
        int maxConcurrentDownloads = Math.max(1, config.maxConcurrentDownloads);
        ExecutorService pool = Executors.newFixedThreadPool(maxConcurrentDownloads);
        Map<String, CompletableFuture<SyncAction>> futuresMap = new LinkedHashMap<>();
        for (ModrinthFile file : toProcess) {
            String relPath = normalizePath(file.path);
            futuresMap.put(relPath, CompletableFuture.supplyAsync(() -> {
                try {
                    return syncOne(file);
                } catch (Exception e) {
                    return new SyncAction(file, ActionStatus.FAILED, e.getMessage());
                }
            }, pool));
        }

        AtomicInteger completed = new AtomicInteger(0);
        List<CompletableFuture<Void>> progressFutures = new ArrayList<>();
        for (Map.Entry<String, CompletableFuture<SyncAction>> e : futuresMap.entrySet()) {
            final String relPath = e.getKey();
            CompletableFuture<SyncAction> f = e.getValue();
            CompletableFuture<Void> pf = f.thenAccept(action -> {
                int cur = completed.incrementAndGet();
                notifyProgress(callback, cur, total, relPath, action.status, action.detail);
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

        // 收集 actions
        List<SyncAction> actions = new ArrayList<>();
        Set<String> newTrackedFiles = new LinkedHashSet<>();
        for (ModrinthFile file : toProcess) {
            String relPath = normalizePath(file.path);
            CompletableFuture<SyncAction> f = futuresMap.get(relPath);
            try {
                SyncAction action = f.get();
                actions.add(action);
                if (action.status == ActionStatus.DOWNLOADED || action.status == ActionStatus.SKIPPED) {
                    newTrackedFiles.add(lower(relPath));
                }
            } catch (Exception e) {
                actions.add(new SyncAction(file, ActionStatus.FAILED, "future error: " + e.getMessage()));
            }
        }

        // 删除 tracked 中 manifest 不再包含的
        List<String> removed = new ArrayList<>();
        for (String trackedRelPath : trackedFiles) {
            String actualRelPath = null;
            for (String existingRelPath : existing.keySet()) {
                if (lower(existingRelPath).equals(trackedRelPath)) {
                    actualRelPath = existingRelPath;
                    break;
                }
            }
            if (actualRelPath == null) {
                continue;
            }
            if (selection.expectedRelPathKeys.contains(lower(actualRelPath))) {
                continue;
            }
            if (keepPaths.contains(actualRelPath) || isKeptByPrefix(actualRelPath)) {
                continue;
            }

            Path target = existing.get(actualRelPath);
            try {
                if (config.backupOldMods) {
                    Path bak = target.resolveSibling(target.getFileName().toString() + ".bak");
                    Files.move(target, bak, StandardCopyOption.REPLACE_EXISTING);
                    ModLog.info("Backed up tracked orphan: %s -> %s", actualRelPath, bak);
                } else {
                    Files.delete(target);
                    ModLog.info("Removed tracked orphan: %s", actualRelPath);
                }
                removed.add(actualRelPath);
                newTrackedFiles.remove(trackedRelPath);
            } catch (IOException ex) {
                ModLog.warn("Failed to remove tracked orphan %s: %s", actualRelPath, ex.getMessage());
            }
        }

        // 清理空目录（仅清理因删除文件而变空的目录）
        cleanupEmptyDirs();

        // 保存 tracked files
        try {
            ModUpdaterConfig.saveTrackedMods(trackedFilePath, newTrackedFiles);
            ModLog.info("[Sync] Saved %d tracked files to %s", newTrackedFiles.size(), trackedFilePath);
        } catch (IOException e) {
            ModLog.warn("[Sync] Failed to save tracked files: %s", e.getMessage());
        }

        boolean changed = actions.stream().anyMatch(a -> a.status != ActionStatus.SKIPPED) || !removed.isEmpty();
        boolean failed = actions.stream().anyMatch(a -> a.status == ActionStatus.FAILED);
        String remoteVersion = index.versionId == null ? "" : index.versionId;
        return new SyncResult(index, actions, removed, changed, failed, null,
                selection.skippedByFilter, selection.skippedByEnv, remoteVersion, newTrackedFiles);
    }

    private SyncAction syncOne(ModrinthFile file) {
        String relPath = normalizePath(file.path);
        Path target = gameDir.resolve(relPath).normalize();

        if (!isUnderManagedDir(target)) {
            return new SyncAction(file, ActionStatus.FAILED, "unsafe path: " + file.path);
        }

        Path part = target.resolveSibling(target.getFileName().toString() + ".part").normalize();
        String expectedSha1 = file.sha1();
        String expectedMd5 = (expectedSha1 == null) ? file.md5() : null;

        if (Files.exists(target)) {
            try {
                if (!config.verifyHash) {
                    return new SyncAction(file, ActionStatus.SKIPPED, "exists, hash not verified");
                }
                boolean hashMatches = false;
                if (expectedSha1 != null) {
                    String localSha1 = HashUtils.sha1(target);
                    hashMatches = localSha1.equalsIgnoreCase(expectedSha1);
                } else if (expectedMd5 != null) {
                    String localMd5 = HashUtils.md5(target);
                    hashMatches = localMd5.equalsIgnoreCase(expectedMd5);
                } else {
                    return new SyncAction(file, ActionStatus.SKIPPED, "exists, no hash to verify");
                }
                if (hashMatches) {
                    return new SyncAction(file, ActionStatus.SKIPPED, "hash matches");
                }
                if (config.backupOldMods) {
                    Path bak = target.resolveSibling(target.getFileName() + ".bak");
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
                    Files.createDirectories(target.getParent());
                    Files.deleteIfExists(part);
                    HttpUtil.downloadFile(url, part, config.effectiveHttpTimeoutMs(), 0);
                    if (config.verifyHash) {
                        if (expectedSha1 != null) {
                            String got = HashUtils.sha1(part);
                            if (!got.equalsIgnoreCase(expectedSha1)) {
                                Files.deleteIfExists(part);
                                throw new IOException("sha1 mismatch after download: got=" + got + " expected=" + expectedSha1);
                            }
                        } else if (expectedMd5 != null) {
                            String got = HashUtils.md5(part);
                            if (!got.equalsIgnoreCase(expectedMd5)) {
                                Files.deleteIfExists(part);
                                throw new IOException("md5 mismatch after download: got=" + got + " expected=" + expectedMd5);
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

    // ------------------------------------------------------------------
    // Manifest validation
    // ------------------------------------------------------------------

    private String validateManifest(ModrinthIndex index) {
        if (index == null || index.files == null) {
            return "Manifest was empty or malformed";
        }
        if (index.formatVersion != 1) {
            return "Unsupported manifest formatVersion: " + index.formatVersion + " (expected 1)";
        }
        Set<String> seen = new HashSet<>();
        for (int i = 0; i < index.files.size(); i++) {
            ModrinthFile file = index.files.get(i);
            if (file == null) {
                return "Manifest contains a null file entry at index " + i;
            }
            if (file.path == null || file.path.isBlank()) {
                return "Manifest file entry at index " + i + " is missing path";
            }

            String relPath = normalizePath(file.path);
            if (!isUnderManagedPrefix(relPath)) {
                continue; // 不在管理范围内，跳过不校验
            }

            Path target = gameDir.resolve(relPath).normalize();
            if (!isUnderManagedDir(target)) {
                return "Manifest file escapes managed directories: " + file.path;
            }

            String key = lower(relPath);
            if (!seen.add(key)) {
                return "Manifest contains duplicate path: " + file.path;
            }

            if (file.downloads == null || file.downloads.isEmpty()) {
                return "Manifest entry " + relPath + " has no downloads";
            }
            if (config.verifyHash && isMissingHash(file)) {
                return "Manifest entry " + relPath + " is missing both sha1 and md5 while hash verification is enabled";
            }
        }
        return null;
    }

    // ------------------------------------------------------------------
    // Manifest fetching (Modrinth / Kerong auto-detection)
    // ------------------------------------------------------------------

    private ModrinthIndex fetchManifest(String url) throws IOException, InterruptedException {
        String body = HttpUtil.getString(url, config.effectiveHttpTimeoutMs(), 0);
        JsonObject root = JsonParser.parseString(body).getAsJsonObject();

        if (root.has("formatVersion")) {
            return GSON.fromJson(root, ModrinthIndex.class);
        }

        if (root.has("version") && hasAnyKerongFileList(root)) {
            KerongManifest kerong = GSON.fromJson(root, KerongManifest.class);
            ModLog.info("[Manifest] Detected Kerong-style manifest (version=%d, name=%s, modFiles=%d, configFiles=%d, resourceFiles=%d)",
                    kerong.version, kerong.versionName,
                    kerong.modFiles != null ? kerong.modFiles.size() : 0,
                    kerong.configFiles != null ? kerong.configFiles.size() : 0,
                    kerong.resourceFiles != null ? kerong.resourceFiles.size() : 0);
            return KerongManifestAdapter.adapt(kerong, url);
        }

        throw new IOException("Unknown manifest format: expected 'formatVersion' (Modrinth) "
                + "or 'version'+file list (Kerong). Raw keys: " + root.keySet());
    }

    private static boolean hasAnyKerongFileList(JsonObject root) {
        return root.has("modFiles") || root.has("ModFiles")
                || root.has("configFiles") || root.has("ConfigFiles")
                || root.has("resourceFiles") || root.has("ResourceFiles");
    }

    // ------------------------------------------------------------------
    // Listing existing files
    // ------------------------------------------------------------------

    /** 扫描所有管理的目录，返回 {相对路径 → Path} 映射。 */
    private Map<String, Path> listExistingFiles() throws IOException {
        Map<String, Path> result = new HashMap<>();
        for (Path dir : List.of(modsDir, configDir, resourcepacksDir)) {
            if (!Files.isDirectory(dir)) {
                continue;
            }
            try (Stream<Path> stream = Files.walk(dir)) {
                stream.filter(Files::isRegularFile).forEach(path -> {
                    Path rel = normalizedGameDir.relativize(path);
                    String relPath = rel.toString().replace('\\', '/');
                    result.put(relPath, path);
                });
            }
        }
        return result;
    }

    // ------------------------------------------------------------------
    // File selection from manifest
    // ------------------------------------------------------------------

    private ManagedSelection selectManagedFiles(ModrinthIndex index) {
        Set<String> skip = config.skipModsSet();
        Set<String> only = config.onlyModsSet();
        List<ModrinthFile> selected = new ArrayList<>();
        Set<String> expectedRelPathKeys = new HashSet<>();
        int skippedByFilter = 0;
        int skippedByEnv = 0;

        for (ModrinthFile file : index.files) {
            String relPath = normalizePath(file.path);
            if (!isUnderManagedPrefix(relPath)) {
                continue;
            }
            if (!file.isClientRequired()) {
                skippedByEnv++;
                continue;
            }
            String key = lower(relPath);
            // skip/only 过滤仅针对 mods/ 下的文件（黑/白名单设计时仅考虑 mod）
            if (relPath.startsWith("mods/")) {
                String filename = file.fileName();
                String filenameKey = lower(filename);
                if (!skip.isEmpty() && skip.contains(filenameKey)) {
                    skippedByFilter++;
                    continue;
                }
                if (!only.isEmpty() && !only.contains(filenameKey)) {
                    skippedByFilter++;
                    continue;
                }
            }
            expectedRelPathKeys.add(key);
            selected.add(file);
        }

        return new ManagedSelection(selected, index.files, expectedRelPathKeys, skippedByFilter, skippedByEnv);
    }

    // ------------------------------------------------------------------
    // Keep paths from Kerong filesToKeep
    // ------------------------------------------------------------------

    /** 从 manifest 中提取 filesToKeep（Kerong 格式）。 */
    private Set<String> extractKeepPaths(ModrinthIndex index) {
        // 目前 filesToKeep 仅在 KerongManifestAdapter 适配时以描述性方式传递。
        // 这里从 index 中判断是否包含 Kerong 特有的信息。
        // 简化处理：默认空集，后续可通过配置扩展。
        return Collections.emptySet();
    }

    private boolean isKeptByPrefix(String relPath) {
        for (String prefix : keepPaths) {
            if (prefix.endsWith("/") && relPath.startsWith(prefix)) {
                return true;
            }
            if (relPath.equals(prefix)) {
                return true;
            }
        }
        return false;
    }

    // ------------------------------------------------------------------
    // Directory cleanup helpers
    // ------------------------------------------------------------------

    private void cleanupEmptyDirs() {
        for (Path dir : List.of(configDir, resourcepacksDir)) {
            try {
                try (Stream<Path> stream = Files.walk(dir)) {
                    stream.sorted((a, b) -> -a.compareTo(b))
                            .filter(Files::isDirectory)
                            .forEach(p -> {
                                try {
                                    if (Files.isDirectory(p) && Files.list(p).findAny().isEmpty()) {
                                        Files.delete(p);
                                        ModLog.debug("Removed empty dir: %s", normalizedGameDir.relativize(p));
                                    }
                                } catch (IOException ignored) {}
                            });
                }
            } catch (IOException ignored) {
            }
        }
    }

    // ------------------------------------------------------------------
    // Path safety
    // ------------------------------------------------------------------

    private static boolean isUnderManagedPrefix(String relPath) {
        if (relPath == null || relPath.isEmpty()) {
            return false;
        }
        for (String prefix : MANAGED_PREFIXES) {
            if (relPath.startsWith(prefix)) {
                return true;
            }
        }
        return false;
    }

    private boolean isUnderManagedDir(Path target) {
        Path norm = target.normalize();
        return norm.startsWith(normalizedGameDir.resolve("mods"))
                || norm.startsWith(normalizedGameDir.resolve("config"))
                || norm.startsWith(normalizedGameDir.resolve("resourcepacks"));
    }

    // ------------------------------------------------------------------
    // Small helpers
    // ------------------------------------------------------------------

    private void ensureDirectoriesExist() {
        try {
            Files.createDirectories(modsDir);
            Files.createDirectories(configDir);
            Files.createDirectories(resourcepacksDir);
        } catch (IOException e) {
            ModLog.warn("Could not ensure managed directories exist: %s", e.getMessage());
        }
    }

    private static String normalizePath(String path) {
        if (path == null) {
            return "";
        }
        return path.replace('\\', '/');
    }

    private static String firstDownloadUrl(ModrinthFile file) {
        return (file.downloads != null && !file.downloads.isEmpty()) ? file.downloads.get(0) : "";
    }

    private static void removeExistingIgnoreCase(Map<String, Path> existing, String relPath) {
        String key = null;
        for (String k : existing.keySet()) {
            if (k.equalsIgnoreCase(relPath) || k.equals(relPath)) {
                key = k;
                break;
            }
        }
        if (key != null) {
            existing.remove(key);
        }
    }

    private void notifyProgress(ProgressCallback callback, int current, int total,
                                String relPath, ActionStatus status, String detail) {
        if (callback != null) {
            callback.onProgress(current, total, relPath, status, detail);
        }
    }

    private void deleteQuietly(Path path) {
        try {
            Files.deleteIfExists(path);
        } catch (IOException ignored) {
        }
    }

    private static boolean isMissingHash(ModrinthFile file) {
        String sha1 = file.sha1();
        if (sha1 != null && !sha1.isBlank()) {
            return false;
        }
        String md5 = file.md5();
        return md5 == null || md5.isBlank();
    }

    private static String lower(String value) {
        return value.toLowerCase(Locale.ROOT);
    }

    // ------------------------------------------------------------------
    // Internal data classes
    // ------------------------------------------------------------------

    private static final class ManagedSelection {
        private final List<ModrinthFile> files;             // 实际入选的文件
        private final List<ModrinthFile> allFiles;          // manifest 中所有受管理的文件
        private final Set<String> expectedRelPathKeys;      // 入选文件的路径 key
        private final int skippedByFilter;
        private final int skippedByEnv;

        private ManagedSelection(List<ModrinthFile> files, List<ModrinthFile> allFiles,
                                 Set<String> expectedRelPathKeys, int skippedByFilter, int skippedByEnv) {
            this.files = Collections.unmodifiableList(files);
            this.allFiles = Collections.unmodifiableList(allFiles);
            this.expectedRelPathKeys = Collections.unmodifiableSet(expectedRelPathKeys);
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
        public final String filename;        // 全相对路径，如 "mods/jei.jar"
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
