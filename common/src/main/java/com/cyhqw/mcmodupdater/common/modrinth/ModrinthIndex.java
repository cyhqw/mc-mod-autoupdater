package com.cyhqw.mcmodupdater.common.modrinth;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Modrinth 整合包索引（modrinth.index.json）的 Java 数据模型。
 *
 * <p>严格遵循 Modrinth 整合包规范：
 * <a href="https://docs.modrinth.com/docs/modpacks/format_definition/">format definition</a>。</p>
 *
 * <p>结构：</p>
 * <pre>
 * {
 *   "formatVersion": 1,
 *   "game": "minecraft",
 *   "versionId": "1.0",
 *   "name": "My Modpack",
 *   "files": [
 *     {
 *       "path": "mods/sodium.jar",
 *       "hashes": { "sha1": "...", "sha512": "..." },
 *       "downloads": ["https://cdn.modrinth.com/.../sodium.jar"],
 *       "fileSize": 12345,
 *       "env": { "client": "required", "server": "optional" }
 *     }
 *   ],
 *   "dependencies": { "minecraft": "1.20.1", "fabric-loader": "0.15.11" }
 * }
 * </pre>
 *
 * <p>本模组仅同步 path 以 {@code mods/} 开头、且 env.client 为 "required" 或 "optional" 的条目。</p>
 */
public final class ModrinthIndex {

    /** 整合包格式版本，固定为 1。 */
    public int formatVersion = 1;

    /** 游戏名，目前固定为 "minecraft"。 */
    public String game = "minecraft";

    /** 整合包版本号（自定义字符串）。 */
    public String versionId = "";

    /** 整合包显示名。 */
    public String name = "";

    /** 文件列表。 */
    public List<ModrinthFile> files = new ArrayList<>();

    /**
     * 依赖映射。键为依赖类型（如 "minecraft"、"fabric-loader"、"forge"），值为版本字符串。
     * 对应 manifest 中的 game version 和 loader 信息。
     */
    public Map<String, String> dependencies = new LinkedHashMap<>();

    /**
     * 需保留的路径前缀/文件（来自 Kerong filesToKeep）。
     * 标准 Modrinth 格式不包含此字段，留空即可。同步引擎据此跳过这些路径的清理，
     * 即便它们曾由本模组下载但已从清单移除（例如整个 config/ 目录被标记为 keep）。
     */
    public List<String> filesToKeep;
}
