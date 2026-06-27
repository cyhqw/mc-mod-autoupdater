package com.cyhqw.mcmodupdater.forge;

import com.cyhqw.mcmodupdater.common.config.ModUpdaterConfig;
import com.cyhqw.mcmodupdater.common.syncer.ModSyncer;
import com.cyhqw.mcmodupdater.common.util.ModLog;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.event.lifecycle.FMLClientSetupEvent;
import net.minecraftforge.fml.loading.FMLPaths;

import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Forge 客户端事件处理器。
 *
 * <p>纯客户端模组：启动时从配置的 URL 拉取 Modrinth 整合包格式的 modrinth.index.json，
 * 同步本地 mods 目录。若 {@code periodicSyncMinutes > 0}，则按周期重复同步。</p>
 *
 * <p>仅在客户端Dist 上注册事件，专用服不会加载此类。</p>
 */
@Mod.EventBusSubscriber(modid = McModUpdaterForge.MOD_ID, value = Dist.CLIENT, bus = Mod.EventBusSubscriber.Bus.MOD)
public final class McModUpdaterForgeClient {

    private static final Logger LOGGER = LogManager.getLogger("MCModUpdater");

    private static volatile boolean syncedOnce = false;
    private static volatile ModUpdaterConfig config;
    private static final AtomicLong lastPeriodicSyncTime = new AtomicLong(0);

    @SubscribeEvent
    public static void onClientSetup(FMLClientSetupEvent event) {
        ModLog.setSink(new Log4jSink(LOGGER));

        Path configPath = FMLPaths.CONFIGDIR.get().resolve("mcmodupdater/mcmodupdater.properties");
        config = ModUpdaterConfig.load(configPath);
        try {
            config.save(configPath);
        } catch (Exception e) {
            LOGGER.warn("Failed to save default config: {}", e.getMessage());
        }

        if (config.manifestUrl == null || config.manifestUrl.isBlank()) {
            LOGGER.warn("[MCModUpdater] manifestUrl is not set — skipping sync. Edit {} and restart.", configPath);
            return;
        }

        if (config.autoSyncOnLaunch) {
            event.enqueueWork(() -> {
                if (syncedOnce) return;
                syncedOnce = true;
                Thread t = new Thread(() -> runSync(config), "mcmodupdater-sync");
                t.setDaemon(true);
                t.start();
            });
        } else {
            LOGGER.info("[MCModUpdater] Auto-sync on launch disabled by config.");
        }

        // 周期同步（通过客户端 tick 事件驱动）
        if (config.periodicSyncMinutes > 0) {
            LOGGER.info("[MCModUpdater] Periodic sync every {} minute(s).", config.periodicSyncMinutes);
            net.minecraftforge.common.MinecraftForge.EVENT_BUS.addListener(
                    (TickEvent.ClientTickEvent evt) -> {
                        if (evt.phase != TickEvent.Phase.END) return;
                        ModUpdaterConfig cfg = config;
                        if (cfg == null) return;
                        long intervalMs = cfg.periodicSyncMinutes * 60_000L;
                        long now = System.currentTimeMillis();
                        long last = lastPeriodicSyncTime.get();
                        if (now - last < intervalMs) return;
                        if (!lastPeriodicSyncTime.compareAndSet(last, now)) return;
                        Thread t = new Thread(() -> runSync(cfg), "mcmodupdater-periodic");
                        t.setDaemon(true);
                        t.start();
                    });
        }
    }

    private static void runSync(ModUpdaterConfig cfg) {
        Path modsDir = cfg.resolveModsDir(FMLPaths.GAMEDIR.get());
        ModSyncer syncer = new ModSyncer(modsDir, cfg);
        ModSyncer.SyncResult result = syncer.sync();
        // Forge 客户端在 setup 阶段没有 ClientPlayer 引用，结果只输出到日志。
        if (result.failed && result.errorMessage != null) {
            LOGGER.error("[MCModUpdater] Sync failed: {}", result.errorMessage);
        } else {
            LOGGER.info("[MCModUpdater] Sync done: {} downloaded, {} skipped, {} failed, {} filtered, {} env-skipped, {} orphans removed",
                    result.downloadedCount(), result.skippedCount(), result.failedCount(),
                    result.skippedByFilter, result.skippedByEnv, result.removedOrphans.size());
        }
    }
}
