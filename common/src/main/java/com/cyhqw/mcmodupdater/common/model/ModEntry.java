package com.cyhqw.mcmodupdater.common.model;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * A single resolved mod entry in the manifest.
 *
 * <p>One entry = one downloadable .jar file. The client uses {@link #downloadUrl}
 * to fetch the file and {@link #sha1} (preferred) or {@link #sha512} to verify
 * integrity.</p>
 */
public final class ModEntry {

    /** Stable mod id, e.g. {@code fabric-api}. */
    public String id;

    /** Human-readable name. */
    public String name;

    /** {@code "modrinth"} or {@code "curseforge"}. */
    public String source;

    /** Project page URL (e.g. https://modrinth.com/mod/fabric-api). */
    public String sourceUrl;

    /** Direct CDN download URL for the .jar. */
    public String downloadUrl;

    /** Expected filename when written to the mods folder. */
    public String filename;

    /** Mod version string, e.g. {@code 0.92.2+1.20.1}. */
    public String version;

    /** File size in bytes (best-effort, used for sanity check on download). */
    public long fileSize;

    /** SHA1 hex digest of the .jar (lowercase, 40 hex chars). */
    public String sha1;

    /** SHA-512 hex digest of the .jar (lowercase, 128 hex chars). */
    public String sha512;

    /** Game versions this file supports, e.g. {@code ["1.20", "1.20.1"]}. */
    public List<String> gameVersions = new ArrayList<>();

    /** Loaders this file supports, e.g. {@code ["fabric", "quilt"]}. */
    public List<String> loaders = new ArrayList<>();

    /** CurseForge-only: project id, kept so the client can rebuild CDN URLs if needed. */
    public String curseforgeProjectId;

    /** CurseForge-only: file id. */
    public long curseforgeFileId;

    /** Default constructor for Gson deserialization. */
    public ModEntry() {}

    public ModEntry(String id, String name, String source, String downloadUrl, String filename) {
        this.id = id;
        this.name = name;
        this.source = source;
        this.downloadUrl = downloadUrl;
        this.filename = filename;
    }

    /** Returns an unmodifiable view of the loaders list. */
    public List<String> getLoaders() {
        return Collections.unmodifiableList(loaders);
    }
}
