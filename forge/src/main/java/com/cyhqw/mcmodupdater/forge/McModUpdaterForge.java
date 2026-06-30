package com.cyhqw.mcmodupdater.forge;

import com.cyhqw.mcmodupdater.common.config.ModUpdaterConfig;
import com.cyhqw.mcmodupdater.common.launcher.LaunchSyncRunner;
import com.cyhqw.mcmodupdater.common.util.ModLog;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.event.lifecycle.FMLCommonSetupEvent;
import net.minecraftforge.fml.loading.FMLPaths;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.nio.file.Path;
import java.util.concurrent.CountDownLatch;

/**
 * Forge 模组入口。
 *
 * <p>本模组纯客户端运行。在 {@link FMLCommonSetupEvent}（Forge 早期加载事件）
 * 中阻塞地执行 {@link LaunchSyncRunner#runLaunchSync}。</p>
 *
 * <p>关键：同步逻辑在独立线程上跑（避免 Swing modal dialog 与 MC Render thread 死锁），
 * 主线程用 {@link CountDownLatch} 等待同步完成，实现真正的"拦截启动"。</p>
 *
 * <p>{@code FMLCommonSetupEvent} 在模组加载早期、世界尚未加载时触发。
 * 阻塞此事件会让加载屏幕暂停，弹出 Swing 对话框，玩家确认后才继续。</p>
 */
@Mod(McModUpdaterForge.MOD_ID)
public final class McModUpdaterForge {

    public static final String MOD_ID = "mcmodupdater";
    public static final String MOD_NAME = "MC Mod Auto-Updater";
    public static final String VERSION = "0.4.0";

    private static final Logger LOGGER = LogManager.getLogger("MCModUpdater");

    public McModUpdaterForge() {
        ModLog.setSink(new Log4jSink(LOGGER));
        LOGGER.info("[MCModUpdater] Forge client mod loading. (client-only, pre-launch sync)");
        net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext.get().getModEventBus()
                .addListener(McModUpdaterForge::onCommonSetup);
    }

    @SubscribeEvent
    public static void onCommonSetup(FMLCommonSetupEvent event) {
        Path gameDir = FMLPaths.GAMEDIR.get();
        Path configPath = FMLPaths.CONFIGDIR.get().resolve("mcmodupdater/mcmodupdater.properties");
        ModUpdaterConfig config = ModUpdaterConfig.load(configPath);
        try {
            config.save(configPath);
        } catch (Exception e) {
            LOGGER.warn("Failed to save default config: {}", e.getMessage());
        }

        if (!config.autoSyncOnLaunch) {
            LOGGER.info("[MCModUpdater] autoSyncOnLaunch=false, skipping launch sync.");
            return;
        }

        // 不用 enqueueWork，直接在 modloading-worker 线程上阻塞
        // 这样可以避免 DeferredWorkQueue 的超时机制
        // 同步逻辑在独立线程跑（Swing modal dialog 需要独立线程避免死锁），
        // 当前线程用 CountDownLatch 阻塞等待
        LOGGER.info("[MCModUpdater] Starting launch sync (blocking modloading until done)...");
        final CountDownLatch latch = new CountDownLatch(1);
        final ModUpdaterConfig cfg = config;
        Thread syncThread = new Thread(() -> {
            try {
                LaunchSyncRunner.runLaunchSync(gameDir, configPath, cfg, "Forge");
            } catch (Throwable t) {
                LOGGER.error("[MCModUpdater] Launch sync crashed", t);
            } finally {
                latch.countDown();
            }
        }, "mcmodupdater-launch-sync");
        syncThread.setDaemon(true);
        syncThread.start();

        // 阻塞当前 modloading-worker 线程，等待同步完成
        try {
            latch.await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            LOGGER.warn("[MCModUpdater] Launch sync wait interrupted");
        }
        LOGGER.info("[MCModUpdater] Launch sync finished, continuing mod loading.");
    }
}
