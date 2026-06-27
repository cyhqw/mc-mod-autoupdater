# Manifest JSON Schema

`manifest.json` is the file the server generates and the client fetches. It is JSON (UTF-8, pretty-printed). The schema is versioned via `meta.schema_version`; the current version is **1**.

## Top-level structure

```jsonc
{
  "meta": { ... },      // ManifestMeta — see below
  "mods":  [ ModEntry, ... ]
}
```

## `meta` — `ManifestMeta`

| Field               | Type    | Required | Description                                                |
| ------------------- | ------- | -------- | ---------------------------------------------------------- |
| `schema_version`    | int     | yes      | Always `1` for this version of the format                  |
| `generated_at`      | string  | yes      | ISO-8601 UTC timestamp, e.g. `2026-06-27T15:37:08Z`        |
| `minecraft_version` | string  | yes      | The server's Minecraft version, e.g. `1.20.1`              |
| `mod_loader`        | string  | yes      | `fabric`, `quilt`, `forge`, or `neoforge`                  |
| `mod_count`         | int     | yes      | Number of entries in the `mods` array                      |
| `missing_count`     | int     | no       | Only present in `missing.json`                             |
| `generator`         | string  | yes      | Always `mc-mod-autoupdater <version>`                     |

## `mods[]` — `ModEntry`

| Field                    | Type        | Required | Description                                                              |
| ------------------------ | ----------- | -------- | ----------------------------------------------------------------------- |
| `id`                     | string      | yes      | Stable mod id — Modrinth slug or CurseForge project slug/id             |
| `name`                   | string      | yes      | Human-readable display name                                             |
| `source`                 | string      | yes      | `"modrinth"` or `"curseforge"`                                          |
| `source_url`             | string      | no       | Project page URL                                                        |
| `download_url`           | string      | yes      | Direct CDN URL to the .jar                                              |
| `filename`               | string      | yes      | Expected filename when written to the client's `mods/` folder           |
| `version`                | string      | no       | Mod version string, e.g. `0.92.2+1.20.1`                                |
| `file_size`              | number      | no       | File size in bytes (used for sanity check)                              |
| `sha1`                   | string      | no       | SHA1 hex digest (40 lowercase hex chars) — used by client to verify     |
| `sha512`                 | string      | no       | SHA-512 hex digest (128 lowercase hex chars) — informational            |
| `game_versions`          | string[]    | no       | Game versions this file supports, e.g. `["1.20", "1.20.1"]`             |
| `loaders`                | string[]    | no       | Loaders this file supports, e.g. `["fabric", "quilt"]`                  |
| `curseforge_project_id`  | string      | no       | CurseForge-only: numeric project id                                     |
| `curseforge_file_id`     | number      | no       | CurseForge-only: numeric file id                                        |

## `missing.json` structure

```jsonc
{
  "meta": { ..., "missing_count": 2 },
  "missing": [
    {
      "filename": "my-private-mod-1.0.jar",
      "mod_id": "my_private_mod",
      "version": "1.0",
      "sha1": "abcdef0123456789...",
      "reason": "not_found_on_modrinth_or_curseforge",
      "attempts": [
        { "source": "modrinth:slug:my_private_mod", "ok": false, "detail": "no match" },
        { "source": "modrinth:search:My Private Mod", "ok": false, "detail": "no match" },
        { "source": "curseforge", "ok": false, "detail": "no API key (CURSEFORGE_API_KEY env var not set)" }
      ]
    }
  ]
}
```

The `attempts` array records every lookup the server made for that mod, in order. Use it to debug why a particular mod wasn't resolved.

## Backward compatibility

The client tolerates missing optional fields. If a future schema bump removes or renames a field, the client should ignore unknown fields (Gson does this by default).

When the schema does need to break compatibility (e.g. rename a required field), bump `schema_version` and have the client refuse to load manifests with a newer schema than it understands.
