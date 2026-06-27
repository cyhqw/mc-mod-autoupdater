package com.cyhqw.mcmodupdater.forge;

import com.cyhqw.mcmodupdater.common.util.ModLog;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import net.minecraftforge.fml.common.Mod;

/**
 * Forge 模组入口。@Mod 注解驱动注册，构造函数无需业务逻辑 —— 所有事件处理器
 * 都通过 {@link Mod.EventBusSubscriber} 注解自动挂载。
 *
 * <p>服务端：{@link McModUpdaterForgeServer} 注册命令。</p>
 * <p>客户端：{@link McModUpdaterForgeClient} 在启动时触发同步。</p>
 */
@Mod(McModUpdaterForge.MOD_ID)
public final class McModUpdaterForge {

    public static final String MOD_ID = "mcmodupdater";
    public static final String MOD_NAME = "MC Mod Auto-Updater";
    public static final String VERSION = "0.1.0";

    private static final Logger LOGGER = LogManager.getLogger("MCModUpdater");

    public McModUpdaterForge() {
        ModLog.setSink(new Log4jSink(LOGGER));
        LOGGER.info("[MCModUpdater] Forge mod loading. mc={}, forge={}", "1.20.1", "47.x");
        // No-op — registration happens via @Mod.EventBusSubscriber annotations.
    }
}
