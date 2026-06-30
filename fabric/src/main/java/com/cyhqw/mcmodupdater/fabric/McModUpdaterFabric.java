package com.cyhqw.mcmodupdater.fabric;

import com.cyhqw.mcmodupdater.common.config.ModUpdaterConfig;
import com.cyhqw.mcmodupdater.common.launcher.LaunchSyncRunner;
import com.cyhqw.mcmodupdater.common.util.ModLog;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.loader.api.FabricLoader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.util.concurrent.CountDownLatch;

/**
 * Fabric 模组入口。
 *
 * <p>本模组纯客户端运行。为了在"游戏加载之前"完成更新检查，入口注册为
 * {@link ModInitializer}，在 {@link #onInitialize()} 中阻塞地执行
 * {@link LaunchSyncRunner#runLaunchSync}。</p>
 *
 * <p>关键：同步逻辑在独立线程上跑（避免 Swing modal dialog 与 MC 主线程死锁），
 * 主线程用 {@link CountDownLatch} 等待同步完成，实现真正的"拦截启动"。</p>
 *
 * <p>{@code onInitialize} 在 Fabric 的引导阶段同步执行，此时游戏主循环尚未开始，
 * mods/ 目录下的模组也尚未被加载。阻塞会让启动屏幕暂停，直到玩家关闭对话框为止。</p>
 */
public class McModUpdaterFabric implements ModInitializer {

    public static final Logger LOGGER = LoggerFactory.getLogger("MCModUpdater");

    @Override
    public void onInitialize() {
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
            LOGGER.info("[MCModUpdater] autoSyncOnLaunch=false, skipping launch sync.");
            return;
        }

        // 在独立线程上跑同步，主线程用 CountDownLatch 阻塞等待
        // 这样 Swing modal dialog 不会与 MC 主线程死锁
        final CountDownLatch latch = new CountDownLatch(1);
        final ModUpdaterConfig cfg = config;
        Thread syncThread = new Thread(() -> {
            try {
                LaunchSyncRunner.runLaunchSync(gameDir, configPath, cfg, "Fabric");
            } catch (Throwable t) {
                LOGGER.error("[MCModUpdater] Launch sync crashed", t);
            } finally {
                latch.countDown();
            }
        }, "mcmodupdater-launch-sync");
        syncThread.setDaemon(true);
        syncThread.start();

        // 阻塞主线程，等待同步完成
        try {
            latch.await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            LOGGER.warn("[MCModUpdater] Launch sync wait interrupted");
        }
    }
}
