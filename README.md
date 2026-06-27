# MC Mod Auto-Updater

A Minecraft **1.20.1** mod that auto-updates client mods from a JSON manifest. The **server** mod scans its own `mods/` folder, queries **Modrinth first, then CurseForge**, and writes a `manifest.json` + `missing.json` to an output directory. The **client** mod fetches that manifest from a URL at game launch and synchronizes the local `mods/` folder — downloading, updating, and (optionally) removing orphaned jars.

> Builds are provided for **both Fabric and Forge**, sharing a common Java core.

- **Target Minecraft:** 1.20.1
- **Java:** 17
- **Mod Loaders:** Fabric (Loader 0.15.x, Fabric API 0.92.x), Forge (47.x)
- **Source priority:** Modrinth → CurseForge
- **Matching strategy:** jar metadata first (`fabric.mod.json` / `META-INF/mods.toml` / `mcmod.info`), filename fallback
- **License:** MIT

---

## How it works

```
┌──────────────────────────────────────────────────────────────────────────┐
│  Server (your dedicated MC server, runs the server-side mod)              │
│                                                                          │
│   mods/*.jar                                                              │
│      │  scan + read jar metadata (modId, name, version, loader)          │
│      ▼                                                                   │
│   Modrinth lookup ──► (miss?) ──► CurseForge lookup                     │
│      │                          (both miss?)                             │
│      ▼                          ▼                                        │
│   manifest.json             missing.json                                 │
│   (download URLs,           (mods not found on                           │
│    sha1, sha512,             either platform —                           │
│    versions, loaders)        private / custom mods)                      │
│                                                                          │
│   Published to a URL (e.g. GitHub Pages, S3, your CDN)                  │
└──────────────────────────────────────────────────────────────────────────┘
                                  │
                                  ▼  HTTPS GET manifest.json
┌──────────────────────────────────────────────────────────────────────────┐
│  Client (every player's MC client, runs the client-side mod)             │
│                                                                          │
│   At game launch:                                                        │
│   1. Fetch manifest.json from configured URL                             │
│   2. For each entry:                                                     │
│        - exists + sha1 matches → skip                                    │
│        - otherwise download → verify sha1 → atomic rename                │
│   3. Optional: remove orphan .jar files not in the manifest              │
│   4. Prompt player to restart if anything changed                        │
└──────────────────────────────────────────────────────────────────────────┘
```

---

## Repository layout

```
mc-mod-autoupdater/
├── build.gradle              # root build (applies Java plugin to subprojects)
├── settings.gradle           # includes common, fabric, forge
├── gradle.properties         # version pins for MC, Fabric, Forge
├── common/                   # shared Java code, no MC dependency
│   └── src/main/java/com/cyhqw/mcmodupdater/common/
│       ├── Constants.java
│       ├── config/           # ModUpdaterConfig (.properties file)
│       ├── curseforge/       # CurseForgeClient (slug, search, fingerprint)
│       ├── http/             # HttpUtil (java.net.http wrapper)
│       ├── manifest/         # ManifestBuilder (server-side scanner+resolver)
│       ├── model/            # Manifest, ManifestMeta, ModEntry, Missing*
│       ├── modrinth/         # ModrinthClient (slug, search, version files)
│       ├── scanner/          # ModScanner + ModMetadata (jar meta parser)
│       ├── syncer/           # ModSyncer (client-side downloader)
│       └── util/             # HashUtils, JarReader, ModLog
├── fabric/                   # Fabric 1.20.1 mod
│   ├── build.gradle          # uses fabric-loom 1.6
│   └── src/main/
│       ├── java/com/cyhqw/mcmodupdater/fabric/
│       │   ├── McModUpdaterFabricClient.java   # client: launch-time sync
│       │   ├── McModUpdaterFabricServer.java   # server: /mcmodupdater generate
│       │   └── Slf4jSink.java
│       └── resources/
│           ├── fabric.mod.json
│           └── pack.mcmeta
├── forge/                    # Forge 1.20.1 (47.x) mod
│   ├── build.gradle          # uses ForgeGradle 6
│   └── src/main/
│       ├── java/com/cyhqw/mcmodupdater/forge/
│       │   ├── McModUpdaterForge.java          # @Mod entry
│       │   ├── McModUpdaterForgeClient.java    # client: launch-time sync
│       │   ├── McModUpdaterForgeServer.java    # server: /mcmodupdater generate
│       │   ├── Log4jSink.java
│       │   └── Slf4jSink.java
│       └── resources/META-INF/mods.toml
└── docs/
    └── manifest-schema.md    # JSON schema documentation
```

---

## Building

### Prerequisites

- JDK 17 (MC 1.20.1 requires Java 17)
- Internet access (Gradle will download Minecraft mappings, Fabric API, Forge, etc.)

### Build all modules

```bash
# From the repository root:
./gradlew clean build
```

### Build a single loader

```bash
# Fabric
./gradlew :fabric:build

# Forge
./gradlew :forge:build
```

The compiled mod jars will appear under:

- `fabric/build/libs/mc-mod-autoupdater-fabric-1.20.1-0.1.0.jar`
- `forge/build/libs/mc-mod-autoupdater-forge-1.20.1-0.1.0.jar`

Drop the appropriate jar into your server's and client's `mods/` folders.

---

## Server-side usage

### 1. Install the server mod

Drop the Fabric or Forge jar (matching your server's loader) into `<server>/mods/`.

### 2. (Optional) Set the CurseForge API key

CurseForge requires a free API key. Get one at <https://console.curseforge.com/>. Set it as an environment variable before launching the server:

```bash
export CURSEFORGE_API_KEY="your-key-here"
```

Without a key, the server will still work — it just won't be able to query CurseForge, so any mod not on Modrinth will end up in `missing.json`.

### 3. Generate the manifest

Run inside the server console (or op chat):

```
/mcmodupdater generate
```

Output files are written to:

```
<server>/config/mcmodupdater/manifest-output/manifest.json
<server>/config/mcmodupdater/manifest-output/missing.json
```

### 4. Publish the manifest

Upload `manifest.json` (and optionally `missing.json`) to any HTTP(S) host. Common choices:

- **GitHub Pages** — push to a `gh-pages` branch or repo
- **S3 / CloudFront** — `aws s3 cp manifest.json s3://my-bucket/mc/manifest.json`
- **Cloudflare R2 / Netlify / Vercel** — drop in the static directory

The client only needs the URL to `manifest.json`. You don't need to publish `missing.json` — it's for the server admin's reference.

### 5. Re-generate when mods change

Any time you add / update / remove a mod on the server, re-run `/mcmodupdater generate` and re-upload the manifest.

---

## Client-side usage

### 1. Install the client mod

Drop the Fabric or Forge jar (matching your client's loader) into `<client>/mods/`.

### 2. Configure the manifest URL

On first launch, the mod creates `<client>/config/mcmodupdater/mcmodupdater.properties`. Edit it:

```properties
# ----- Server-side config -----
server.outputSubdir=manifest-output
server.extraScanDirs=
server.scanDisabled=false
server.skipCurseForge=false
server.skipModrinth=false

# ----- Client-side config -----
client.manifestUrl=https://your-host.example.com/mc/manifest.json
client.autoSyncOnLaunch=true
client.removeOrphans=false
client.backupOldMods=true
client.maxConcurrentDownloads=4
client.verifyHash=true
client.promptRestart=true
```

Restart the client. The mod will:

1. Fetch the manifest from `client.manifestUrl`
2. For each mod entry: skip if local file's SHA1 matches, otherwise download and verify
3. Optionally remove orphans (if `client.removeOrphans=true`)
4. Display a chat message with the sync summary
5. Prompt you to restart if anything changed

---

## Manifest JSON schema

Full schema is documented in [`docs/manifest-schema.md`](docs/manifest-schema.md). Quick summary:

```jsonc
{
  "meta": {
    "schema_version": 1,
    "generated_at": "2026-06-27T15:37:08Z",
    "minecraft_version": "1.20.1",
    "mod_loader": "fabric",
    "mod_count": 12,
    "generator": "mc-mod-autoupdater 0.1.0"
  },
  "mods": [
    {
      "id": "fabric-api",
      "name": "Fabric API",
      "source": "modrinth",
      "source_url": "https://modrinth.com/mod/fabric-api",
      "download_url": "https://cdn.modrinth.com/.../fabric-api-0.92.2.jar",
      "filename": "fabric-api-0.92.2+1.20.1.jar",
      "version": "0.92.2+1.20.1",
      "file_size": 1234567,
      "sha1": "abcdef0123456789...",
      "sha512": "0123456789abcdef...",
      "game_versions": ["1.20", "1.20.1"],
      "loaders": ["fabric", "quilt"]
    }
    // ...
  ]
}
```

The matching `missing.json`:

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
        { "source": "curseforge", "ok": false, "detail": "skipped" }
      ]
    }
  ]
}
```

---

## Configuration reference

| Property                              | Default              | Description                                                                 |
| ------------------------------------- | -------------------- | --------------------------------------------------------------------------- |
| `server.outputSubdir`                 | `manifest-output`    | Subdir under `config/mcmodupdater/` where manifest/missing are written     |
| `server.extraScanDirs`                | (empty)              | Comma-separated extra dirs to scan in addition to `mods/`                  |
| `server.scanDisabled`                 | `false`              | Include `.jar.disabled` files in the scan                                  |
| `server.skipCurseForge`               | `false`              | Skip the CurseForge fallback entirely                                      |
| `server.skipModrinth`                 | `false`              | Skip Modrinth entirely (rarely useful)                                     |
| `client.manifestUrl`                  | (empty)              | **Required for client.** URL pointing to manifest.json                     |
| `client.autoSyncOnLaunch`             | `true`               | Sync automatically when the client starts                                  |
| `client.removeOrphans`                | `false`              | Delete local mods not in the manifest                                      |
| `client.backupOldMods`                | `true`               | Move overwritten/removed files to `.bak` instead of deleting               |
| `client.maxConcurrentDownloads`       | `4`                  | Concurrent download threads                                                |
| `client.verifyHash`                   | `true`               | Verify SHA1 after download and reject mismatches                           |
| `client.promptRestart`                | `true`               | Tell the player to restart if anything changed                             |

---

## Why Modrinth first?

- Modrinth's API is **public and keyless** for read operations — no setup required.
- Modrinth's slug-based project URLs are stable and human-readable.
- CurseForge is the fallback because it requires an API key and has stricter rate limits.

If you want to flip the order (CF first), the easiest path is to swap the order of the `resolveOne` calls in [`common/src/main/java/com/cyhqw/mcmodupdater/common/manifest/ManifestBuilder.java`](common/src/main/java/com/cyhqw/mcmodupdater/common/manifest/ManifestBuilder.java).

---

## Limitations

- **Hash-based matching is not used for the initial lookup.** CurseForge's fingerprint API uses a non-standard murmur hash; computing it requires extra code we haven't shipped yet. Slug + name search is good enough for the vast majority of public mods.
- **No version-pinning overrides.** The server picks the newest file matching `gameVersion` + `loader`. If you need to pin an older version, edit `manifest.json` by hand after generating it.
- **Client can't show GUI prompts** before the player is in-game. The launch-time sync runs on a background thread and reports via chat once the player joins a world. Failed syncs are logged to the game log.
- **No signature verification.** We verify SHA1 against the manifest, but we don't verify the manifest itself was signed by a trusted publisher. Use HTTPS and keep your manifest host secure.

---

## Contributing

PRs welcome. Please run `./gradlew check` before submitting.

---

## License

MIT — see [LICENSE](LICENSE).
