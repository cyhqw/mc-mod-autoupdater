package com.cyhqw.mcmodupdater.forge;

import com.cyhqw.mcmodupdater.common.config.ModUpdaterConfig;
import com.cyhqw.mcmodupdater.common.syncer.ModSyncer;
import com.cyhqw.mcmodupdater.common.util.ModLog;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.event.lifecycle.FMLClientSetupEvent;
import net.minecraftforge.fml.loading.FMLPaths;

import java.nio.file.Path;

/**
 * Forge client-side event handler.
 *
 * <p>Per the user's spec ("启动时触发更新检查"), this kicks off a background
 * mod sync at client setup time.</p>
 */
@Mod.EventBusSubscriber(modid = McModUpdaterForge.MOD_ID, value = Dist.CLIENT, bus = Mod.EventBusSubscriber.Bus.MOD)
public final class McModUpdaterForgeClient {

    private static final org.apache.logging.log4j.Logger LOGGER = LogManager.getLogger("MCModUpdater");

    private static volatile boolean syncedOnce = false;

    @SubscribeEvent
    public static void onClientSetup(FMLClientSetupEvent event) {
        ModLog.setSink(new Slf4jSink(LOGGER));

        Path configPath = FMLPaths.CONFIGDIR.get().resolve("mcmodupdater/mcmodupdater.properties");
        ModUpdaterConfig config = ModUpdaterConfig.load(configPath);
        try {
            config.save(configPath);
        } catch (Exception e) {
            LOGGER.warn("Failed to save default config: {}", e.getMessage());
        }

        if (!config.autoSyncOnLaunch) {
            LOGGER.info("[MCModUpdater] Auto-sync on launch disabled by config.");
            return;
        }
        if (config.manifestUrl == null || config.manifestUrl.isBlank()) {
            LOGGER.warn("[MCModUpdater] client.manifestUrl is not set — skipping auto-sync. Edit {} and restart.", configPath);
            return;
        }

        event.enqueueWork(() -> {
            if (syncedOnce) return;
            syncedOnce = true;
            Thread t = new Thread(() -> runSync(config), "mcmodupdater-sync");
            t.setDaemon(true);
            t.start();
        });
    }

    private static void runSync(ModUpdaterConfig config) {
        Path modsDir = FMLPaths.GAMEDIR.get().resolve("mods");
        ModSyncer syncer = new ModSyncer(modsDir, config);
        ModSyncer.SyncResult result = syncer.sync();
        // Log a summary — in-game chat feedback requires a ClientPlayer reference,
        // which isn't reliably available at this stage of Forge's init. Players
        // can watch the log output for results, or the next-launch sync will
        // report via the Minecraft main HUD once we hook a later event.
        if (result.failed && result.errorMessage != null) {
            LOGGER.error("[MCModUpdater] Sync failed: {}", result.errorMessage);
        } else {
            LOGGER.info("[MCModUpdater] Sync done: {} downloaded, {} skipped, {} failed, {} orphans removed",
                    result.downloadedCount(), result.skippedCount(), result.failedCount(),
                    result.removedOrphans.size());
        }
    }
}
