package com.cyhqw.mcmodupdater.fabric;

import com.cyhqw.mcmodupdater.common.config.ModUpdaterConfig;
import com.cyhqw.mcmodupdater.common.syncer.ModSyncer;
import com.cyhqw.mcmodupdater.common.util.ModLog;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.MinecraftClient;
import net.minecraft.text.Text;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;

/**
 * Fabric client entry point.
 *
 * <p>Per the user's spec ("启动时触发更新检查"), this runs the mod sync once
 * when the client is starting up — specifically, on the first client tick.
 * Any updates are reported to the player via the in-game chat HUD.</p>
 */
public class McModUpdaterFabricClient implements ClientModInitializer {

    public static final Logger LOGGER = LoggerFactory.getLogger("MCModUpdater");

    private static boolean syncedOnce = false;

    @Override
    public void onInitializeClient() {
        ModLog.setSink(new Slf4jSink(LOGGER));

        Path gameDir = FabricLoader.getInstance().getGameDir();
        Path configPath = gameDir.resolve("config").resolve("mcmodupdater").resolve("mcmodupdater.properties");
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

        ClientLifecycleEvents.CLIENT_TICK_START.register(client -> {
            if (syncedOnce) return;
            syncedOnce = true;
            // Defer to a background thread so we don't stall the render loop.
            Thread t = new Thread(() -> runSync(client, config), "mcmodupdater-sync");
            t.setDaemon(true);
            t.start();
        });
    }

    private void runSync(MinecraftClient client, ModUpdaterConfig config) {
        Path modsDir = FabricLoader.getInstance().getGameDir().resolve("mods");
        ModSyncer syncer = new ModSyncer(modsDir, config);
        ModSyncer.SyncResult result = syncer.sync();
        reportToPlayer(client, result);
    }

    private void reportToPlayer(MinecraftClient client, ModSyncer.SyncResult result) {
        if (client.player == null) {
            // Player may not be in-game yet (we ran on first tick of main menu).
            // Log instead.
            if (result.failed) {
                LOGGER.error("[MCModUpdater] Sync failed: {}", result.errorMessage);
            } else {
                LOGGER.info("[MCModUpdater] Sync done: {} downloaded, {} skipped, {} failed",
                        result.downloadedCount(), result.skippedCount(), result.failedCount());
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
            if (!result.removedOrphans.isEmpty()) {
                sb.append(", ").append(result.removedOrphans.size()).append(" orphan(s) removed");
            }
            if (result.changed && config.promptRestart) {
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
