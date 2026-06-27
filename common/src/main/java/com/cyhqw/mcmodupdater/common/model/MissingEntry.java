package com.cyhqw.mcmodupdater.common.model;

import java.util.ArrayList;
import java.util.List;

/**
 * A mod that the server could not resolve on Modrinth / CurseForge.
 *
 * <p>Stored in {@code missing.json} so the player knows which mods are
 * private / no longer publicly available.</p>
 */
public final class MissingEntry {

    public String filename;
    public String modId;
    public String version;
    public String sha1;
    public String reason;

    /** Detailed per-source attempt records, for debugging. */
    public List<AttemptRecord> attempts = new ArrayList<>();

    public MissingEntry() {}

    public MissingEntry(String filename) {
        this.filename = filename;
    }

    public static final class AttemptRecord {
        public String source;
        public boolean ok;
        public String detail;

        public AttemptRecord() {}

        public AttemptRecord(String source, boolean ok, String detail) {
            this.source = source;
            this.ok = ok;
            this.detail = detail;
        }
    }
}
