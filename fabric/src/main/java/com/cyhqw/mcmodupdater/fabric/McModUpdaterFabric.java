package com.cyhqw.mcmodupdater.fabric;

import com.cyhqw.mcmodupdater.common.config.ModUpdaterConfig;
import com.cyhqw.mcmodupdater.common.launcher.LaunchSyncRunner;
import com.cyhqw.mcmodupdater.common.util.ModLog;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.loader.api.FabricLoader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;

/**
 * Fabric 模组入口。
 *
 * <p>本模组纯客户端运行。为了在"游戏加载之前"完成更新检查，入口注册为
 * {@link ModInitializer}（环境=client 时由 Fabric 加载），在
 * {@link #onInitialize()} 中阻塞地执行 {@link LaunchSyncRunner#runLaunchSync}。</p>
 *
 * <p>{@code onInitialize} 在 Fabric 的引导阶段同步执行，此时游戏主循环尚未开始，
 * mods/ 目录下的模组也尚未被加载。所以这里阻塞不会冻结已渲染的画面，只会在
 * 启动屏幕（黑屏 + 加载日志）期间弹出一个 Swing 对话框。</p>
 *
 * <p>注意：Swing 对话框会阻塞当前线程，但 Fabric 的 mod 初始化是在主线程上做的，
 * 阻塞会让启动屏幕暂停，直到玩家关闭对话框为止。这正是我们想要的"弹窗确认后再
 * 继续加载"的行为。</p>
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

        // 启动时同步检查（阻塞，弹 Swing 对话框）
        if (config.autoSyncOnLaunch) {
            try {
                LaunchSyncRunner.runLaunchSync(gameDir, configPath, config, "Fabric");
            } catch (Throwable t) {
                LOGGER.error("[MCModUpdater] Launch sync crashed", t);
            }
        } else {
            LOGGER.info("[MCModUpdater] autoSyncOnLaunch=false, skipping launch sync.");
        }

        // 若同步发生且需要重启，在服务端启动时再次提示（兜底）
        ServerLifecycleEvents.SERVER_STARTED.register(server ->
                LOGGER.info("[MCModUpdater] Game started. Mod auto-updater is active."));
    }
}
