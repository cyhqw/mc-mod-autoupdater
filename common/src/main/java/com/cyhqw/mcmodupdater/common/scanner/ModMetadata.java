package com.cyhqw.mcmodupdater.common.scanner;

import com.cyhqw.mcmodupdater.common.util.JarReader;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Extracted mod metadata from a .jar file.
 *
 * <p>Resolution priority (matches the user's "metadata-first" requirement):</p>
 * <ol>
 *   <li>{@code fabric.mod.json} (Fabric / Quilt) — preferred, has id+version+depends</li>
 *   <li>{@code META-INF/mods.toml} (Forge / NeoForge) — TOML-ish, we parse the [[mods]] block</li>
 *   <li>{@code mcmod.info} (legacy Forge) — JSON</li>
 *   <li>Filename parsing as a last-resort fallback</li>
 * </ol>
 */
public final class ModMetadata {

    public final String modId;
    public final String name;
    public final String version;
    /** "fabric" | "quilt" | "forge" | "neoforge" | null */
    public final String loader;
    public final List<String> dependencies;
    public final String jarFilename;
    public final long jarSize;

    public ModMetadata(String modId, String name, String version, String loader,
                       List<String> dependencies, String jarFilename, long jarSize) {
        this.modId = modId;
        this.name = name;
        this.version = version;
        this.loader = loader;
        this.dependencies = dependencies;
        this.jarFilename = jarFilename;
        this.jarSize = jarSize;
    }

    public String displayKey() {
        return modId != null ? modId : jarFilename;
    }

    @Override
    public String toString() {
        return String.format("Mod[%s/%s, v=%s, loader=%s, file=%s]",
                modId, name, version, loader, jarFilename);
    }

    // ------------------------------------------------------------------
    // Parsing
    // ------------------------------------------------------------------

    public static ModMetadata fromJar(Path jarPath) {
        String filename = jarPath.getFileName().toString();
        long size;
        try {
            size = java.nio.file.Files.size(jarPath);
        } catch (Exception e) {
            size = 0L;
        }

        // 1) fabric.mod.json
        try {
            String json = JarReader.readEntry(jarPath, "fabric.mod.json");
            if (json != null) {
                return parseFabricModJson(json, filename, size);
            }
        } catch (Exception ignored) {}

        // 2) META-INF/mods.toml
        try {
            String toml = JarReader.readEntry(jarPath, "META-INF/mods.toml");
            if (toml != null) {
                return parseModsToml(toml, filename, size);
            }
        } catch (Exception ignored) {}

        // 3) mcmod.info
        try {
            String info = JarReader.readEntry(jarPath, "mcmod.info");
            if (info != null) {
                return parseMcmodInfo(info, filename, size);
            }
        } catch (Exception ignored) {}

        // 4) Filename fallback
        return fromFilename(filename, size);
    }

    private static ModMetadata parseFabricModJson(String json, String filename, long size) {
        JsonObject o = JsonParser.parseString(json).getAsJsonObject();
        String id = getStr(o, "id");
        String name = getStr(o, "name");
        String version = getStr(o, "version");
        String loader = "fabric";
        List<String> deps = new ArrayList<>();
        if (o.has("depends") && o.get("depends").isJsonObject()) {
            for (String key : o.getAsJsonObject("depends").keySet()) {
                if (!"minecraft".equals(key) && !"java".equals(key) && !"fabricloader".equals(key)) {
                    deps.add(key);
                }
            }
        }
        if (o.has("depends") && o.get("depends").isJsonObject()
                && o.getAsJsonObject("depends").has("quilt_loader")) {
            loader = "quilt";
        }
        return new ModMetadata(id, name, version, loader, deps, filename, size);
    }

    private static ModMetadata parseModsToml(String toml, String filename, long size) {
        // Minimal TOML-ish parser for the [[mods]] block.
        String modId = null, name = null, version = null, loader = "forge";
        List<String> deps = new ArrayList<>();
        // Detect NeoForge marker
        if (toml.contains("neoForgeVersion") || toml.contains("neoforge")) {
            loader = "neoforge";
        }
        // Find first [[mods]] block
        int blockStart = toml.indexOf("[[mods]]");
        if (blockStart >= 0) {
            String block = toml.substring(blockStart);
            // Stop at next [[ ]] section if any
            int nextBlock = block.indexOf("\n[[", 1);
            if (nextBlock > 0) block = block.substring(0, nextBlock);
            // Parse lines
            for (String line : block.split("\\r?\\n")) {
                String trimmed = line.split("#", 2)[0].trim();
                if (trimmed.isEmpty() || trimmed.startsWith("[")) continue;
                int eq = trimmed.indexOf('=');
                if (eq <= 0) continue;
                String key = trimmed.substring(0, eq).trim();
                String value = trimmed.substring(eq + 1).trim();
                value = stripQuotes(value);
                switch (key) {
                    case "modId":       modId = value; break;
                    case "displayName": name = value; break;
                    case "version":     version = value; break;
                    case "dependencies":
                        // e.g. 'dependencies = "required-after:forge@[47,);required-after:minecraft"'
                        for (String dep : value.split(";")) {
                            dep = dep.trim();
                            if (dep.isEmpty()) continue;
                            Matcher m = DEP_PATTERN.matcher(dep);
                            if (m.find()) deps.add(m.group(1));
                        }
                        break;
                }
            }
        }
        if (modId == null && name == null) return fromFilename(filename, size);
        return new ModMetadata(modId, (name != null) ? name : modId, version, loader, deps, filename, size);
    }

    private static final Pattern DEP_PATTERN = Pattern.compile("(?:required|optional|after|before|broken)?-?(?:after|before)?:(\\w+)");

    private static ModMetadata parseMcmodInfo(String json, String filename, long size) {
        try {
            JsonElement root = JsonParser.parseString(json);
            JsonObject mod = root.isJsonArray() && !root.getAsJsonArray().isEmpty()
                    ? root.getAsJsonArray().get(0).getAsJsonObject()
                    : (root.isJsonObject() && root.getAsJsonObject().has("modList")
                        ? root.getAsJsonObject().getAsJsonArray("modList").get(0).getAsJsonObject()
                        : null);
            if (mod == null) return fromFilename(filename, size);
            String modId = getStr(mod, "modid");
            String name = getStr(mod, "name");
            String version = getStr(mod, "version");
            List<String> deps = new ArrayList<>();
            if (mod.has("dependencies") && mod.get("dependencies").isJsonArray()) {
                for (JsonElement d : mod.getAsJsonArray("dependencies")) {
                    deps.add(d.getAsString());
                }
            }
            return new ModMetadata(modId, (name != null) ? name : modId, version, "forge", deps, filename, size);
        } catch (Exception e) {
            return fromFilename(filename, size);
        }
    }

    // Filename patterns: modname-1.2.3.jar / modname-1.2.3+1.20.1.jar / [1.20.1]modname-1.2.3.jar
    private static final Pattern FN_RE = Pattern.compile(
            "^(?:\\[[^\\]]*\\]\\s*)?(?<name>[A-Za-z][A-Za-z0-9_\\- ]+?)[\\-_](?<ver>\\d[\\w.\\-+]*?)\\.jar$",
            Pattern.CASE_INSENSITIVE);

    public static ModMetadata fromFilename(String filename, long size) {
        Matcher m = FN_RE.matcher(filename);
        if (m.matches()) {
            String name = m.group("name");
            String version = m.group("ver");
            String id = name.toLowerCase().replace('-', '_').replace(' ', '_').replaceAll("[^a-z0-9_]", "");
            return new ModMetadata(id, name, version, null, new ArrayList<>(), filename, size);
        }
        String stem = filename.replaceAll("\\.jar$", "");
        String id = stem.toLowerCase().replaceAll("[^a-z0-9_]+", "_").replaceAll("^_+|_+$", "");
        return new ModMetadata(id, stem, "", null, new ArrayList<>(), filename, size);
    }

    private static String getStr(JsonObject o, String key) {
        return o.has(key) && !o.get(key).isJsonNull() ? o.get(key).getAsString() : null;
    }

    private static String stripQuotes(String s) {
        if (s == null) return null;
        s = s.trim();
        if (s.length() >= 2 && ((s.startsWith("\"") && s.endsWith("\"")) || (s.startsWith("'") && s.endsWith("'")))) {
            return s.substring(1, s.length() - 1);
        }
        return s;
    }
}
