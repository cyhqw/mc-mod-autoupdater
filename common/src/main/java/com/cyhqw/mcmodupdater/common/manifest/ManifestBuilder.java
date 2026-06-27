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
import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * Server-side logic: scans the mods directory, queries Modrinth first then
 * CurseForge, and writes {@code manifest.json} + {@code missing.json} to the
 * configured output directory.
 *
 * <p>This is the "服务端行为" (server behavior) part of the spec.</p>
 */
public final class ManifestBuilder {

    private final ModrinthClient modrinth;
    private final CurseForgeClient curseforge;
    private final String gameVersion;
    private final String loader;

    public ManifestBuilder(String gameVersion, String loader) {
        this.gameVersion = gameVersion;
        this.loader = loader;
        this.modrinth = new ModrinthClient();
        this.curseforge = CurseForgeClient.fromEnv();
    }

    public ManifestBuilder(String gameVersion, String loader, boolean skipModrinth, boolean skipCurseForge) {
        this.gameVersion = gameVersion;
        this.loader = loader;
        this.modrinth = skipModrinth ? null : new ModrinthClient();
        this.curseforge = (skipCurseForge || !CurseForgeClient.isAvailable()) ? null : CurseForgeClient.fromEnv();
    }

    /**
     * Build the manifest for the given mods directory.
     *
     * @param modsDir     directory containing .jar files to scan
     * @param outputDir   directory where manifest.json and missing.json will be written
     * @return the in-memory {@link Manifest} that was written
     */
    public BuildResult build(Path modsDir, Path outputDir) throws IOException {
        return build(List.of(modsDir), outputDir, false);
    }

    public BuildResult build(Path modsDir, Path outputDir, boolean includeDisabled) throws IOException {
        return build(List.of(modsDir), outputDir, includeDisabled);
    }

    public BuildResult build(List<Path> scanDirs, Path outputDir, boolean includeDisabled) throws IOException {
        Files.createDirectories(outputDir);

        Map<String, ModMetadata> scannedByFile = new LinkedHashMap<>();
        for (Path scanDir : scanDirs) {
            List<ModMetadata> dirScan = ModScanner.scan(scanDir, includeDisabled);
            ModLog.info("Scanned %d mod(s) in %s", dirScan.size(), scanDir);
            for (ModMetadata meta : dirScan) {
                ModMetadata previous = scannedByFile.putIfAbsent(meta.jarFilename, meta);
                if (previous != null) {
                    ModLog.warn("Duplicate mod filename %s while scanning %s; keeping first occurrence", meta.jarFilename, scanDir);
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
            Optional<ModEntry> resolved = resolveOne(meta);
            if (resolved.isPresent()) {
                ModEntry e = resolved.get();
                // Make sure the jar size from the local file is recorded if the API didn't provide it.
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
     * Resolve one mod against Modrinth first, then CurseForge.
     *
     * <p>Strategy (per the user's "metadata-first, Modrinth-first" requirements):</p>
     * <ol>
     *   <li>Try Modrinth by modId (slug) lookup</li>
     *   <li>Try Modrinth by name search</li>
     *   <li>Try CurseForge by slug lookup</li>
     *   <li>Try CurseForge by name search</li>
     * </ol>
     */
    public Optional<ModEntry> resolveOne(ModMetadata meta) {
        List<MissingEntry.AttemptRecord> attempts = new ArrayList<>();

        if (modrinth != null && meta.modId != null) {
            try {
                Optional<ModEntry> r = modrinth.resolveBySlug(meta.modId, gameVersion, loader);
                if (r.isPresent()) {
                    return r;
                }
                attempts.add(new MissingEntry.AttemptRecord("modrinth:slug:" + meta.modId, false, "no match"));
            } catch (Exception e) {
                attempts.add(new MissingEntry.AttemptRecord("modrinth:slug:" + meta.modId, false, e.getMessage()));
            }
        }

        if (modrinth != null && meta.name != null) {
            try {
                Optional<ModEntry> r = modrinth.resolveBySearch(meta.name, gameVersion, loader);
                if (r.isPresent()) {
                    return r;
                }
                attempts.add(new MissingEntry.AttemptRecord("modrinth:search:" + meta.name, false, "no match"));
            } catch (Exception e) {
                attempts.add(new MissingEntry.AttemptRecord("modrinth:search:" + meta.name, false, e.getMessage()));
            }
        }

        if (curseforge != null && meta.modId != null) {
            try {
                Optional<ModEntry> r = curseforge.resolveBySlug(meta.modId, gameVersion, loader);
                if (r.isPresent()) {
                    return r;
                }
                attempts.add(new MissingEntry.AttemptRecord("curseforge:slug:" + meta.modId, false, "no match"));
            } catch (Exception e) {
                attempts.add(new MissingEntry.AttemptRecord("curseforge:slug:" + meta.modId, false, e.getMessage()));
            }
        }

        if (curseforge != null && meta.name != null) {
            try {
                Optional<ModEntry> r = curseforge.resolveBySearch(meta.name, gameVersion, loader);
                if (r.isPresent()) {
                    return r;
                }
                attempts.add(new MissingEntry.AttemptRecord("curseforge:search:" + meta.name, false, "no match"));
            } catch (Exception e) {
                attempts.add(new MissingEntry.AttemptRecord("curseforge:search:" + meta.name, false, e.getMessage()));
            }
        }

        if (modrinth == null) attempts.add(new MissingEntry.AttemptRecord("modrinth", false, "skipped"));
        if (curseforge == null) {
            attempts.add(new MissingEntry.AttemptRecord("curseforge", false,
                    CurseForgeClient.isAvailable() ? "skipped" : "no API key (CURSEFORGE_API_KEY env var not set)"));
        }

        ModLog.warn("Could not resolve: %s (%s) — attempts=%s", meta.jarFilename, meta.modId, attempts);
        return Optional.empty();
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
}
