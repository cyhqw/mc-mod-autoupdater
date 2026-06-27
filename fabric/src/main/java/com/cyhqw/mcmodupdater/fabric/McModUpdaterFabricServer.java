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
 * Fabric dedicated-server entry point.
 *
 * <p>The server-side mod does NOT auto-run on startup — it's an on-demand
 * command. Run {@code /mcmodupdater generate} from the server console or
 * op chat to scan mods and regenerate the manifest files.</p>
 *
 * <p>The output files (manifest.json + missing.json) appear in
 * {@code <server>/config/mcmodupdater/manifest-output/} by default.</p>
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
                                ctx.getSource().sendFeedback(() -> Text.literal("[MCModUpdater] Config reloaded."), false);
                                return 1;
                            }))
            );
        });

        ServerLifecycleEvents.SERVER_STARTED.register(server ->
                LOGGER.info("[MCModUpdater] Server started. Run /mcmodupdater generate to build the manifest."));
    }

    private int runGenerate(CommandContext<ServerCommandSource> ctx, ModUpdaterConfig cfg) {
        Path gameDir = FabricLoader.getInstance().getGameDir();
        Path outputDir = gameDir.resolve("config").resolve("mcmodupdater").resolve(cfg.outputSubdir);

        ctx.getSource().sendFeedback(() -> Text.literal("[MCModUpdater] Scanning " + cfg.scanDirs(gameDir) + " ..."), false);

        // Run on a background thread so we don't freeze the main server thread.
        Thread t = new Thread(() -> {
            // Detect mod loader: prefer Fabric (we're running on Fabric here), but
            // include the configured loader hint in the manifest meta.
            String loader = "fabric";
            String mcVersion = "1.20.1";
            ManifestBuilder builder = new ManifestBuilder(mcVersion, loader, cfg.skipModrinth, cfg.skipCurseForge);
            try {
                ManifestBuilder.BuildResult r = builder.build(cfg.scanDirs(gameDir), outputDir, cfg.scanDisabled);
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
