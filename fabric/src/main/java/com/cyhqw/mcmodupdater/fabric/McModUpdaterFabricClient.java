package com.cyhqw.mcmodupdater.fabric;

import com.cyhqw.mcmodupdater.common.config.ModUpdaterConfig;
import com.cyhqw.mcmodupdater.common.syncer.ModSyncer;
import com.cyhqw.mcmodupdater.common.util.ModLog;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.MinecraftClient;
import net.minecraft.text.Text;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Fabric 客户端入口。
 *
 * <p>启动时触发一次同步（可通过 {@code client.autoSyncOnLaunch} 关闭）。
 * 若 {@code client.periodicSyncMinutes > 0}，则后台按周期重复同步。</p>
 */
public class McModUpdaterFabricClient implements ClientModInitializer {

    public static final Logger LOGGER = LoggerFactory.getLogger("MCModUpdater");

    private static boolean syncedOnce = false;
    private static ModUpdaterConfig config;
    private static final AtomicLong lastPeriodicSyncTime = new AtomicLong(0);

    @Override
    public void onInitializeClient() {
        ModLog.setSink(new Slf4jSink(LOGGER));

        Path gameDir = FabricLoader.getInstance().getGameDir();
        Path configPath = gameDir.resolve("config").resolve("mcmodupdater").resolve("mcmodupdater.properties");
        config = ModUpdaterConfig.load(configPath);
        try {
            config.save(configPath);
        } catch (Exception e) {
            LOGGER.warn("Failed to save default config: {}", e.getMessage());
        }

        if (config.manifestUrl == null || config.manifestUrl.isBlank()) {
            LOGGER.warn("[MCModUpdater] client.manifestUrl is not set — skipping sync. Edit {} and restart.", configPath);
            return;
        }

        // 启动时同步
        if (config.autoSyncOnLaunch) {
            ClientTickEvents.START_CLIENT_TICK.register(client -> {
                if (syncedOnce) return;
                syncedOnce = true;
                Thread t = new Thread(() -> runSync(client, config), "mcmodupdater-sync");
                t.setDaemon(true);
                t.start();
            });
        } else {
            LOGGER.info("[MCModUpdater] Auto-sync on launch disabled by config.");
        }

        // 周期同步
        if (config.periodicSyncMinutes > 0) {
            long intervalMs = config.periodicSyncMinutes * 60_000L;
            LOGGER.info("[MCModUpdater] Periodic sync every {} minute(s).", config.periodicSyncMinutes);
            ClientTickEvents.START_CLIENT_TICK.register(client -> {
                long now = System.currentTimeMillis();
                long last = lastPeriodicSyncTime.get();
                if (now - last < intervalMs) return;
                if (!lastPeriodicSyncTime.compareAndSet(last, now)) return;
                Thread t = new Thread(() -> runSync(client, config), "mcmodupdater-periodic");
                t.setDaemon(true);
                t.start();
            });
        }
    }

    private void runSync(MinecraftClient client, ModUpdaterConfig cfg) {
        Path modsDir = cfg.resolveModsDir(FabricLoader.getInstance().getGameDir());
        ModSyncer syncer = new ModSyncer(modsDir, cfg);
        ModSyncer.SyncResult result = syncer.sync();
        reportToPlayer(client, result, cfg);
    }

    private void reportToPlayer(MinecraftClient client, ModSyncer.SyncResult result, ModUpdaterConfig cfg) {
        if (client.player == null) {
            if (result.failed) {
                LOGGER.error("[MCModUpdater] Sync failed: {}", result.errorMessage);
            } else {
                LOGGER.info("[MCModUpdater] Sync done: {} downloaded, {} skipped, {} failed, {} filtered",
                        result.downloadedCount(), result.skippedCount(), result.failedCount(), result.skippedByFilter);
            }
            return;
        }
        StringBuilder sb = new StringBuilder();
        sb.append("[MCModUpdater] ");
        if (result.failed && result.errorMessage != null) {
            sb.append("Sync failed: ").append(result.errorMessage);
        } else {
            sb.append("Sync complete: ")
              .append(result.downloadedCount()).append(" downloaded, ")
              .append(result.skippedCount()).append(" up-to-date, ")
              .append(result.failedCount()).append(" failed");
            if (result.skippedByFilter > 0) {
                sb.append(", ").append(result.skippedByFilter).append(" filtered");
            }
            if (!result.removedOrphans.isEmpty()) {
                sb.append(", ").append(result.removedOrphans.size()).append(" orphan(s) removed");
            }
            if (result.changed && cfg.promptRestart) {
                sb.append(" — restart the game to apply changes");
            }
        }
        Text message = Text.literal(sb.toString());
        client.execute(() -> {
            if (client.player != null) {
                client.player.sendMessage(message, false);
            }
        });
    }
}
