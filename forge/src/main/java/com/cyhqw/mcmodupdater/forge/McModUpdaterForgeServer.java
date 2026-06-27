package com.cyhqw.mcmodupdater.forge;

import com.cyhqw.mcmodupdater.common.config.ModUpdaterConfig;
import com.cyhqw.mcmodupdater.common.manifest.ManifestBuilder;
import com.cyhqw.mcmodupdater.common.util.ModLog;
import com.mojang.brigadier.context.CommandContext;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraftforge.event.RegisterCommandsEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.loading.FMLPaths;

import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicReference;

import static net.minecraft.commands.Commands.literal;

/**
 * Forge 服务端命令注册。
 *
 * <p>注册 {@code /mcmodupdater generate}、{@code /mcmodupdater reload-config}、
 * {@code /mcmodupdater show-config} 命令。</p>
 *
 * <p>所有可配置项见 {@link ModUpdaterConfig}。</p>
 */
@Mod.EventBusSubscriber(modid = McModUpdaterForge.MOD_ID, bus = Mod.EventBusSubscriber.Bus.FORGE)
public final class McModUpdaterForgeServer {

    private static final Logger LOGGER = LogManager.getLogger("MCModUpdater");

    @SubscribeEvent
    public static void onRegisterCommands(RegisterCommandsEvent event) {
        ModLog.setSink(new Log4jSink(LOGGER));

        Path configPath = FMLPaths.CONFIGDIR.get().resolve("mcmodupdater/mcmodupdater.properties");
        ModUpdaterConfig config = ModUpdaterConfig.load(configPath);
        try {
            config.save(configPath);
        } catch (Exception e) {
            LOGGER.warn("Failed to save default config: {}", e.getMessage());
        }
        final AtomicReference<ModUpdaterConfig> cfgRef = new AtomicReference<>(config);

        event.getDispatcher().register(literal("mcmodupdater")
                .requires(src -> src.hasPermission(2))
                .then(literal("generate")
                        .executes(ctx -> runGenerate(ctx, cfgRef.get())))
                .then(literal("reload-config")
                        .executes(ctx -> {
                            ModUpdaterConfig fresh = ModUpdaterConfig.load(configPath);
                            cfgRef.set(fresh);
                            ctx.getSource().sendSuccess(() -> Component.literal(
                                    "[MCModUpdater] Config reloaded. mc=" + fresh.minecraftVersion
                                            + " loader=" + fresh.modLoader), false);
                            return 1;
                        }))
                .then(literal("show-config")
                        .executes(ctx -> {
                            ModUpdaterConfig cfg = cfgRef.get();
                            Path gameDir = FMLPaths.GAMEDIR.get();
                            ctx.getSource().sendSuccess(() -> Component.literal(
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
    }

    private static int runGenerate(CommandContext<CommandSourceStack> ctx, ModUpdaterConfig cfg) {
        Path gameDir = FMLPaths.GAMEDIR.get();
        Path outputDir = cfg.resolveOutputDir(gameDir);

        CommandSourceStack src = ctx.getSource();
        MinecraftServer server = src.getServer();
        src.sendSuccess(() -> Component.literal(
                "[MCModUpdater] Scanning " + cfg.scanDirs(gameDir) + " (mc=" + cfg.minecraftVersion
                        + ", loader=" + cfg.modLoader + ") ..."), false);

        Thread t = new Thread(() -> {
            String loader = (cfg.modLoader != null && !cfg.modLoader.isBlank()) ? cfg.modLoader : "forge";
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
                server.execute(() -> {
                    src.sendSuccess(() -> Component.literal(
                            "[MCModUpdater] Manifest written: " + r.manifest.mods.size() + " mods, "
                                    + r.missingReport.missing.size() + " missing → " + r.outputDir), false);
                });
            } catch (Exception e) {
                LOGGER.error("[MCModUpdater] Manifest build failed", e);
                server.execute(() ->
                        src.sendFailure(Component.literal("[MCModUpdater] ERROR: " + e.getMessage())));
            }
        }, "mcmodupdater-generate");
        t.setDaemon(true);
        t.start();
        return 1;
    }
}
