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

/**
 * Forge 模组入口。
 *
 * <p>本模组纯客户端运行。在 {@link FMLCommonSetupEvent}（Forge 早期加载事件）
 * 中阻塞地执行 {@link LaunchSyncRunner#runLaunchSync}。</p>
 *
 * <p>{@code FMLCommonSetupEvent} 在模组加载早期、世界尚未加载时触发，
 * 此时 mods/ 目录下的模组正在被加载但尚未初始化。阻塞此事件会让加载屏幕暂停，
 * 弹出 Swing 对话框，玩家确认后才继续。</p>
 *
 * <p>通过 {@code @Mod.EventBusSubscriber(value = Dist.CLIENT)} 限制为仅客户端触发。</p>
 */
@Mod(McModUpdaterForge.MOD_ID)
public final class McModUpdaterForge {

    public static final String MOD_ID = "mcmodupdater";
    public static final String MOD_NAME = "MC Mod Auto-Updater";
    public static final String VERSION = "0.3.0";

    private static final Logger LOGGER = LogManager.getLogger("MCModUpdater");

    public McModUpdaterForge() {
        ModLog.setSink(new Log4jSink(LOGGER));
        LOGGER.info("[MCModUpdater] Forge client mod loading. (client-only, pre-launch sync)");
        // 注册到 MOD bus 监听 FMLCommonSetupEvent
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

        // 阻塞地执行同步，弹窗告知结果后再让游戏继续加载
        event.enqueueWork(() -> {
            try {
                LaunchSyncRunner.runLaunchSync(gameDir, configPath, config, "Forge");
            } catch (Throwable t) {
                LOGGER.error("[MCModUpdater] Launch sync crashed", t);
            }
        });
    }
}
