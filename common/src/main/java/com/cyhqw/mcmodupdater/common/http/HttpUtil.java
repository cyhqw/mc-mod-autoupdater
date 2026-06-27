package com.cyhqw.mcmodupdater.common.http;

import com.cyhqw.mcmodupdater.common.Constants;
import com.cyhqw.mcmodupdater.common.util.ModLog;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;
import java.util.Optional;

/**
 * Thin wrapper over {@link HttpClient} used to talk to Modrinth / CurseForge
 * and to download .jar files.
 *
 * <p>The Java 11+ {@code java.net.http} client is available inside Minecraft's
 * bundled JRE (MC 1.20.1 ships with Java 17), so we don't need a 3rd-party
 * HTTP library.</p>
 */
public final class HttpUtil {

    private static final HttpClient CLIENT = HttpClient.newBuilder()
            .connectTimeout(Duration.ofMillis(Constants.HTTP_TIMEOUT_MS))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();

    private HttpUtil() {}

    /** GET a text response, returns the body, throws on non-2xx. */
    public static String getString(String url, Map<String, String> headers) throws IOException, InterruptedException {
        HttpRequest.Builder b = baseGet(url);
        if (headers != null) headers.forEach(b::header);
        HttpResponse<String> resp = CLIENT.send(b.build(), HttpResponse.BodyHandlers.ofString());
        checkResponse(url, resp.statusCode(), resp.body());
        return resp.body();
    }

    public static String getString(String url) throws IOException, InterruptedException {
        return getString(url, null);
    }

    /** GET a file, streaming to {@code dest}. Throws on non-2xx. */
    public static void downloadFile(String url, Path dest) throws IOException, InterruptedException {
        HttpRequest req = baseGet(url).build();
        HttpResponse<InputStream> resp = CLIENT.send(req, HttpResponse.BodyHandlers.ofInputStream());
        if (resp.statusCode() / 100 != 2) {
            String body = new String(resp.body().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
            throw new IOException("HTTP " + resp.statusCode() + " for " + url + " — " + body);
        }
        try (InputStream in = resp.body();
             java.io.OutputStream out = java.nio.file.Files.newOutputStream(dest)) {
            byte[] buf = new byte[16384];
            int n;
            while ((n = in.read(buf)) != -1) {
                out.write(buf, 0, n);
            }
        }
        long size = java.nio.file.Files.size(dest);
        ModLog.info("Downloaded %s (%d bytes) -> %s", url, size, dest);
    }

    /** POST a JSON body and return the response text. */
    public static String postJson(String url, String jsonBody, Map<String, String> headers)
            throws IOException, InterruptedException {
        HttpRequest.Builder b = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .timeout(Duration.ofMillis(Constants.HTTP_TIMEOUT_MS))
                .header("User-Agent", Constants.HTTP_USER_AGENT)
                .header("Accept", "application/json")
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(jsonBody));
        if (headers != null) headers.forEach(b::header);
        HttpResponse<String> resp = CLIENT.send(b.build(), HttpResponse.BodyHandlers.ofString());
        checkResponse(url, resp.statusCode(), resp.body());
        return resp.body();
    }

    private static HttpRequest.Builder baseGet(String url) {
        return HttpRequest.newBuilder()
                .uri(URI.create(url))
                .timeout(Duration.ofMillis(Constants.HTTP_TIMEOUT_MS))
                .header("User-Agent", Constants.HTTP_USER_AGENT)
                .header("Accept", "application/json")
                .GET();
    }

    private static void checkResponse(String url, int status, String body) throws IOException {
        if (status / 100 != 2) {
            String snippet = body == null ? "" : (body.length() > 500 ? body.substring(0, 500) + "..." : body);
            throw new IOException("HTTP " + status + " for " + url + " — " + snippet);
        }
    }

    /** Returns the CurseForge API key, read from the {@code CURSEFORGE_API_KEY} env var or system property. */
    public static Optional<String> curseForgeApiKey() {
        String env = System.getenv(Constants.CURSEFORGE_API_KEY_ENV);
        if (env != null && !env.isBlank()) return Optional.of(env.trim());
        String prop = System.getProperty(Constants.CURSEFORGE_API_KEY_ENV);
        if (prop != null && !prop.isBlank()) return Optional.of(prop.trim());
        return Optional.empty();
    }
}
