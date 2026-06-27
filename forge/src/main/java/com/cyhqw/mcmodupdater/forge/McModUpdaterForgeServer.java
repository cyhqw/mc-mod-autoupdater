package com.cyhqw.mcmodupdater.forge;

import com.cyhqw.mcmodupdater.common.config.ModUpdaterConfig;
import com.cyhqw.mcmodupdater.common.manifest.ManifestBuilder;
import com.cyhqw.mcmodupdater.common.util.ModLog;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.logging.log4j.LogManager;
import com.mojang.logging.log4j.Logger;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraftforge.event.RegisterCommandsEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.loading.FMLPaths;

import java.nio.file.Path;

import static net.minecraft.commands.Commands.literal;

/**
 * Forge dedicated-server command registration.
 *
 * <p>Registers {@code /mcmodupdater generate} and {@code /mcmodupdater reload-config}
 * on the server.</p>
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
        final ModUpdaterConfig cfg = config;

        event.getDispatcher().register(literal("mcmodupdater")
                .requires(src -> src.hasPermission(2))
                .then(literal("generate")
                        .executes(ctx -> runGenerate(ctx, cfg)))
                .then(literal("reload-config")
                        .executes(ctx -> {
                            ModUpdaterConfig fresh = ModUpdaterConfig.load(configPath);
                            ctx.getSource().sendSuccess(() -> Component.literal("[MCModUpdater] Config reloaded."), false);
                            return 1;
                        }))
        );
    }

    private static int runGenerate(CommandContext<CommandSourceStack> ctx, ModUpdaterConfig cfg) {
        Path gameDir = FMLPaths.GAMEDIR.get();
        Path modsDir = gameDir.resolve("mods");
        Path outputDir = gameDir.resolve("config").resolve("mcmodupdater").resolve(cfg.outputSubdir);

        CommandSourceStack src = ctx.getSource();
        MinecraftServer server = src.getServer();
        src.sendSuccess(() -> Component.literal("[MCModUpdater] Scanning " + modsDir + " ..."), false);

        Thread t = new Thread(() -> {
            String loader = "forge";
            String mcVersion = server.getMinecraftVersion() != null
                    ? server.getMinecraftVersion().getName() : "1.20.1";
            ManifestBuilder builder = new ManifestBuilder(mcVersion, loader, cfg.skipModrinth, cfg.skipCurseForge);
            try {
                ManifestBuilder.BuildResult r = builder.build(modsDir, outputDir);
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
