package com.cyhqw.mcmodupdater.common.modrinth;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Modrinth 整合包中的一个文件条目。
 *
 * <pre>
 * {
 *   "path": "mods/sodium.jar",
 *   "hashes": { "sha1": "...", "sha512": "..." },
 *   "downloads": ["https://cdn.modrinth.com/.../sodium.jar"],
 *   "fileSize": 12345,
 *   "env": { "client": "required", "server": "optional" }
 * }
 * </pre>
 */
public final class ModrinthFile {

    /** 文件相对路径，例如 "mods/sodium.jar" 或 "config/foo.toml"。 */
    public String path;

    /** 哈希映射。标准规定至少包含 sha1 和 sha512。 */
    public Map<String, String> hashes = new LinkedHashMap<>();

    /** 下载 URL 列表（按优先级排序，通常只有一个 Modrinth CDN URL）。 */
    public List<String> downloads = new ArrayList<>();

    /** 文件大小（字节）。可选字段。 */
    public long fileSize = 0;

    /**
     * 环境约束。可选。
     * <ul>
     *   <li>{@code "client"}: "required" / "optional" / "unsupported"</li>
     *   <li>{@code "server"}: "required" / "optional" / "unsupported"</li>
     * </ul>
     * 不存在时视为两端都 required。
     */
    public Map<String, String> env;

    /** 便捷方法：返回 sha1，若缺失返回 null。 */
    public String sha1() {
        return hashes != null ? hashes.get("sha1") : null;
    }

    /** 便捷方法：返回 sha512，若缺失返回 null。 */
    public String sha512() {
        return hashes != null ? hashes.get("sha512") : null;
    }

    /** 便捷方法：判断 client 是否需要此文件（"required" 或 "optional" 或 env 缺失）。 */
    public boolean isClientRequired() {
        if (env == null) {
            return true;
        }
        String c = env.get("client");
        if (c == null) {
            return true;
        }
        return "required".equalsIgnoreCase(c) || "optional".equalsIgnoreCase(c);
    }

    /** 便捷方法：判断 path 是否在 mods 目录下。 */
    public boolean isMod() {
        if (path == null) {
            return false;
        }
        String normalized = path.replace('\\', '/');
        return normalized.startsWith("mods/") || normalized.equals("mods");
    }

    /** 便捷方法：返回纯文件名（去掉目录前缀）。 */
    public String fileName() {
        if (path == null) {
            return "";
        }
        String normalized = path.replace('\\', '/');
        int slash = normalized.lastIndexOf('/');
        return slash >= 0 ? normalized.substring(slash + 1) : normalized;
    }
}
