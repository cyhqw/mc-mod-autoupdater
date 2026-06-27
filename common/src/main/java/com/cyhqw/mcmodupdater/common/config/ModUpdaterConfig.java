package com.cyhqw.mcmodupdater.common.config;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;

/**
 * Shared configuration. Persisted as a simple {@code .properties} file at
 * {@code config/mcmodupdater/mcmodupdater.properties}.
 *
 * <p>Platform modules may add additional fields (e.g. loader type is detected
 * at runtime). The fields here are the ones players legitimately want to edit.</p>
 */
public final class ModUpdaterConfig {

    /** ----- Server-side config ----- */

    /** Subdirectory under config/mcmodupdater/ where manifest + missing.json are written. */
    public String outputSubdir = "manifest-output";

    /** Comma-separated list of additional dirs to scan in addition to the instance mods/ dir. */
    public String extraScanDirs = "";

    /** Whether to also scan disabled .jar.disabled files. */
    public boolean scanDisabled = false;

    /** Whether to skip the CurseForge fallback entirely (e.g. if no API key configured). */
    public boolean skipCurseForge = false;

    /** Whether to skip the Modrinth lookup (rarely needed). */
    public boolean skipModrinth = false;

    /** ----- Client-side config ----- */

    /** URL pointing to manifest.json (HTTP/HTTPS). Required for client sync. */
    public String manifestUrl = "";

    /** Whether to auto-sync on game launch. */
    public boolean autoSyncOnLaunch = true;

    /** Whether to remove local mods not in the manifest (clean install). */
    public boolean removeOrphans = false;

    /** Whether to keep a backup (.bak) of removed/overwritten mods. */
    public boolean backupOldMods = true;

    /** Maximum concurrent downloads. */
    public int maxConcurrentDownloads = 4;

    /** Whether to verify SHA1 after download and reject mismatches. */
    public boolean verifyHash = true;

    /** Whether the client should restart-prompt the user after a sync. */
    public boolean promptRestart = true;

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
        c.outputSubdir = props.getProperty("server.outputSubdir", c.outputSubdir);
        c.extraScanDirs = props.getProperty("server.extraScanDirs", c.extraScanDirs);
        c.scanDisabled = Boolean.parseBoolean(props.getProperty("server.scanDisabled", String.valueOf(c.scanDisabled)));
        c.skipCurseForge = Boolean.parseBoolean(props.getProperty("server.skipCurseForge", String.valueOf(c.skipCurseForge)));
        c.skipModrinth = Boolean.parseBoolean(props.getProperty("server.skipModrinth", String.valueOf(c.skipModrinth)));

        c.manifestUrl = props.getProperty("client.manifestUrl", c.manifestUrl);
        c.autoSyncOnLaunch = Boolean.parseBoolean(props.getProperty("client.autoSyncOnLaunch", String.valueOf(c.autoSyncOnLaunch)));
        c.removeOrphans = Boolean.parseBoolean(props.getProperty("client.removeOrphans", String.valueOf(c.removeOrphans)));
        c.backupOldMods = Boolean.parseBoolean(props.getProperty("client.backupOldMods", String.valueOf(c.backupOldMods)));
        c.maxConcurrentDownloads = parseInt(props, "client.maxConcurrentDownloads", c.maxConcurrentDownloads);
        c.verifyHash = Boolean.parseBoolean(props.getProperty("client.verifyHash", String.valueOf(c.verifyHash)));
        c.promptRestart = Boolean.parseBoolean(props.getProperty("client.promptRestart", String.valueOf(c.promptRestart)));
        return c;
    }

    public void save(Path configPath) throws IOException {
        Properties props = new Properties();
        props.setProperty("server.outputSubdir", outputSubdir);
        props.setProperty("server.extraScanDirs", extraScanDirs);
        props.setProperty("server.scanDisabled", String.valueOf(scanDisabled));
        props.setProperty("server.skipCurseForge", String.valueOf(skipCurseForge));
        props.setProperty("server.skipModrinth", String.valueOf(skipModrinth));

        props.setProperty("client.manifestUrl", manifestUrl);
        props.setProperty("client.autoSyncOnLaunch", String.valueOf(autoSyncOnLaunch));
        props.setProperty("client.removeOrphans", String.valueOf(removeOrphans));
        props.setProperty("client.backupOldMods", String.valueOf(backupOldMods));
        props.setProperty("client.maxConcurrentDownloads", String.valueOf(maxConcurrentDownloads));
        props.setProperty("client.verifyHash", String.valueOf(verifyHash));
        props.setProperty("client.promptRestart", String.valueOf(promptRestart));

        Files.createDirectories(configPath.getParent());
        try (var out = Files.newOutputStream(configPath)) {
            props.store(out, "MC Mod Auto-Updater configuration");
        }
    }

    private static int parseInt(Properties p, String key, int def) {
        try {
            return Integer.parseInt(p.getProperty(key, String.valueOf(def)));
        } catch (Exception e) {
            return def;
        }
    }
}
