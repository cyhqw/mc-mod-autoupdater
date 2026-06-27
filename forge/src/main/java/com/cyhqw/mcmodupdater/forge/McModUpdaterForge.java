package com.cyhqw.mcmodupdater.forge;

import com.cyhqw.mcmodupdater.common.util.ModLog;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import net.minecraftforge.fml.common.Mod;

/**
 * Forge 模组入口。
 *
 * <p>纯客户端模组：所有业务逻辑由 {@link McModUpdaterForgeClient} 在客户端Dist 上
 * 通过 {@link Mod.EventBusSubscriber} 注册，本类只负责把日志桥接到 Log4j。</p>
 *
 * <p>不注册任何服务端逻辑，不在专用服上运行。</p>
 */
@Mod(McModUpdaterForge.MOD_ID)
public final class McModUpdaterForge {

    public static final String MOD_ID = "mcmodupdater";
    public static final String MOD_NAME = "MC Mod Auto-Updater";
    public static final String VERSION = "0.2.0";

    private static final Logger LOGGER = LogManager.getLogger("MCModUpdater");

    public McModUpdaterForge() {
        ModLog.setSink(new Log4jSink(LOGGER));
        LOGGER.info("[MCModUpdater] Forge client mod loading. (client-only)");
    }
}
