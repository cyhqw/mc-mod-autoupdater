package com.cyhqw.mcmodupdater.common.modrinth;

import com.cyhqw.mcmodupdater.common.util.ModLog;
import java.net.URI;
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
 *       <li>{@code relativePath} → {@code path}（原样透传）</li>
 *       <li>{@code md5} → {@code hashes["md5"]}</li>
 *       <li>下载 URL 由 {@code {baseUrl}/files/{relativePath}} 构造</li>
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
        ModLog.info("[KerongAdapter] baseUrl=%s, mapping %d mod + %d config + %d resource files",
                baseUrl,
                kerong.modFiles != null ? kerong.modFiles.size() : 0,
                kerong.configFiles != null ? kerong.configFiles.size() : 0,
                kerong.resourceFiles != null ? kerong.resourceFiles.size() : 0);

        index.files = new ArrayList<>();

        // modFiles
        if (kerong.modFiles != null) {
            for (KerongManifest.KerongFile kf : kerong.modFiles) {
                ModrinthFile mf = toModrinthFile(kf, baseUrl);
                if (mf != null) {
                    index.files.add(mf);
                }
            }
        }

        // configFiles
        if (kerong.configFiles != null) {
            for (KerongManifest.KerongFile kf : kerong.configFiles) {
                ModrinthFile mf = toModrinthFile(kf, baseUrl);
                if (mf != null) {
                    index.files.add(mf);
                }
            }
        }

        // resourceFiles
        if (kerong.resourceFiles != null) {
            for (KerongManifest.KerongFile kf : kerong.resourceFiles) {
                ModrinthFile mf = toModrinthFile(kf, baseUrl);
                if (mf != null) {
                    index.files.add(mf);
                }
            }
        }

        // filesToKeep 以简易方式映射为 dependencies 注解（仅日志记录，暂不参与 tracked 逻辑）
        if (kerong.filesToKeep != null && !kerong.filesToKeep.isEmpty()) {
            ModLog.info("[KerongAdapter] filesToKeep (%d entries) logged but not migrated to sync engine",
                    kerong.filesToKeep.size());
        }

        ModLog.info("[KerongAdapter] Adapted to ModrinthIndex: versionId=%s, name=%s, files=%d",
                index.versionId, index.name, index.files.size());
        return index;
    }

    private static ModrinthFile toModrinthFile(KerongManifest.KerongFile kf, String baseUrl) {
        if (kf == null || kf.relativePath == null || kf.relativePath.isBlank()) {
            return null;
        }

        ModrinthFile mf = new ModrinthFile();
        mf.path = kf.relativePath.replace('\\', '/');
        mf.fileSize = kf.size;

        // 哈希：Kerong 使用 MD5，放入 hashes["md5"]
        if (kf.md5 != null && !kf.md5.isBlank()) {
            mf.hashes = new LinkedHashMap<>();
            mf.hashes.put("md5", kf.md5.trim().toLowerCase());
        }

        // 下载 URL：Kerong 服务端以 {baseUrl}/files/{path} 提供文件
        mf.downloads = new ArrayList<>();
        mf.downloads.add(baseUrl + "files/" + mf.path);

        return mf;
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
