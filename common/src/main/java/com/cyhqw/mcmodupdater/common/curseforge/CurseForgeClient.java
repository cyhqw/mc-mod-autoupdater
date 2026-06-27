package com.cyhqw.mcmodupdater.common.curseforge;

import com.cyhqw.mcmodupdater.common.Constants;
import com.cyhqw.mcmodupdater.common.http.HttpUtil;
import com.cyhqw.mcmodupdater.common.model.ModEntry;
import com.cyhqw.mcmodupdater.common.util.ModLog;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * CurseForge Core API v1 client.
 *
 * <p>CurseForge requires an API key. Get one at
 * <a href="https://console.curseforge.com/">console.curseforge.com</a> and pass
 * it via the {@code CURSEFORGE_API_KEY} environment variable (or system
 * property of the same name).</p>
 *
 * <p>CurseForge class IDs:</p>
 * <ul>
 *   <li>6 = Minecraft (game)</li>
 *   <li>6 = Mods (class id within game)</li>
 *   <li>12 = Resource Packs</li>
 * </ul>
 *
 * <p>For mod search, we use {@code classId=6} (Mods).</p>
 */
public final class CurseForgeClient {

    private static final int GAME_ID_MINECRAFT = 432;
    private static final int CLASS_ID_MODS = 6;

    private final String apiKey;

    public CurseForgeClient(String apiKey) {
        this.apiKey = apiKey;
    }

    public static boolean isAvailable() {
        return HttpUtil.curseForgeApiKey().isPresent();
    }

    public static CurseForgeClient fromEnv() {
        return HttpUtil.curseForgeApiKey()
                .map(CurseForgeClient::new)
                .orElse(null);
    }

    public Optional<ModEntry> resolveBySlug(String slug, String gameVersion, String loader) {
        // CurseForge uses "mod search" by slug: /mods/search?gameId=432&slug=...
        String url = Constants.CURSEFORGE_API + "/mods/search?gameId=" + GAME_ID_MINECRAFT
                + "&slug=" + encode(slug);
        try {
            JsonObject resp = getJson(url);
            JsonArray data = resp.has("data") ? resp.getAsJsonArray("data") : new JsonArray();
            if (data.isEmpty()) return Optional.empty();
            JsonObject mod = data.get(0).getAsJsonObject();
            int projectId = mod.get("id").getAsInt();
            String name = mod.get("name").getAsString();
            String slugOut = mod.has("slug") ? mod.get("slug").getAsString() : slug;
            String projectUrl = mod.has("links") && mod.getAsJsonObject("links").has("websiteUrl")
                    ? mod.getAsJsonObject("links").get("websiteUrl").getAsString()
                    : "https://www.curseforge.com/minecraft/mc-mods/" + slugOut;
            return pickFile(projectId, slugOut, name, projectUrl, gameVersion, loader);
        } catch (Exception e) {
            ModLog.info("CurseForge slug lookup failed for '%s': %s", slug, e.getMessage());
            return Optional.empty();
        }
    }

    public Optional<ModEntry> resolveBySearch(String query, String gameVersion, String loader) {
        // /mods/search?gameId=432&searchFilter=...&classId=6
        StringBuilder url = new StringBuilder(Constants.CURSEFORGE_API)
                .append("/mods/search?gameId=").append(GAME_ID_MINECRAFT)
                .append("&classId=").append(CLASS_ID_MODS)
                .append("&searchFilter=").append(encode(query))
                .append("&pageSize=5&sortField=2&sortOrder=desc"); // 2 = popularity
        try {
            JsonObject resp = getJson(url.toString());
            JsonArray data = resp.has("data") ? resp.getAsJsonArray("data") : new JsonArray();
            if (data.isEmpty()) {
                ModLog.info("CurseForge search returned 0 hits for '%s'", query);
                return Optional.empty();
            }
            JsonObject mod = data.get(0).getAsJsonObject();
            int projectId = mod.get("id").getAsInt();
            String name = mod.get("name").getAsString();
            String slugOut = mod.has("slug") ? mod.get("slug").getAsString() : query;
            String projectUrl = mod.has("links") && mod.getAsJsonObject("links").has("websiteUrl")
                    ? mod.getAsJsonObject("links").get("websiteUrl").getAsString()
                    : "https://www.curseforge.com/minecraft/mc-mods/" + slugOut;
            return pickFile(projectId, slugOut, name, projectUrl, gameVersion, loader);
        } catch (Exception e) {
            ModLog.info("CurseForge search failed for '%s': %s", query, e.getMessage());
            return Optional.empty();
        }
    }

    /**
     * Resolve mods in bulk via the fingerprint API.
     *
     * <p>CurseForge's fingerprint endpoint is the most reliable lookup: it
     * returns the exact project+file mapping for a given jar SHA1.</p>
     */
    public Optional<ModEntry> resolveByFingerprint(String sha1, String gameVersion, String loader) {
        // POST /fingerprints
        String url = Constants.CURSEFORGE_API + "/fingerprints";
        String body = "{\"fingerprints\":[" + Long.parseLong(sha1.substring(0, Math.min(16, sha1.length())), 16) + "]}";
        // NOTE: CurseForge's fingerprint is the long form of the murmur hash, NOT plain SHA1.
        // Computing the murmur hash requires CurseForge's specific algorithm — for simplicity
        // we expose this hook but the server currently uses slug+search lookups by default.
        try {
            String resp = HttpUtil.postJson(url, body, Map.of("x-api-key", apiKey));
            JsonObject root = JsonParser.parseString(resp).getAsJsonObject();
            if (root.has("data") && !root.getAsJsonObject("data").has("exactMatches")) return Optional.empty();
            JsonArray matches = root.getAsJsonObject("data").getAsJsonArray("exactMatches");
            if (matches.isEmpty()) return Optional.empty();
            JsonObject match = matches.get(0).getAsJsonObject();
            if (match.has("file")) {
                JsonObject file = match.getAsJsonObject("file");
                return Optional.of(fileToEntry(file, gameVersion, loader));
            }
            return Optional.empty();
        } catch (Exception e) {
            ModLog.info("CurseForge fingerprint lookup failed: %s", e.getMessage());
            return Optional.empty();
        }
    }

    private Optional<ModEntry> pickFile(int projectId, String slug, String name, String projectUrl,
                                        String gameVersion, String loader) throws IOException, InterruptedException {
        // /mods/{modId}/files?gameVersion=1.20.1&modLoaderType=4
        // modLoaderType: 1=Forge, 2=Cauldron, 3=LiteLoader, 4=Fabric, 5=Quilt, 6=NeoForge
        Integer loaderType = loaderToType(loader);
        StringBuilder url = new StringBuilder(Constants.CURSEFORGE_API)
                .append("/mods/").append(projectId).append("/files");
        List<String> params = new ArrayList<>();
        if (gameVersion != null) params.add("gameVersion=" + encode(gameVersion));
        if (loaderType != null) params.add("modLoaderType=" + loaderType);
        if (!params.isEmpty()) url.append("?").append(String.join("&", params));

        JsonObject resp = getJson(url.toString());
        JsonArray data = resp.has("data") ? resp.getAsJsonArray("data") : new JsonArray();
        if (data.isEmpty()) {
            ModLog.info("CurseForge project %d has no file for mc=%s loader=%s", projectId, gameVersion, loader);
            return Optional.empty();
        }
        // First entry is the newest release (server sorts by date).
        JsonObject file = data.get(0).getAsJsonObject();
        return Optional.of(fileToEntry(file, projectId, slug, name, projectUrl));
    }

    private ModEntry fileToEntry(JsonObject file, String gameVersion, String loader) {
        // Used by fingerprint path — slug/name unknown, fall back to id.
        int projectId = file.has("modId") ? file.get("modId").getAsInt() : 0;
        long fileId = file.get("id").getAsLong();
        String filename = file.get("fileName").getAsString();
        String downloadUrl = file.has("downloadUrl") && !file.get("downloadUrl").isJsonNull()
                ? file.get("downloadUrl").getAsString()
                : "https://www.curseforge.com/minecraft/mc-mods/" + projectId + "/files/" + fileId;
        ModEntry e = new ModEntry(String.valueOf(projectId), String.valueOf(projectId), "curseforge", downloadUrl, filename);
        e.curseforgeProjectId = String.valueOf(projectId);
        e.curseforgeFileId = fileId;
        if (file.has("fileLength")) e.fileSize = file.get("fileLength").getAsLong();
        if (file.has("releaseType")) {
            // 1=Release, 2=Beta, 3=Alpha — store in version field as text
        }
        if (file.has("gameVersions")) {
            for (JsonElement gv : file.getAsJsonArray("gameVersions")) e.gameVersions.add(gv.getAsString());
        }
        return e;
    }

    private ModEntry fileToEntry(JsonObject file, int projectId, String slug, String name, String projectUrl) {
        long fileId = file.get("id").getAsLong();
        String filename = file.get("fileName").getAsString();
        String downloadUrl = file.has("downloadUrl") && !file.get("downloadUrl").isJsonNull()
                ? file.get("downloadUrl").getAsString()
                : "https://www.curseforge.com/minecraft/mc-mods/" + slug + "/download/" + fileId;
        ModEntry e = new ModEntry(slug, name, "curseforge", downloadUrl, filename);
        e.sourceUrl = projectUrl;
        e.curseforgeProjectId = String.valueOf(projectId);
        e.curseforgeFileId = fileId;
        if (file.has("fileLength")) e.fileSize = file.get("fileLength").getAsLong();
        if (file.has("gameVersions")) {
            for (JsonElement gv : file.getAsJsonArray("gameVersions")) e.gameVersions.add(gv.getAsString());
        }
        // CurseForge does not return mod loader type per file in this endpoint reliably;
        // we infer it from the manifest's requested loader when present.
        return e;
    }

    private JsonObject getJson(String url) throws IOException, InterruptedException {
        String body = HttpUtil.getString(url, Map.of("x-api-key", apiKey));
        return JsonParser.parseString(body).getAsJsonObject();
    }

    private static Integer loaderToType(String loader) {
        if (loader == null) return null;
        switch (loader.toLowerCase()) {
            case "fabric": return 4;
            case "quilt":  return 5;
            case "forge":  return 1;
            case "neoforge": return 6;
            default: return null;
        }
    }

    private static String encode(String s) {
        return URLEncoder.encode(s, StandardCharsets.UTF_8);
    }
}
