package com.cyhqw.mcmodupdater.common.syncer;

import com.cyhqw.mcmodupdater.common.config.ModUpdaterConfig;
import com.cyhqw.mcmodupdater.common.http.HttpUtil;
import com.cyhqw.mcmodupdater.common.model.Manifest;
import com.cyhqw.mcmodupdater.common.model.ModEntry;
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
 * Client-side logic: fetch a manifest from a URL, sync the local mods folder
 * to match it.
 *
 * <p>This is the "客户端行为" (client behavior) part of the spec. Triggered at
 * game launch by the platform mods.</p>
 *
 * <p>Sync algorithm:</p>
 * <ol>
 *   <li>Download {@code manifest.json} from the configured URL</li>
 *   <li>For each mod entry:
 *     <ul>
 *       <li>If the expected file (by filename) is already in the mods folder AND
 *           its SHA1 matches → skip</li>
 *       <li>Otherwise download to {@code .part}, verify SHA1, atomically rename</li>
 *       <li>If overwrite needed, optionally backup the old file to {@code .bak}</li>
 *     </ul>
 *   </li>
 *   <li>If {@code removeOrphans=true}, delete any .jar in the mods folder whose
 *       filename isn't in the manifest (with backup if enabled)</li>
 *   <li>Return a {@link SyncResult} so the platform can prompt for restart</li>
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
            return SyncResult.failure("client.manifestUrl is not set in config");
        }

        Manifest manifest;
        try {
            String body = HttpUtil.getString(config.manifestUrl);
            manifest = new Gson().fromJson(JsonParser.parseString(body).getAsJsonObject(), Manifest.class);
        } catch (Exception e) {
            return SyncResult.failure("Failed to fetch manifest from " + config.manifestUrl + ": " + e.getMessage());
        }

        if (manifest == null || manifest.mods == null) {
            return SyncResult.failure("Manifest was empty or malformed");
        }
        if (manifest.meta == null) {
            return SyncResult.failure("Manifest meta block is missing");
        }
        String validationError = validateManifest(manifest);
        if (validationError != null) {
            return SyncResult.failure(validationError);
        }
        ModLog.info("Manifest fetched: %d mods, generated %s",
                manifest.mods.size(), manifest.meta.generatedAt);

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

        for (ModEntry entry : manifest.mods) {
            expectedFilenames.add(entry.filename);
            futures.add(CompletableFuture.supplyAsync(() -> {
                try {
                    concurrency.acquire();
                    try {
                        return syncOne(entry, existing);
                    } finally {
                        concurrency.release();
                    }
                } catch (Exception e) {
                    return new SyncAction(entry, ActionStatus.FAILED, e.getMessage());
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

        // Restart prompt needed if anything changed.
        boolean changed = actions.stream().anyMatch(a -> a.status != ActionStatus.SKIPPED) || !removed.isEmpty();
        boolean failed = actions.stream().anyMatch(a -> a.status == ActionStatus.FAILED);
        return new SyncResult(manifest, actions, removed, changed, failed, null);
    }

    private SyncAction syncOne(ModEntry entry, Map<String, Path> existing) {
        Path target = modsDir.resolve(entry.filename).normalize();
        Path part = modsDir.resolve(entry.filename + ".part").normalize();

        // Skip if file exists with matching hash.
        if (Files.exists(target)) {
            try {
                if (!config.verifyHash || entry.sha1 == null) {
                    ModLog.info("[skip] %s (hash check disabled or missing)", entry.filename);
                    return new SyncAction(entry, ActionStatus.SKIPPED, "exists, hash not verified");
                }
                String localSha1 = HashUtils.sha1(target);
                if (localSha1.equalsIgnoreCase(entry.sha1)) {
                    ModLog.info("[skip] %s (sha1 matches)", entry.filename);
                    return new SyncAction(entry, ActionStatus.SKIPPED, "sha1 matches");
                }
                ModLog.info("[update] %s: local=%s remote=%s", entry.filename, localSha1, entry.sha1);
                if (config.backupOldMods) {
                    Path bak = target.resolveSibling(entry.filename + ".bak");
                    Files.move(target, bak, StandardCopyOption.REPLACE_EXISTING);
                } else {
                    Files.deleteIfExists(target);
                }
            } catch (IOException e) {
                return new SyncAction(entry, ActionStatus.FAILED, "hash check failed: " + e.getMessage());
            }
        }

        // Download
        try {
            Files.deleteIfExists(part);
            HttpUtil.downloadFile(entry.downloadUrl, part);
            if (config.verifyHash && entry.sha1 != null) {
                String got = HashUtils.sha1(part);
                if (!got.equalsIgnoreCase(entry.sha1)) {
                    Files.deleteIfExists(part);
                    return new SyncAction(entry, ActionStatus.FAILED,
                            "sha1 mismatch after download: got=" + got + " expected=" + entry.sha1);
                }
            }
            Files.move(part, target, StandardCopyOption.REPLACE_EXISTING);
            return new SyncAction(entry, ActionStatus.DOWNLOADED, "ok");
        } catch (Exception e) {
            try { Files.deleteIfExists(part); } catch (IOException ignored) {}
            return new SyncAction(entry, ActionStatus.FAILED, e.getMessage());
        }
    }

    private String validateManifest(Manifest manifest) {
        Set<String> filenames = new HashSet<>();
        for (int i = 0; i < manifest.mods.size(); i++) {
            ModEntry entry = manifest.mods.get(i);
            if (entry == null) {
                return "Manifest contains a null mod entry at index " + i;
            }
            if (entry.filename == null || entry.filename.isBlank()) {
                return "Manifest mod entry at index " + i + " is missing filename";
            }
            Path target = modsDir.resolve(entry.filename).normalize();
            if (!target.getParent().equals(modsDir.normalize()) || target.getFileName() == null) {
                return "Manifest filename is unsafe: " + entry.filename;
            }
            String lower = entry.filename.toLowerCase();
            if (!lower.endsWith(".jar") && !lower.endsWith(".jar.disabled")) {
                return "Manifest filename is not a jar: " + entry.filename;
            }
            if (!filenames.add(entry.filename)) {
                return "Manifest contains duplicate filename: " + entry.filename;
            }
            if (entry.downloadUrl == null || entry.downloadUrl.isBlank()) {
                return "Manifest entry " + entry.filename + " is missing downloadUrl";
            }
            if (config.verifyHash && (entry.sha1 == null || entry.sha1.isBlank())) {
                return "Manifest entry " + entry.filename + " is missing sha1 while hash verification is enabled";
            }
        }
        return null;
    }

    // ------------------------------------------------------------------

    public enum ActionStatus { SKIPPED, DOWNLOADED, FAILED }

    public static final class SyncAction {
        public final ModEntry entry;
        public final ActionStatus status;
        public final String detail;

        public SyncAction(ModEntry entry, ActionStatus status, String detail) {
            this.entry = entry;
            this.status = status;
            this.detail = detail;
        }
    }

    public static final class SyncResult {
        public final Manifest manifest;
        public final List<SyncAction> actions;
        public final List<String> removedOrphans;
        public final boolean changed;
        public final boolean failed;
        public final String errorMessage;

        private SyncResult(Manifest manifest, List<SyncAction> actions, List<String> removed,
                           boolean changed, boolean failed, String errorMessage) {
            this.manifest = manifest;
            this.actions = actions;
            this.removedOrphans = removed;
            this.changed = changed;
            this.failed = failed;
            this.errorMessage = errorMessage;
        }

        public static SyncResult failure(String message) {
            return new SyncResult(null, new ArrayList<>(), new ArrayList<>(), false, true, message);
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
