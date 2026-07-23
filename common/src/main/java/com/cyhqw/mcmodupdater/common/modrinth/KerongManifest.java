package com.cyhqw.mcmodupdater.common.modrinth;

import com.google.gson.annotations.SerializedName;
import java.util.List;

/**
 * Kerong 整合包更新器的 version.json 数据模型。
 *
 * <p>科融整合包使用独立的 version.json 格式，与 Modrinth 整合包规范不同。
 * 本类用于反序列化该格式，再通过 {@link KerongManifestAdapter} 转换为
 * 标准的 {@link ModrinthIndex}。</p>
 *
 * <pre>
 * {
 *   "version": 1,
 *   "versionName": "v1.0 正式版",
 *   "description": "更新说明",
 *   "releaseDate": "2026-07-05",
 *   "fileCount": 520,
 *   "totalSize": 1234567890,
 *   "filesToKeep": [ "config/..." ],
 *   "configFiles":   [ { "relativePath": "config/...", "size": 123, "md5": "..." } ],
 *   "resourceFiles": [ { "relativePath": "resourcepacks/...", "size": 456, "md5": "..." } ],
 *   "modFiles":      [ { "relativePath": "mods/...", "size": 789, "md5": "..." } ]
 * }
 * </pre>
 */
public final class KerongManifest {

    /**
     * 版本号。Kerong 当前返回整数（如 11），但使用 Number 类型以兼容
     * 未来可能的字符串/浮点变化。适配时通过 {@code String.valueOf()} 转为字符串。
     */
    @SerializedName(value = "version", alternate = { "Version" })
    public Number version;

    @SerializedName(value = "versionName", alternate = { "VersionName" })
    public String versionName;

    @SerializedName(value = "description", alternate = { "Description" })
    public String description;

    @SerializedName(value = "releaseDate", alternate = { "ReleaseDate" })
    public String releaseDate;

    @SerializedName(value = "fileCount", alternate = { "FileCount" })
    public int fileCount;

    @SerializedName(value = "totalSize", alternate = { "TotalSize" })
    public long totalSize;

    @SerializedName(value = "filesToKeep", alternate = { "FilesToKeep" })
    public List<String> filesToKeep;

    @SerializedName(value = "configFiles", alternate = { "ConfigFiles" })
    public List<KerongFile> configFiles;

    @SerializedName(value = "resourceFiles", alternate = { "ResourceFiles" })
    public List<KerongFile> resourceFiles;

    @SerializedName(value = "modFiles", alternate = { "ModFiles" })
    public List<KerongFile> modFiles;

    /** Kerong 整合包中的一个文件条目。 */
    public static final class KerongFile {
        @SerializedName(value = "relativePath", alternate = { "RelativePath" })
        public String relativePath;

        @SerializedName(value = "size", alternate = { "Size" })
        public long size;

        @SerializedName(value = "md5", alternate = { "Md5" })
        public String md5;
    }
}
