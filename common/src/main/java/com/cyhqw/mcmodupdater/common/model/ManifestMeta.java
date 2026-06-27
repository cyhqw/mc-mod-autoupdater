package com.cyhqw.mcmodupdater.common.model;

import java.time.Instant;
import java.time.format.DateTimeFormatter;

/**
 * Manifest metadata block, shared by {@link Manifest} and {@link MissingReport}.
 *
 * <p>Use {@link #fresh(String, String)} to construct a meta block stamped with
 * the current UTC time.</p>
 */
public final class ManifestMeta {

    public int schemaVersion = 1;
    public String generatedAt;
    public String minecraftVersion;
    public String modLoader;
    public int modCount;
    public int missingCount;
    public String generator;

    public ManifestMeta() {}

    public ManifestMeta(String minecraftVersion, String modLoader) {
        this.schemaVersion = 1;
        this.generatedAt = DateTimeFormatter.ISO_INSTANT.format(Instant.now());
        this.minecraftVersion = minecraftVersion;
        this.modLoader = modLoader;
        this.generator = "mc-mod-autoupdater 0.1.0";
    }

    /** Convenience factory that fills in {@link #generatedAt} and {@link #generator}. */
    public static ManifestMeta fresh(String minecraftVersion, String modLoader) {
        return new ManifestMeta(minecraftVersion, modLoader);
    }
}
