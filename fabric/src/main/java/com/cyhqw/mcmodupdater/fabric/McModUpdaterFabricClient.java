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

/**
 * Fabric 客户端入口。
 *
 * <p>按照需求（"启动时触发更新检查"），客户端首次 tick 时在后台线程执行一次模组同步。
 * 同步结果通过游戏内聊天 HUD 反馈给玩家。</p>
 */
public class McModUpdaterFabricClient implements ClientModInitializer {

    public static final Logger LOGGER = LoggerFactory.getLogger("MCModUpdater");

    private static boolean syncedOnce = false;
    private static ModUpdaterConfig config;

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

        if (!config.autoSyncOnLaunch) {
            LOGGER.info("[MCModUpdater] Auto-sync on launch disabled by config.");
            return;
        }
        if (config.manifestUrl == null || config.manifestUrl.isBlank()) {
            LOGGER.warn("[MCModUpdater] client.manifestUrl is not set — skipping auto-sync. Edit {} and restart.", configPath);
            return;
        }

        ClientTickEvents.START_CLIENT_TICK.register(client -> {
            if (syncedOnce) return;
            syncedOnce = true;
            // Defer to a background thread so we don't stall the render loop.
            Thread t = new Thread(() -> runSync(client, config), "mcmodupdater-sync");
            t.setDaemon(true);
            t.start();
        });
    }

    private void runSync(MinecraftClient client, ModUpdaterConfig cfg) {
        Path modsDir = FabricLoader.getInstance().getGameDir().resolve("mods");
        ModSyncer syncer = new ModSyncer(modsDir, cfg);
        ModSyncer.SyncResult result = syncer.sync();
        reportToPlayer(client, result, cfg);
    }

    private void reportToPlayer(MinecraftClient client, ModSyncer.SyncResult result, ModUpdaterConfig cfg) {
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
