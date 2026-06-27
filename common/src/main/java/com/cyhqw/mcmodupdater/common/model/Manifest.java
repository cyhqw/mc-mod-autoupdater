package com.cyhqw.mcmodupdater.common.model;

import java.util.ArrayList;
import java.util.List;

/**
 * Top-level manifest file ({@code manifest.json}).
 *
 * <pre>
 * {
 *   "meta": {
 *     "schema_version": 1,
 *     "generated_at": "2026-06-27T15:37:08Z",
 *     "minecraft_version": "1.20.1",
 *     "mod_loader": "fabric",
 *     "mod_count": 12,
 *     "generator": "mc-mod-autoupdater 0.1.0"
 *   },
 *   "mods": [ ModEntry, ... ]
 * }
 * </pre>
 */
public final class Manifest {

    public ManifestMeta meta = new ManifestMeta();
    public List<ModEntry> mods = new ArrayList<>();

    public Manifest() {}

    public Manifest(ManifestMeta meta, List<ModEntry> mods) {
        this.meta = meta;
        this.mods = mods;
    }
}
