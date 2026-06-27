package com.cyhqw.mcmodupdater.common.modrinth;

import com.cyhqw.mcmodupdater.common.Constants;
import com.cyhqw.mcmodupdater.common.http.HttpUtil;
import com.cyhqw.mcmodupdater.common.model.ModEntry;
import com.cyhqw.mcmodupdater.common.util.ModLog;
import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Modrinth Labrinth v2 API client.
 *
 * <p>Modrinth's public API does not require an API key for read operations.
 * Rate limit: 300 req/s per IP — plenty for our use case.</p>
 *
 * <p>Primary lookup strategy:</p>
 * <ol>
 *   <li>{@code GET /project/{slug|id}} — direct lookup by mod id / slug from jar metadata</li>
 *   <li>{@code GET /search?query=...&facets=...} — fuzzy search by name as a fallback</li>
 *   <li>{@code GET /project/{id}/version?game_versions=[...]&loaders=[...]} — find the
 *       best matching version file</li>
 * </ol>
 */
public final class ModrinthClient {

    private final Gson gson = new Gson();

    public Optional<ModEntry> resolveBySlug(String slugOrId, String gameVersion, String loader) {
        String url = Constants.MODRINTH_API + "/project/" + encode(slugOrId);
        try {
            String body = HttpUtil.getString(url);
            JsonObject project = JsonParser.parseString(body).getAsJsonObject();
            String projectId = project.get("id").getAsString();
            String projectSlug = project.has("slug") && !project.get("slug").isJsonNull()
                    ? project.get("slug").getAsString() : projectId;
            String title = project.has("title") ? project.get("title").getAsString() : projectSlug;
            String projectUrl = "https://modrinth.com/mod/" + projectSlug;

            return pickVersionFile(projectId, projectSlug, title, projectUrl, gameVersion, loader);
        } catch (Exception e) {
            ModLog.info("Modrinth slug lookup failed for '%s': %s", slugOrId, e.getMessage());
            return Optional.empty();
        }
    }

    public Optional<ModEntry> resolveBySearch(String query, String gameVersion, String loader) {
        // facets: [["project_type:mod"], ["categories:fabric"|"categories:forge"], ["versions:1.20.1"]]
        List<String> facets = new ArrayList<>();
        facets.add("[\"project_type:mod\"]");
        if (loader != null) {
            facets.add("[\"categories:" + loader + "\"]");
        }
        if (gameVersion != null) {
            facets.add("[\"versions:" + gameVersion + "\"]");
        }
        String facetsJson = "[" + String.join(",", facets) + "]";
        String url = Constants.MODRINTH_API + "/search?limit=5&query=" + encode(query)
                + "&facets=" + URLEncoder.encode(facetsJson, StandardCharsets.UTF_8);
        try {
            String body = HttpUtil.getString(url);
            JsonObject root = JsonParser.parseString(body).getAsJsonObject();
            JsonArray hits = root.has("hits") ? root.getAsJsonArray("hits") : new JsonArray();
            if (hits.isEmpty()) {
                ModLog.info("Modrinth search returned 0 hits for '%s'", query);
                return Optional.empty();
            }
            JsonObject first = hits.get(0).getAsJsonObject();
            String slug = first.get("slug").getAsString();
            String title = first.get("title").getAsString();
            String projectId = first.get("project_id").getAsString();
            String projectUrl = "https://modrinth.com/mod/" + slug;
            return pickVersionFile(projectId, slug, title, projectUrl, gameVersion, loader);
        } catch (Exception e) {
            ModLog.info("Modrinth search failed for '%s': %s", query, e.getMessage());
            return Optional.empty();
        }
    }

    private Optional<ModEntry> pickVersionFile(String projectId, String projectSlug, String title,
                                               String projectUrl, String gameVersion, String loader) throws IOException, InterruptedException {
        // /project/{id}/version?game_versions=["1.20.1"]&loaders=["fabric"]
        StringBuilder url = new StringBuilder(Constants.MODRINTH_API)
                .append("/project/").append(encode(projectId)).append("/version");
        List<String> params = new ArrayList<>();
        if (gameVersion != null) {
            params.add("game_versions=" + URLEncoder.encode("[\"" + gameVersion + "\"]", StandardCharsets.UTF_8));
        }
        if (loader != null) {
            params.add("loaders=" + URLEncoder.encode("[\"" + loader + "\"]", StandardCharsets.UTF_8));
        }
        if (!params.isEmpty()) url.append("?").append(String.join("&", params));

        String body = HttpUtil.getString(url.toString());
        JsonArray versions = JsonParser.parseString(body).getAsJsonArray();
        if (versions.isEmpty()) {
            ModLog.info("Modrinth project '%s' has no version for mc=%s loader=%s", projectSlug, gameVersion, loader);
            return Optional.empty();
        }
        // First entry is the newest by default.
        JsonObject version = versions.get(0).getAsJsonObject();
        String versionName = version.has("version_number") && !version.get("version_number").isJsonNull()
                ? version.get("version_number").getAsString() : "";
        JsonArray files = version.has("files") ? version.getAsJsonArray("files") : new JsonArray();
        if (files.isEmpty()) return Optional.empty();

        JsonObject primary = files.get(0).getAsJsonObject();
        // Prefer the file marked primary, if any.
        for (JsonElement e : files) {
            JsonObject f = e.getAsJsonObject();
            if (f.has("primary") && f.get("primary").getAsBoolean()) {
                primary = f;
                break;
            }
        }
        String downloadUrl = primary.get("url").getAsString();
        String filename = primary.get("filename").getAsString();
        long size = primary.has("size") ? primary.get("size").getAsLong() : 0L;
        String sha1 = primary.has("hashes") && primary.getAsJsonObject("hashes").has("sha1")
                ? primary.getAsJsonObject("hashes").get("sha1").getAsString() : null;
        String sha512 = primary.has("hashes") && primary.getAsJsonObject("hashes").has("sha512")
                ? primary.getAsJsonObject("hashes").get("sha512").getAsString() : null;

        List<String> gameVersions = new ArrayList<>();
        if (version.has("game_versions")) {
            for (JsonElement gv : version.getAsJsonArray("game_versions")) {
                gameVersions.add(gv.getAsString());
            }
        }
        List<String> loaders = new ArrayList<>();
        if (version.has("loaders")) {
            for (JsonElement l : version.getAsJsonArray("loaders")) {
                loaders.add(l.getAsString());
            }
        }

        ModEntry entry = new ModEntry(projectSlug, title, "modrinth", downloadUrl, filename);
        entry.sourceUrl = projectUrl;
        entry.version = versionName;
        entry.fileSize = size;
        entry.sha1 = sha1;
        entry.sha512 = sha512;
        entry.gameVersions = gameVersions;
        entry.loaders = loaders;
        return Optional.of(entry);
    }

    private static String encode(String s) {
        return URLEncoder.encode(s, StandardCharsets.UTF_8);
    }
}
