package com.cyhqw.mcmodupdater.common.modrinth;

import com.cyhqw.mcmodupdater.common.util.ModLog;
import java.io.UnsupportedEncodingException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;

/**
 * 将 Kerong 格式的 version.json 转换为标准的 {@link ModrinthIndex}，
 * 使得 mc-mod-autoupdater 的同步引擎无需改动即可兼容 Kerong 整合包源。
 *
 * <p>转换规则：</p>
 * <ol>
 *   <li>{@code version} (int) → {@code versionId} (String)</li>
 *   <li>{@code versionName} → {@code name}</li>
 *   <li>modFiles / configFiles / resourceFiles 分别转换为 {@link ModrinthFile} 条目：
 *     <ul>
 *       <li>{@code relativePath} → {@code path}（原样透传，正斜杠归一化）</li>
 *       <li>{@code md5} → {@code hashes["md5"]}</li>
 *       <li>下载 URL 由 {@code {baseUrl}/files/{relativePath}} 构造，
 *           并对每个路径段做百分号编码（与 Kerong 原生 encodeUrlPath 行为一致，
 *           支持含中文/空格的模组名）</li>
 *       <li>{@code size} → {@code fileSize}</li>
 *     </ul>
 *   </li>
 * </ol>
 */
public final class KerongManifestAdapter {

    private KerongManifestAdapter() {
    }

    /**
     * 将 Kerong version.json 转换为标准 ModrinthIndex。
     *
     * @param kerong  Kerong 格式的版本清单
     * @param manifestUrl  原始的 manifest URL（用于提取 base URL 构造文件下载地址）
     * @return 适配后的 ModrinthIndex
     */
    public static ModrinthIndex adapt(KerongManifest kerong, String manifestUrl) {
        ModrinthIndex index = new ModrinthIndex();

        // versionId: Kerong 使用整数版本号，转为字符串以兼容
        index.versionId = String.valueOf(kerong.version);

        // name: Kerong 使用 versionName 作为显示名
        index.name = kerong.versionName != null ? kerong.versionName : "";

        // 计算文件下载的 base URL
        String baseUrl = extractBaseUrl(manifestUrl);
        index.files = new ArrayList<>();

        int modCount = addAll(kerong.modFiles, baseUrl, index.files);
        int configCount = addAll(kerong.configFiles, baseUrl, index.files);
        int resourceCount = addAll(kerong.resourceFiles, baseUrl, index.files);

        ModLog.info("[KerongAdapter] baseUrl=%s, mapped %d mod + %d config + %d resource files (%d total)",
                baseUrl, modCount, configCount, resourceCount, index.files.size());

        // filesToKeep：Kerong 用于保护特定文件不被清理。本同步引擎基于 tracked-mods 模型，
        // 仅清理“曾由本模组下载、但新清单中已移除”的模组，玩家自加模组本就不会被删除，
        // 因此 filesToKeep 在当前模型下无需额外迁移，仅记录数量以便排查。
        if (kerong.filesToKeep != null && !kerong.filesToKeep.isEmpty()) {
            ModLog.info("[KerongAdapter] filesToKeep (%d entries) not migrated (tracked-mods model already protects them)",
                    kerong.filesToKeep.size());
        }

        ModLog.info("[KerongAdapter] Adapted to ModrinthIndex: versionId=%s, name=%s, files=%d",
                index.versionId, index.name, index.files.size());
        return index;
    }

    /**
     * 将一组 KerongFile 转换并追加到目标列表，返回成功转换的数量。
     */
    private static int addAll(List<KerongManifest.KerongFile> source, String baseUrl, List<ModrinthFile> dest) {
        if (source == null || source.isEmpty()) {
            return 0;
        }
        int added = 0;
        for (KerongManifest.KerongFile kf : source) {
            ModrinthFile mf = toModrinthFile(kf, baseUrl);
            if (mf != null) {
                dest.add(mf);
                added++;
            }
        }
        return added;
    }

    private static ModrinthFile toModrinthFile(KerongManifest.KerongFile kf, String baseUrl) {
        if (kf == null || kf.relativePath == null || kf.relativePath.isBlank()) {
            return null;
        }

        String path = kf.relativePath.replace('\\', '/');

        // 路径安全：拒绝包含 ".." 段的路径，避免目录穿越（防御性，与同步引擎的 basename 限制互为兜底）
        if (path.contains("/..") || path.startsWith("..") || path.contains("../") || path.equals("..")) {
            ModLog.warn("[KerongAdapter] skip unsafe relativePath (contains '..'): %s", kf.relativePath);
            return null;
        }

        ModrinthFile mf = new ModrinthFile();
        mf.path = path;
        mf.fileSize = kf.size;

        // 哈希：Kerong 使用 MD5，放入 hashes["md5"]（统一小写）
        String md5 = normalizeHash(kf.md5);
        if (md5 != null) {
            mf.hashes = new LinkedHashMap<>();
            mf.hashes.put("md5", md5);
        }

        // 下载 URL：Kerong 服务端以 {baseUrl}/files/{path} 提供文件
        // 对路径逐段百分号编码，以正确处理中文/空格等字符（与 Kerong 原生 encodeUrlPath 一致）
        mf.downloads = new ArrayList<>();
        mf.downloads.add(baseUrl + "files/" + encodeUrlPath(path));

        return mf;
    }

    /**
     * 归一化哈希值：去空白、转小写；空值返回 null。
     */
    private static String normalizeHash(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        return raw.trim().toLowerCase();
    }

    /**
     * 对 URL 路径部分逐段做百分号编码。
     * 与 Kerong 原生行为一致：以 "/" 切分，每段 URLEncoder.encode(UTF-8)，
     * 并将 form 编码产生的 "+" 还原为 "%20"（path 中不允许出现裸 "+" 表示空格）。
     */
    static String encodeUrlPath(String path) {
        if (path == null || path.isEmpty()) {
            return path;
        }
        String[] segments = path.split("/", -1);
        StringBuilder sb = new StringBuilder(path.length() + 8);
        for (int i = 0; i < segments.length; i++) {
            if (i > 0) {
                sb.append('/');
            }
            String seg = segments[i];
            if (seg.isEmpty()) {
                continue;
            }
            try {
                String enc = URLEncoder.encode(seg, StandardCharsets.UTF_8.name());
                sb.append(enc.replace("+", "%20"));
            } catch (UnsupportedEncodingException e) {
                // UTF-8 理论上一定存在
                sb.append(seg);
            }
        }
        return sb.toString();
    }

    /**
     * 从 manifest URL 中提取 base URL。
     * 如 https://kerong.xin/modpack/version.json → https://kerong.xin/modpack/
     */
    static String extractBaseUrl(String manifestUrl) {
        if (manifestUrl == null || manifestUrl.isBlank()) {
            return "";
        }
        // 去掉 query string 和 fragment
        String clean = manifestUrl;
        int qIdx = clean.indexOf('?');
        if (qIdx >= 0) {
            clean = clean.substring(0, qIdx);
        }
        int fIdx = clean.indexOf('#');
        if (fIdx >= 0) {
            clean = clean.substring(0, fIdx);
        }
        // 取到最后一个 / 为止（包含），即目录级 base URL
        int lastSlash = clean.lastIndexOf('/');
        if (lastSlash >= 0) {
            return clean.substring(0, lastSlash + 1);
        }
        return clean + "/";
    }
}
