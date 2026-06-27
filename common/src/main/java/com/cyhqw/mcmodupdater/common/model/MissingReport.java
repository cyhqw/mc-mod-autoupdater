package com.cyhqw.mcmodupdater.common.model;

import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

/**
 * Top-level missing-mods report file ({@code missing.json}).
 *
 * <pre>
 * {
 *   "meta": { ..., "missing_count": 2 },
 *   "missing": [ MissingEntry, ... ]
 * }
 * </pre>
 */
public final class MissingReport {

    public ManifestMeta meta = new ManifestMeta();
    public List<MissingEntry> missing = new ArrayList<>();

    public MissingReport() {}

    public MissingReport(ManifestMeta meta, List<MissingEntry> missing) {
        this.meta = meta;
        this.missing = missing;
    }

    /** Helper: stamps {@link ManifestMeta#generatedAt} with the current UTC time. */
    public static MissingReport fresh(ManifestMeta meta, List<MissingEntry> missing) {
        meta.generatedAt = DateTimeFormatter.ISO_INSTANT.format(Instant.now());
        meta.missingCount = missing.size();
        meta.modCount = 0;
        return new MissingReport(meta, missing);
    }
}
