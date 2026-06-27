package com.cyhqw.mcmodupdater.fabric;

import com.cyhqw.mcmodupdater.common.config.ModUpdaterConfig;
import com.cyhqw.mcmodupdater.common.manifest.ManifestBuilder;
import com.cyhqw.mcmodupdater.common.util.ModLog;
import com.mojang.brigadier.context.CommandContext;
import net.fabricmc.api.DedicatedServerModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.text.Text;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicReference;

import static net.minecraft.server.command.CommandManager.literal;

/**
 * Fabric 专用服入口。
 *
 * <p>服务端不自动运行 —— 通过 {@code /mcmodupdater generate} 手动触发。
 * 输出目录由 {@code server.outputDir}（绝对路径）或 {@code server.outputSubdir}
 * （位于 config/mcmodupdater/ 下）控制。</p>
 *
 * <p>所有可配置项见 {@link ModUpdaterConfig}。</p>
 */
public class McModUpdaterFabricServer implements DedicatedServerModInitializer {

    public static final Logger LOGGER = LoggerFactory.getLogger("MCModUpdater");

    @Override
    public void onInitializeServer() {
        ModLog.setSink(new Slf4jSink(LOGGER));

        Path gameDir = FabricLoader.getInstance().getGameDir();
        Path configPath = gameDir.resolve("config").resolve("mcmodupdater").resolve("mcmodupdater.properties");
        ModUpdaterConfig config = ModUpdaterConfig.load(configPath);
        try {
            config.save(configPath);
        } catch (Exception e) {
            LOGGER.warn("Failed to save default config: {}", e.getMessage());
        }

        final AtomicReference<ModUpdaterConfig> cfgRef = new AtomicReference<>(config);
        CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) -> {
            dispatcher.register(literal("mcmodupdater")
                    .requires(src -> src.hasPermissionLevel(2))
                    .then(literal("generate")
                            .executes(ctx -> runGenerate(ctx, cfgRef.get())))
                    .then(literal("reload-config")
                            .executes(ctx -> {
                                ModUpdaterConfig fresh = ModUpdaterConfig.load(configPath);
                                cfgRef.set(fresh);
                                ctx.getSource().sendFeedback(() -> Text.literal(
                                        "[MCModUpdater] Config reloaded. mc=" + fresh.minecraftVersion
                                                + " loader=" + fresh.modLoader), false);
                                return 1;
                            }))
                    .then(literal("show-config")
                            .executes(ctx -> {
                                ModUpdaterConfig cfg = cfgRef.get();
                                ctx.getSource().sendFeedback(() -> Text.literal(
                                        "[MCModUpdater] manifestUrl=" + cfg.manifestUrl
                                                + " | mc=" + cfg.minecraftVersion
                                                + " | loader=" + cfg.modLoader
                                                + " | outputDir=" + cfg.resolveOutputDir(gameDir)
                                                + " | skipMods=" + cfg.skipModrinth
                                                + " | skipCF=" + cfg.skipCurseForge
                                                + " | cfKey=" + (cfg.curseForgeApiKey != null && !cfg.curseForgeApiKey.isBlank() ? "(set)" : "(unset)")), false);
                                return 1;
                            }))
            );
        });

        ServerLifecycleEvents.SERVER_STARTED.register(server ->
                LOGGER.info("[MCModUpdater] Server started. Run /mcmodupdater generate to build the manifest."));
    }

    private int runGenerate(CommandContext<ServerCommandSource> ctx, ModUpdaterConfig cfg) {
        Path gameDir = FabricLoader.getInstance().getGameDir();
        Path outputDir = cfg.resolveOutputDir(gameDir);

        ctx.getSource().sendFeedback(() -> Text.literal(
                "[MCModUpdater] Scanning " + cfg.scanDirs(gameDir) + " (mc=" + cfg.minecraftVersion
                        + ", loader=" + cfg.modLoader + ") ..."), false);

        Thread t = new Thread(() -> {
            // 我们在 Fabric 上运行，所以 loader 至少是 fabric；但允许配置覆盖（如 quilt）
            String loader = (cfg.modLoader != null && !cfg.modLoader.isBlank()) ? cfg.modLoader : "fabric";
            String mcVersion = (cfg.minecraftVersion != null && !cfg.minecraftVersion.isBlank())
                    ? cfg.minecraftVersion : "1.20.1";
            ManifestBuilder builder = new ManifestBuilder(
                    mcVersion, loader,
                    cfg.skipModrinth, cfg.skipCurseForge,
                    cfg.curseForgeApiKey,
                    cfg.serverHttpTimeoutMs, cfg.serverMaxRetries);
            try {
                ManifestBuilder.BuildResult r = builder.build(
                        cfg.scanDirs(gameDir), outputDir, cfg.scanDisabled, cfg.scanRecursively);
                ctx.getSource().getServer().execute(() -> {
                    ctx.getSource().sendFeedback(() -> Text.literal(
                            "[MCModUpdater] Manifest written: " + r.manifest.mods.size() + " mods, "
                                    + r.missingReport.missing.size() + " missing → " + r.outputDir), false);
                });
            } catch (Exception e) {
                LOGGER.error("[MCModUpdater] Manifest build failed", e);
                ctx.getSource().getServer().execute(() ->
                        ctx.getSource().sendFeedback(() -> Text.literal("[MCModUpdater] ERROR: " + e.getMessage()), false));
            }
        }, "mcmodupdater-generate");
        t.setDaemon(true);
        t.start();
        return 1;
    }
}
