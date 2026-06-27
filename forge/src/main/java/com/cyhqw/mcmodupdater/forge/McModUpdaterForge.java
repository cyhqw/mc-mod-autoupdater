package com.cyhqw.mcmodupdater.forge;

import com.cyhqw.mcmodupdater.common.util.ModLog;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Forge mod entry point. Forge's @Mod annotation drives registration; we
 * don't need a constructor body because all our handlers are wired up via
 * {@link Mod.EventBusSubscriber} annotations.
 *
 * <p>Server-side: {@link McModUpdaterForgeServer} registers commands.</p>
 * <p>Client-side: {@link McModUpdaterForgeClient} triggers launch-time sync.</p>
 */
@Mod(McModUpdaterForge.MOD_ID)
public final class McModUpdaterForge {

    public static final String MOD_ID = "mcmodupdater";
    public static final String MOD_NAME = "MC Mod Auto-Updater";
    public static final String VERSION = "0.1.0";

    private static final Logger LOGGER = LoggerFactory.getLogger("MCModUpdater");

    public McModUpdaterForge() {
        ModLog.setSink(new Slf4jSink(LOGGER));
        LOGGER.info("[MCModUpdater] Forge mod loading. mc={}, forge={}", "1.20.1", "47.x");
        // No-op — registration happens via @Mod.EventBusSubscriber annotations.
    }
}
