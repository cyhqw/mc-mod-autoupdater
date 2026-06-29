package com.cyhqw.mcmodupdater.common;

/**
 * 共享常量。
 *
 * <p>本模组纯客户端运行：从给定 URL 拉取 Modrinth 整合包格式的 modrinth.index.json，
 * 同步本地 mods 目录。不包含任何服务端逻辑。</p>
 */
public final class Constants {

    public static final String MOD_ID = "mcmodupdater";
    public static final String MOD_NAME = "MC Mod Auto-Updater";
    public static final String MOD_VERSION = "0.3.0";

    /** 配置文件相对路径（位于游戏目录下）。 */
    public static final String CONFIG_PATH = "config/mcmodupdater/mcmodupdater.properties";

    /** HTTP 默认超时（毫秒）。 */
    public static final int HTTP_TIMEOUT_MS = 15_000;

    /** User-Agent，发送给 Modrinth CDN。 */
    public static final String HTTP_USER_AGENT = "mc-mod-autoupdater/" + MOD_VERSION
            + " (https://github.com/cyhqw/mc-mod-autoupdater)";

    private Constants() {
        throw new UnsupportedOperationException("constants");
    }
}
