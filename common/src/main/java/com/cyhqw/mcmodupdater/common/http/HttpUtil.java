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

/**
 * HTTP 工具。基于 JDK 内置的 {@link HttpClient}（MC 1.20.1 自带 Java 17）。
 *
 * <p>仅用于客户端：拉取 modrinth.index.json、下载 mod jar 文件。</p>
 *
 * <p>所有请求都支持超时和指数退避重试。重试仅对网络层异常生效，对 HTTP 4xx/5xx 不重试。</p>
 */
public final class HttpUtil {

    private static final HttpClient CLIENT = HttpClient.newBuilder()
            .connectTimeout(Duration.ofMillis(Constants.HTTP_TIMEOUT_MS))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();

    private HttpUtil() {
    }

    /** GET 文本响应。使用默认超时，不重试。 */
    public static String getString(String url) throws IOException, InterruptedException {
        return getString(url, Constants.HTTP_TIMEOUT_MS, 0);
    }

    /** GET 文本响应，自定义超时和重试次数。 */
    public static String getString(String url, int timeoutMs, int retries)
            throws IOException, InterruptedException {
        IOException last = null;
        for (int attempt = 0; attempt <= retries; attempt++) {
            try {
                HttpRequest req = baseGet(url, timeoutMs).build();
                HttpResponse<String> resp = CLIENT.send(req, HttpResponse.BodyHandlers.ofString());
                checkResponse(url, resp.statusCode(), resp.body());
                return resp.body();
            } catch (IOException e) {
                last = e;
                if (attempt < retries) {
                    ModLog.info("HTTP GET %s failed (attempt %d/%d): %s — retrying",
                            url, attempt + 1, retries + 1, e.getMessage());
                    sleepBackoff(attempt);
                }
            }
        }
        throw last;
    }

    /** 下载文件到 dest。使用默认超时，不重试。 */
    public static void downloadFile(String url, Path dest) throws IOException, InterruptedException {
        downloadFile(url, dest, Constants.HTTP_TIMEOUT_MS, 0);
    }

    /** 下载文件到 dest，自定义超时和重试次数。 */
    public static void downloadFile(String url, Path dest, int timeoutMs, int retries)
            throws IOException, InterruptedException {
        IOException last = null;
        for (int attempt = 0; attempt <= retries; attempt++) {
            try {
                HttpRequest req = baseGet(url, timeoutMs).build();
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
                return;
            } catch (IOException e) {
                last = e;
                try {
                    java.nio.file.Files.deleteIfExists(dest);
                } catch (IOException ignored) {
                }
                if (attempt < retries) {
                    ModLog.info("Download %s failed (attempt %d/%d): %s — retrying",
                            url, attempt + 1, retries + 1, e.getMessage());
                    sleepBackoff(attempt);
                }
            }
        }
        throw last;
    }

    private static HttpRequest.Builder baseGet(String url, int timeoutMs) {
        return HttpRequest.newBuilder()
                .uri(URI.create(url))
                .timeout(Duration.ofMillis(timeoutMs > 0 ? timeoutMs : Constants.HTTP_TIMEOUT_MS))
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

    private static void sleepBackoff(int attempt) {
        long ms = 250L * (1L << Math.min(attempt, 5));
        try {
            Thread.sleep(ms);
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
        }
    }
}
