package com.cyhqw.mcmodupdater.common.launcher;

import com.cyhqw.mcmodupdater.common.config.ModUpdaterConfig;
import com.cyhqw.mcmodupdater.common.syncer.ModSyncer;
import com.cyhqw.mcmodupdater.common.util.ModLog;

import javax.swing.JOptionPane;
import javax.swing.SwingUtilities;
import java.nio.file.Path;

/**
 * 启动同步流程：在游戏加载前阻塞地检查 manifest 版本、必要时弹窗 + 同步、
 * 然后才让游戏继续加载。
 *
 * <p>Fabric 和 Forge 入口都通过调用 {@link #runLaunchSync} 触发本流程。
 * 本类在 common 模块中，使用 Swing（JDK 自带）弹窗，不依赖任何 MC 类。</p>
 *
 * <p>流程：</p>
 * <ol>
 *   <li>确定 manifest URL：用户在配置文件填了 {@code manifestUrl} 就用用户的，
 *       否则使用 {@link ModUpdaterConfig#DEFAULT_MANIFEST_URL}（指向本仓库根目录下的 modrinth.index.json）</li>
 *   <li>调用 {@link ModSyncer#checkVersion()} 拉远端 manifest 并对比 versionId</li>
 *   <li>若拉取失败：
 *     <ul>
 *       <li>若 local 已有版本记录 → 弹"网络失败，使用本地版本继续"提示，继续加载</li>
 *       <li>若 local 无版本记录（首次启动）→ 弹"无法获取清单"错误，仍继续加载</li>
 *     </ul>
 *   </li>
 *   <li>若不需要更新（versionId 相同）→ 静默继续加载</li>
 *   <li>若需要更新 → 弹窗告知"发现新版本 vX → vY，开始下载"，玩家点 OK 后同步，
 *       同步完成后弹"同步完成，需要重启游戏才能生效"，玩家点 OK 后继续</li>
 *   <li>把远端 versionId 回写到配置文件 currentVersionId</li>
 * </ol>
 */
public final class LaunchSyncRunner {

    private LaunchSyncRunner() {}

    /**
     * 在游戏加载前执行同步检查。此方法会阻塞调用线程直到玩家关闭弹窗。
     *
     * @param gameDir      游戏根目录（用于定位 mods/ 和 config/）
     * @param configPath   配置文件路径
     * @param config       已加载的配置（会被修改：currentVersionId 字段）
     * @param modsLabel    弹窗标题中显示的加载器名（如 "Fabric" / "Forge"）
     * @return 同步结果，供调用方决定是否提示玩家重启
     */
    public static LaunchSyncResult runLaunchSync(Path gameDir, Path configPath,
                                                  ModUpdaterConfig config, String modsLabel) {
        // manifestUrl 留空时会自动使用 DEFAULT_MANIFEST_URL（指向本仓库根目录下的 modrinth.index.json）
        String url = config.effectiveManifestUrl();
        ModLog.info("[LaunchSync] Checking manifest version at %s", url);

        // 1. 检查版本
        ModSyncer syncer = new ModSyncer(config.resolveModsDir(gameDir), config);
        ModSyncer.CheckResult check;
        try {
            check = syncer.checkVersion();
        } catch (Exception e) {
            ModLog.error("[LaunchSync] Version check crashed", e);
            return handleFetchError(config, configPath, "版本检查异常: " + e.getMessage(), modsLabel);
        }

        if (check.fetchFailed()) {
            return handleFetchError(config, configPath, check.errorMessage, modsLabel);
        }

        // 2. 不需要更新
        if (!check.needUpdate) {
            ModLog.info("[LaunchSync] Already up-to-date (local=%s remote=%s).",
                    check.localVersionId, check.remoteVersionId);
            // 仍然静默回写一次（保证 currentVersionId 不为空）
            if (!check.remoteVersionId.isEmpty()
                    && !check.remoteVersionId.equals(config.currentVersionId)) {
                config.currentVersionId = check.remoteVersionId;
                saveConfigQuietly(configPath, config);
            }
            return LaunchSyncResult.upToDate(check.remoteVersionId);
        }

        // 3. 需要更新
        // 首次运行（localVersionId 为空）：静默直接同步，不弹"发现新版本"对话框，
        // 同步完成后再弹一次结果即可。
        // 后续运行：先弹"发现新版本 vX → vY"对话框，玩家确认后再同步。
        boolean firstRun = check.localVersionId.isEmpty();
        if (!firstRun) {
            String intro = String.format(
                    "检测到整合包有新版本。\n\n" +
                    "  整合包名称: %s\n" +
                    "  本地版本:   %s\n" +
                    "  远端版本:   %s\n\n" +
                    "点击 \"确定\" 开始下载更新，完成后需重启游戏。\n" +
                    "点击 \"取消\" 跳过本次更新（使用本地已有模组启动）。",
                    displayName(check.manifest),
                    check.localVersionId,
                    check.remoteVersionId.isEmpty() ? "(未声明)" : check.remoteVersionId);

            int choice = showDialog(
                    "MC Mod Auto-Updater — " + modsLabel,
                    intro,
                    JOptionPane.INFORMATION_MESSAGE,
                    JOptionPane.OK_CANCEL_OPTION);

            if (choice != JOptionPane.OK_OPTION) {
                ModLog.info("[LaunchSync] User cancelled update. Continuing with local mods.");
                return LaunchSyncResult.userCancelled(check.remoteVersionId);
            }
        } else {
            ModLog.info("[LaunchSync] First run (no local version). Auto-syncing silently...");
        }

        // 4. 执行同步
        ModLog.info("[LaunchSync] Starting sync...");
        ModSyncer.SyncResult result = syncer.sync();

        // 5. 回写 versionId
        if (!result.failed && !result.remoteVersionId.isEmpty()) {
            config.currentVersionId = result.remoteVersionId;
            saveConfigQuietly(configPath, config);
        }

        // 6. 弹窗告知结果
        showSyncResultDialog(modsLabel, result);

        return LaunchSyncResult.synced(result);
    }

    private static LaunchSyncResult handleFetchError(ModUpdaterConfig config, Path configPath,
                                                     String errorMessage, String modsLabel) {
        ModLog.warn("[LaunchSync] Failed to fetch manifest: %s", errorMessage);
        boolean firstRun = config.currentVersionId == null || config.currentVersionId.isEmpty();
        String title = "MC Mod Auto-Updater — " + modsLabel;
        String message;
        int messageType;
        if (firstRun) {
            messageType = JOptionPane.WARNING_MESSAGE;
            message = "无法获取整合包清单：\n  " + errorMessage + "\n\n" +
                    "这是首次启动，本地没有已同步的模组。\n" +
                    "游戏将继续加载。\n" +
                    "当前使用的清单 URL: " + config.effectiveManifestUrl() + "\n" +
                    "（在 config/mcmodupdater/mcmodupdater.properties 中修改 manifestUrl 可换源）";
        } else {
            messageType = JOptionPane.WARNING_MESSAGE;
            message = "无法获取整合包清单：\n  " + errorMessage + "\n\n" +
                    "本地已有版本 " + config.currentVersionId + " 的模组，" +
                    "游戏将继续使用本地模组加载。";
        }
        showDialog(title, message, messageType, JOptionPane.DEFAULT_OPTION);
        return LaunchSyncResult.fetchFailed(errorMessage, firstRun);
    }

    private static void showSyncResultDialog(String modsLabel, ModSyncer.SyncResult result) {
        String title = "MC Mod Auto-Updater — " + modsLabel;
        if (result.failed) {
            showDialog(title,
                    "同步失败：\n  " + result.errorMessage + "\n\n游戏将继续使用本地模组加载。",
                    JOptionPane.ERROR_MESSAGE, JOptionPane.DEFAULT_OPTION);
            return;
        }
        StringBuilder sb = new StringBuilder();
        sb.append("同步完成。\n\n");
        sb.append("  下载: ").append(result.downloadedCount()).append(" 个\n");
        sb.append("  跳过: ").append(result.skippedCount()).append(" 个（已是最新）\n");
        sb.append("  失败: ").append(result.failedCount()).append(" 个\n");
        if (result.skippedByFilter > 0) {
            sb.append("  被过滤: ").append(result.skippedByFilter).append(" 个\n");
        }
        if (!result.removedOrphans.isEmpty()) {
            sb.append("  清理孤儿: ").append(result.removedOrphans.size()).append(" 个\n");
        }
        sb.append("\n请重启游戏以应用更新。");
        showDialog(title, sb.toString(), JOptionPane.INFORMATION_MESSAGE, JOptionPane.DEFAULT_OPTION);
    }

    private static String displayName(com.cyhqw.mcmodupdater.common.modrinth.ModrinthIndex index) {
        if (index == null) return "(未知)";
        if (index.name != null && !index.name.isBlank()) return index.name;
        if (index.versionId != null && !index.versionId.isBlank()) return "v" + index.versionId;
        return "(未命名)";
    }

    /** 在事件分发线程阻塞地弹窗，返回用户选择。 */
    private static int showDialog(String title, String message, int messageType, int optionType) {
        final int[] result = new int[1];
        try {
            SwingUtilities.invokeAndWait(() -> {
                result[0] = JOptionPane.showConfirmDialog(
                        null,
                        message,
                        title,
                        optionType,
                        messageType);
            });
        } catch (Exception e) {
            ModLog.warn("[LaunchSync] Failed to show dialog on EDT, falling back to direct call: %s", e.getMessage());
            // Swing 不一定在所有 headless 环境可用，直接调用作为兜底
            result[0] = JOptionPane.showConfirmDialog(null, message, title, optionType, messageType);
        }
        return result[0];
    }

    private static void saveConfigQuietly(Path configPath, ModUpdaterConfig config) {
        try {
            config.save(configPath);
        } catch (Exception e) {
            ModLog.warn("[LaunchSync] Failed to save config: %s", e.getMessage());
        }
    }

    // ------------------------------------------------------------------

    public static final class LaunchSyncResult {
        public enum Outcome { UP_TO_DATE, SYNCED, USER_CANCELLED, FETCH_FAILED, SKIPPED }

        public final Outcome outcome;
        public final String remoteVersionId;
        public final ModSyncer.SyncResult syncResult;
        public final String errorMessage;
        public final boolean firstRun;

        private LaunchSyncResult(Outcome outcome, String remoteVersionId,
                                 ModSyncer.SyncResult syncResult, String errorMessage, boolean firstRun) {
            this.outcome = outcome;
            this.remoteVersionId = remoteVersionId;
            this.syncResult = syncResult;
            this.errorMessage = errorMessage;
            this.firstRun = firstRun;
        }

        public static LaunchSyncResult upToDate(String remoteVersionId) {
            return new LaunchSyncResult(Outcome.UP_TO_DATE, remoteVersionId, null, null, false);
        }

        public static LaunchSyncResult synced(ModSyncer.SyncResult result) {
            return new LaunchSyncResult(Outcome.SYNCED, result.remoteVersionId, result, null, false);
        }

        public static LaunchSyncResult userCancelled(String remoteVersionId) {
            return new LaunchSyncResult(Outcome.USER_CANCELLED, remoteVersionId, null, null, false);
        }

        public static LaunchSyncResult fetchFailed(String errorMessage, boolean firstRun) {
            return new LaunchSyncResult(Outcome.FETCH_FAILED, "", null, errorMessage, firstRun);
        }

        public static LaunchSyncResult skipped(String reason) {
            return new LaunchSyncResult(Outcome.SKIPPED, "", null, reason, false);
        }
    }
}
