package com.cyhqw.mcmodupdater.common;

/**
 * Shared constants and version info used across the project.
 */
public final class Constants {

    public static final String MOD_ID = "mcmodupdater";
    public static final String MOD_NAME = "MC Mod Auto-Updater";
    public static final String MOD_VERSION = "0.1.0";
    public static final String GENERATOR = "mc-mod-autoupdater " + MOD_VERSION;

    /** Manifest schema version. Bump on breaking changes. */
    public static final int SCHEMA_VERSION = 1;

    /** Subdirectory (relative to the game dir / server dir) where we store config + output. */
    public static final String CONFIG_DIR = "config/mcmodupdater";

    /** Default subdirectory (inside CONFIG_DIR) where the server writes the manifest. */
    public static final String DEFAULT_OUTPUT_SUBDIR = "manifest-output";

    /** Default mods directory name. */
    public static final String MODS_DIR = "mods";

    /** HTTP timeout (ms). */
    public static final int HTTP_TIMEOUT_MS = 15_000;

    /** User-Agent string sent to Modrinth / CurseForge. */
    public static final String HTTP_USER_AGENT = "mc-mod-autoupdater/" + MOD_VERSION + " (https://github.com/cyhqw/mc-mod-autoupdater)";

    /** Modrinth API base. */
    public static final String MODRINTH_API = "https://api.modrinth.com/v2";

    /** CurseForge API base. */
    public static final String CURSEFORGE_API = "https://api.curseforge.com/v1";

    /** CurseForge requires an API key (https://console.curseforge.com/). */
    public static final String CURSEFORGE_API_KEY_ENV = "CURSEFORGE_API_KEY";

    private Constants() {
        throw new UnsupportedOperationException("constants");
    }
}
