package com.cyhqw.mcmodupdater.common.manifest;

import com.cyhqw.mcmodupdater.common.Constants;
import com.cyhqw.mcmodupdater.common.curseforge.CurseForgeClient;
import com.cyhqw.mcmodupdater.common.model.Manifest;
import com.cyhqw.mcmodupdater.common.model.ManifestMeta;
import com.cyhqw.mcmodupdater.common.model.MissingEntry;
import com.cyhqw.mcmodupdater.common.model.MissingReport;
import com.cyhqw.mcmodupdater.common.model.ModEntry;
import com.cyhqw.mcmodupdater.common.modrinth.ModrinthClient;
import com.cyhqw.mcmodupdater.common.scanner.ModMetadata;
import com.cyhqw.mcmodupdater.common.scanner.ModScanner;
import com.cyhqw.mcmodupdater.common.util.HashUtils;
import com.cyhqw.mcmodupdater.common.util.ModLog;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 服务端逻辑：扫描 mods 目录，按 Modrinth 优先、CurseForge 兜底的顺序查询每个模组，
 * 把结果写入 manifest.json + missing.json。
 *
 * <p>构造方式：</p>
 * <ul>
 *   <li>{@link #fromConfig} — 从 {@link com.cyhqw.mcmodupdater.common.config.ModUpdaterConfig}
 *       读取所有可配置参数（推荐路径）</li>
 *   <li>已有的 {@code (gameVersion, loader, skipModrinth, skipCurseForge)} 构造器 — 向后兼容</li>
 * </ul>
 */
public final class ManifestBuilder {

    private final ModrinthClient modrinth;
    private final CurseForgeClient curseforge;
    private final String gameVersion;
    private final String loader;
    private final int httpTimeoutMs;
    private final int maxRetries;

    public ManifestBuilder(String gameVersion, String loader) {
        this(gameVersion, loader, false, false);
    }

    public ManifestBuilder(String gameVersion, String loader, boolean skipModrinth, boolean skipCurseForge) {
        this(gameVersion, loader, skipModrinth, skipCurseForge,
                /*curseForgeApiKey=*/ null, Constants.HTTP_TIMEOUT_MS, /*maxRetries=*/ 0);
    }

    /**
     * 全参数构造器。
     *
     * @param curseForgeApiKey 显式 CurseForge API Key；为 null 则回退到环境变量
     */
    public ManifestBuilder(String gameVersion, String loader,
                           boolean skipModrinth, boolean skipCurseForge,
                           String curseForgeApiKey,
                           int httpTimeoutMs, int maxRetries) {
        this.gameVersion = gameVersion;
        this.loader = loader;
        this.httpTimeoutMs = httpTimeoutMs > 0 ? httpTimeoutMs : Constants.HTTP_TIMEOUT_MS;
        this.maxRetries = Math.max(0, maxRetries);
        this.modrinth = skipModrinth ? null : new ModrinthClient();
        // CurseForge key 优先级：显式传入 > 环境变量 > 不可用
        CurseForgeClient cfClient = null;
        if (!skipCurseForge) {
            String key = (curseForgeApiKey != null && !curseForgeApiKey.isBlank())
                    ? curseForgeApiKey
                    : CurseForgeClient.isAvailable()
                        ? HttpUtilHelper.envKey()
                        : null;
            if (key != null) {
                cfClient = new CurseForgeClient(key);
            }
        }
        this.curseforge = cfClient;
    }

    // ------------------------------------------------------------------
    // 扫描 + 查询
    // ------------------------------------------------------------------

    public BuildResult build(Path modsDir, Path outputDir) throws IOException {
        return build(List.of(modsDir), outputDir, false, true);
    }

    public BuildResult build(Path modsDir, Path outputDir, boolean includeDisabled) throws IOException {
        return build(List.of(modsDir), outputDir, includeDisabled, true);
    }

    public BuildResult build(List<Path> scanDirs, Path outputDir, boolean includeDisabled) throws IOException {
        return build(scanDirs, outputDir, includeDisabled, true);
    }

    /** 完整构造：支持多个扫描目录、是否包含 disabled、是否递归子目录。 */
    public BuildResult build(List<Path> scanDirs, Path outputDir, boolean includeDisabled, boolean scanRecursively)
            throws IOException {
        Files.createDirectories(outputDir);

        Map<String, ModMetadata> scannedByFile = new LinkedHashMap<>();
        for (Path scanDir : scanDirs) {
            List<ModMetadata> dirScan = ModScanner.scan(scanDir, includeDisabled, scanRecursively);
            ModLog.info("Scanned %d mod(s) in %s", dirScan.size(), scanDir);
            for (ModMetadata meta : dirScan) {
                ModMetadata previous = scannedByFile.putIfAbsent(meta.jarFilename, meta);
                if (previous != null) {
                    ModLog.warn("Duplicate mod filename %s while scanning %s; keeping first occurrence",
                            meta.jarFilename, scanDir);
                }
            }
        }
        List<ModMetadata> scanned = new ArrayList<>(scannedByFile.values());
        ModLog.info("Total unique scanned mod(s): %d", scanned.size());

        List<ModEntry> found = new ArrayList<>();
        List<MissingEntry> missing = new ArrayList<>();

        int index = 0;
        for (ModMetadata meta : scanned) {
            index++;
            ModLog.info("[%d/%d] Resolving %s (id=%s, v=%s, loader=%s)",
                    index, scanned.size(), meta.jarFilename, meta.modId, meta.version, meta.loader);
            Optional<ModEntry> resolved = resolveOneWithRetry(meta);
            if (resolved.isPresent()) {
                ModEntry e = resolved.get();
                if (e.fileSize == 0 && meta.jarSize > 0) {
                    e.fileSize = meta.jarSize;
                }
                found.add(e);
            } else {
                MissingEntry miss = new MissingEntry(meta.jarFilename);
                miss.modId = meta.modId;
                miss.version = meta.version;
                try {
                    miss.sha1 = HashUtils.sha1(findScannedFile(scanDirs, meta.jarFilename));
                } catch (Exception ignored) {}
                miss.reason = "not_found_on_modrinth_or_curseforge";
                missing.add(miss);
            }
        }

        ManifestMeta meta = ManifestMeta.fresh(gameVersion, loader);
        meta.modCount = found.size();
        Manifest manifest = new Manifest(meta, found);

        MissingReport report = MissingReport.fresh(ManifestMeta.fresh(gameVersion, loader), missing);

        Gson gson = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();
        Files.writeString(outputDir.resolve("manifest.json"), gson.toJson(manifest));
        Files.writeString(outputDir.resolve("missing.json"), gson.toJson(report));

        ModLog.info("Manifest written: %d found, %d missing -> %s",
                found.size(), missing.size(), outputDir);
        return new BuildResult(manifest, report, outputDir);
    }

    /**
     * 按顺序查询 Modrinth → CurseForge。
     *
     * <p>策略（遵循"元数据优先、Modrinth 优先"的需求）：</p>
     * <ol>
     *   <li>Modrinth slug 查询（按 modId）</li>
     *   <li>Modrinth 名称搜索</li>
     *   <li>CurseForge slug 查询</li>
     *   <li>CurseForge 名称搜索</li>
     * </ol>
     */
    public Optional<ModEntry> resolveOne(ModMetadata meta) {
        return resolveOneWithRetry(meta).map(e -> e);
    }

    private Optional<ModEntry> resolveOneWithRetry(ModMetadata meta) {
        List<MissingEntry.AttemptRecord> attempts = new ArrayList<>();

        if (modrinth != null && meta.modId != null) {
            Optional<ModEntry> r = withRetry("modrinth:slug:" + meta.modId, attempts,
                    () -> modrinth.resolveBySlug(meta.modId, gameVersion, loader));
            if (r.isPresent()) return r;
        }

        if (modrinth != null && meta.name != null) {
            Optional<ModEntry> r = withRetry("modrinth:search:" + meta.name, attempts,
                    () -> modrinth.resolveBySearch(meta.name, gameVersion, loader));
            if (r.isPresent()) return r;
        }

        if (curseforge != null && meta.modId != null) {
            Optional<ModEntry> r = withRetry("curseforge:slug:" + meta.modId, attempts,
                    () -> curseforge.resolveBySlug(meta.modId, gameVersion, loader));
            if (r.isPresent()) return r;
        }

        if (curseforge != null && meta.name != null) {
            Optional<ModEntry> r = withRetry("curseforge:search:" + meta.name, attempts,
                    () -> curseforge.resolveBySearch(meta.name, gameVersion, loader));
            if (r.isPresent()) return r;
        }

        if (modrinth == null) attempts.add(new MissingEntry.AttemptRecord("modrinth", false, "skipped"));
        if (curseforge == null) {
            attempts.add(new MissingEntry.AttemptRecord("curseforge", false,
                    CurseForgeClient.isAvailable() ? "skipped" : "no API key (set server.curseForgeApiKey or CURSEFORGE_API_KEY env var)"));
        }

        ModLog.warn("Could not resolve: %s (%s) — attempts=%s", meta.jarFilename, meta.modId, attempts);
        return Optional.empty();
    }

    /** 对单次查询执行 maxRetries 次重试。失败时记录 attempt，但不抛出。 */
    private Optional<ModEntry> withRetry(String label,
                                         List<MissingEntry.AttemptRecord> attempts,
                                         QueryCall call) {
        Exception lastErr = null;
        for (int attempt = 0; attempt <= maxRetries; attempt++) {
            try {
                Optional<ModEntry> r = call.query();
                if (r.isPresent()) return r;
                attempts.add(new MissingEntry.AttemptRecord(label, false, "no match"));
                return Optional.empty();
            } catch (Exception e) {
                lastErr = e;
                if (attempt < maxRetries) {
                    ModLog.info("Query %s failed (attempt %d/%d): %s — retrying",
                            label, attempt + 1, maxRetries + 1, e.getMessage());
                    try { Thread.sleep(250L * (1L << Math.min(attempt, 5))); }
                    catch (InterruptedException ie) { Thread.currentThread().interrupt(); break; }
                }
            }
        }
        attempts.add(new MissingEntry.AttemptRecord(label, false,
                lastErr != null ? lastErr.getMessage() : "unknown error"));
        return Optional.empty();
    }

    @FunctionalInterface
    public interface QueryCall {
        Optional<ModEntry> query() throws Exception;
    }

    private static Path findScannedFile(List<Path> scanDirs, String filename) {
        for (Path scanDir : scanDirs) {
            Path candidate = scanDir.resolve(filename);
            if (Files.isRegularFile(candidate)) {
                return candidate;
            }
        }
        return scanDirs.get(0).resolve(filename);
    }

    /** Result of a {@link #build} call. */
    public static final class BuildResult {
        public final Manifest manifest;
        public final MissingReport missingReport;
        public final Path outputDir;

        public BuildResult(Manifest manifest, MissingReport missingReport, Path outputDir) {
            this.manifest = manifest;
            this.missingReport = missingReport;
            this.outputDir = outputDir;
        }

        public Path manifestPath() { return outputDir.resolve("manifest.json"); }
        public Path missingPath()  { return outputDir.resolve("missing.json"); }
    }

    /** 内部助手：仅用于避免在 ManifestBuilder 顶部 import HttpUtil。 */
    private static final class HttpUtilHelper {
        static String envKey() {
            return com.cyhqw.mcmodupdater.common.http.HttpUtil.curseForgeApiKey().orElse(null);
        }
    }
}
