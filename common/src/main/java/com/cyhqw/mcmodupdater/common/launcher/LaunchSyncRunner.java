package com.cyhqw.mcmodupdater.common.launcher;

import com.cyhqw.mcmodupdater.common.config.ModUpdaterConfig;
import com.cyhqw.mcmodupdater.common.syncer.ModSyncer;
import com.cyhqw.mcmodupdater.common.util.ModLog;

import java.nio.file.Files;
import java.nio.file.Path;

/**
 * 启动同步流程：在游戏加载前阻塞地检查 manifest 版本、必要时弹窗 + 同步、
 * 然后才让游戏继续加载。
 *
 * <p>使用 {@link UpdateDialog} 作为 GUI，包含进度条、差异表格（默认收起）、状态文本。</p>
 *
 * <p>流程：</p>
 * <ol>
 *   <li>确定 manifest URL（用户配置或默认）</li>
 *   <li>调用 {@link ModSyncer#checkVersion()} 拉远端 manifest 并对比 versionId</li>
 *   <li>若拉取失败：
 *     <ul>
 *       <li>local 已有版本记录 → 简单对话框"网络失败，使用本地版本继续"</li>
 *       <li>local 无版本记录（首次启动）→ 简单对话框"无法获取清单"</li>
 *     </ul>
 *   </li>
 *   <li>若不需要更新（versionId 相同）→ 静默继续加载</li>
 *   <li>若需要更新 → 调用 {@link ModSyncer#analyzeDiff} 计算差异，
 *       弹 UpdateDialog 显示差异摘要，玩家点"确认更新"后开始同步，
 *       同步过程中通过 ProgressCallback 实时更新进度条和当前文件名，
 *       同步完成后显示结果，玩家点"关闭"后游戏继续加载</li>
 *   <li>把远端 versionId 回写到配置文件 currentVersionId</li>
 * </ol>
 */
public final class LaunchSyncRunner {

    private LaunchSyncRunner() {
    }

    /**
     * 在游戏加载前执行同步检查。此方法会阻塞调用线程直到玩家关闭对话框。
     *
     * @param gameDir      游戏根目录
     * @param configPath   配置文件路径
     * @param config       已加载的配置
     * @param modsLabel    弹窗标题中显示的加载器名（"Fabric" / "Forge"）
     * @return 同步结果
     */
    public static LaunchSyncResult runLaunchSync(Path gameDir, Path configPath,
                                                  ModUpdaterConfig config, String modsLabel) {
        // Minecraft Forge/Fabric 在 modloading 阶段会把 AWT 设为 headless 模式，
        // 导致 Swing 弹窗抛 HeadlessException。
        // 强制解除 headless，让 Swing 能正常工作。
        boolean swingReady = forceEnableAwt();
        if (!swingReady) {
            ModLog.warn("[LaunchSync] Swing is not available (headless). Falling back to log-only mode.");
        }

        String url = config.effectiveManifestUrl();
        ModLog.info("[LaunchSync] Checking manifest version at %s", url);

        // 新版使用 tracked_files.txt（支持相对路径）；旧版 tracked_mods.txt 兼容迁移
        Path trackedFilePath = configPath.resolveSibling("tracked_files.txt");
        Path oldTrackedPath = configPath.resolveSibling("tracked_mods.txt");
        if (!Files.exists(trackedFilePath) && Files.exists(oldTrackedPath)) {
            try {
                Files.copy(oldTrackedPath, trackedFilePath);
                ModLog.info("[LaunchSync] Migrated tracked_mods.txt -> tracked_files.txt");
            } catch (Exception e) {
                ModLog.warn("[LaunchSync] Failed to migrate tracked_mods.txt: %s", e.getMessage());
            }
        }
        ModSyncer syncer = new ModSyncer(gameDir, config, trackedFilePath);
        ModSyncer.CheckResult check;
        try {
            check = syncer.checkVersion();
        } catch (Exception e) {
            ModLog.error("[LaunchSync] Version check crashed", e);
            return handleFetchError(config, configPath, "版本检查异常: " + e.getMessage(), modsLabel);
        }

        if (check.fetchFailed()) {
            ModLog.info("[LaunchSync] Fetch failed, calling handleFetchError");
            return handleFetchError(config, configPath, check.errorMessage, modsLabel);
        }

        // 不需要更新
        if (!check.needUpdate) {
            ModLog.info("[LaunchSync] Already up-to-date (local=%s remote=%s). Calling showAutoClose.",
                    check.localVersionId, check.remoteVersionId);
            if (!check.remoteVersionId.isEmpty()
                    && !check.remoteVersionId.equals(config.currentVersionId)) {
                config.currentVersionId = check.remoteVersionId;
                saveConfigQuietly(configPath, config);
            }
            // 弹出 5 秒自动关闭的提示弹窗（原静默不弹出）
            String localLabel = check.localVersionId.isEmpty() ? "(首次安装)" : check.localVersionId;
            String upToDateMsg = String.format(
                    "整合包已是最新版本，无需更新。\n\n本地版本: %s\n远端版本: %s",
                    localLabel, check.remoteVersionId.isEmpty() ? "(未声明)" : check.remoteVersionId);
            SimpleDialog.showAutoClose("MC Mod Auto-Updater — " + modsLabel,
                    upToDateMsg, javax.swing.JOptionPane.INFORMATION_MESSAGE, 5000);
            return LaunchSyncResult.upToDate(check.remoteVersionId);
        }

        // 需要更新 → 计算差异
        ModLog.info("[LaunchSync] Need update. Analyzing diff...");
        ModSyncer.DiffResult diff;
        try {
            diff = syncer.analyzeDiff(check.manifest);
        } catch (Exception e) {
            ModLog.error("[LaunchSync] Diff analysis failed", e);
            return handleFetchError(config, configPath, "差异分析失败: " + e.getMessage(), modsLabel);
        }
        if (diff.hasError()) {
            return handleFetchError(config, configPath, diff.errorMessage, modsLabel);
        }

        // 弹窗询问 + 进度
        String modpackName = (check.manifest != null && check.manifest.name != null && !check.manifest.name.isBlank())
                ? check.manifest.name : "(未命名整合包)";

        if (!swingReady) {
            // Swing 不可用，回退到日志模式：直接同步，不弹窗
            ModLog.warn("[LaunchSync] Swing not available, auto-syncing without dialog (firstRun=%s)",
                    check.localVersionId.isEmpty());
            ModLog.info("[LaunchSync] Diff: %d to download, %d to keep, %d to remove",
                    diff.toDownload.size(), diff.toKeep.size(), diff.toRemove.size());
            for (ModSyncer.DiffEntry e : diff.toDownload) {
                ModLog.info("[LaunchSync]   %s: %s", e.action, e.filename);
            }
            ModSyncer.SyncResult result = syncer.sync(null);
            if (!result.failed && !result.remoteVersionId.isEmpty()) {
                config.currentVersionId = result.remoteVersionId;
                saveConfigQuietly(configPath, config);
            }
            ModLog.info("[LaunchSync] Sync done: %d downloaded, %d skipped, %d failed",
                    result.downloadedCount(), result.skippedCount(), result.failedCount());
            return LaunchSyncResult.synced(result);
        }

        UpdateDialog dialog = new UpdateDialog(
                modsLabel, modpackName,
                check.localVersionId.isEmpty() ? "(首次安装)" : check.localVersionId,
                check.remoteVersionId.isEmpty() ? "(未声明)" : check.remoteVersionId,
                diff);

        boolean confirmed;
        try {
            confirmed = dialog.showAndAwaitConfirm();
        } catch (Exception e) {
            ModLog.error("[LaunchSync] Dialog failed, falling back to auto-sync", e);
            // 弹窗失败，回退到自动同步（不阻塞）
            ModSyncer.SyncResult result = syncer.sync(null);
            if (!result.failed && !result.remoteVersionId.isEmpty()) {
                config.currentVersionId = result.remoteVersionId;
                saveConfigQuietly(configPath, config);
            }
            return LaunchSyncResult.synced(result);
        }

        if (!confirmed) {
            ModLog.info("[LaunchSync] User cancelled update. Continuing with local mods.");
            dialog.dispose();
            return LaunchSyncResult.userCancelled(check.remoteVersionId);
        }

        // 执行同步（带进度回调）
        ModLog.info("[LaunchSync] User confirmed. Starting sync...");
        ModSyncer.SyncResult result = syncer.sync((current, total, filename, status, detail) ->
                dialog.onProgress(current, total, filename, status, detail));

        // 回写 versionId
        if (!result.failed && !result.remoteVersionId.isEmpty()) {
            config.currentVersionId = result.remoteVersionId;
            saveConfigQuietly(configPath, config);
        }

        // 显示完成结果，等待用户关闭
        dialog.showComplete(result);
        dialog.awaitClose();
        dialog.dispose();

        return LaunchSyncResult.synced(result);
    }

    /**
     * 强制解除 AWT headless 模式。
     * Minecraft 在 modloading 阶段会设 java.awt.headless=true，
     * Java 17 模块系统会阻止普通反射 (InaccessibleObjectException)，
     * 因此使用 sun.misc.Unsafe 直接操作内存绕过模块限制。
     *
     * @return true 如果 Swing 可用（headless 已解除）
     */
    private static boolean forceEnableAwt() {
        try {
            // 1. 设置系统属性
            System.setProperty("java.awt.headless", "false");

            // 2. 尝试普通反射；如果失败（Java 17 模块限制），用 Unsafe 绕过
            boolean regularReflectionWorked = tryResetHeadlessViaReflection();
            if (!regularReflectionWorked) {
                ModLog.info("[LaunchSync] Regular reflection blocked, trying Unsafe approach");
                tryResetHeadlessViaUnsafe();
            }

            // 3. 验证
            java.awt.GraphicsEnvironment ge = java.awt.GraphicsEnvironment.getLocalGraphicsEnvironment();
            boolean headless = ge.isHeadless();
            ModLog.info("[LaunchSync] AWT headless=%s, ge=%s", headless, ge.getClass().getSimpleName());
            if (headless) {
                ModLog.warn("[LaunchSync] headless still true. Swing dialogs will fail.");
                return false;
            }
            return true;
        } catch (Throwable t) {
            ModLog.warn("[LaunchSync] forceEnableAwt failed: %s", t.getMessage());
            return false;
        }
    }

    /** 普通反射方式重置 headless 字段。返回 true 表示成功。 */
    private static boolean tryResetHeadlessViaReflection() {
        boolean ok = true;
        try {
            Class<?> tkClass = Class.forName("java.awt.Toolkit");
            java.lang.reflect.Field f = tkClass.getDeclaredField("toolkit");
            f.setAccessible(true);
            f.set(null, null);
            ModLog.info("[LaunchSync] Reflection: Reset Toolkit.toolkit=null OK");
        } catch (Exception e) {
            ModLog.info("[LaunchSync] Reflection: Toolkit reset blocked (%s)", e.getClass().getSimpleName());
            ok = false;
        }
        try {
            Class<?> geClass = Class.forName("java.awt.GraphicsEnvironment");
            java.lang.reflect.Field f = geClass.getDeclaredField("headless");
            f.setAccessible(true);
            f.set(null, Boolean.FALSE);
            ModLog.info("[LaunchSync] Reflection: Reset GE.headless=false OK");
        } catch (Exception e) {
            ModLog.info("[LaunchSync] Reflection: GE.headless blocked (%s)", e.getClass().getSimpleName());
            ok = false;
        }
        try {
            Class<?> geClass = Class.forName("java.awt.GraphicsEnvironment");
            java.lang.reflect.Field f = geClass.getDeclaredField("defaultHeadless");
            f.setAccessible(true);
            f.set(null, Boolean.FALSE);
            ModLog.info("[LaunchSync] Reflection: Reset GE.defaultHeadless=false OK");
        } catch (Exception e) {
            ModLog.info("[LaunchSync] Reflection: GE.defaultHeadless blocked (%s)", e.getClass().getSimpleName());
            ok = false;
        }
        return ok;
    }

    /**
     * 用 sun.misc.Unsafe 绕过 Java 17 模块系统，直接操作内存重置 headless 字段。
     * Unsafe 在 jdk.unsupported 模块中，不受模块限制，
     * staticFieldOffset / putBoolean / putObject 不检查访问权限。
     */
    @SuppressWarnings("removal")
    private static void tryResetHeadlessViaUnsafe() {
        try {
            // 获取 Unsafe 实例
            Class<?> unsafeClass = Class.forName("sun.misc.Unsafe");
            java.lang.reflect.Field theUnsafe = unsafeClass.getDeclaredField("theUnsafe");
            theUnsafe.setAccessible(true);
            sun.misc.Unsafe unsafe = (sun.misc.Unsafe) theUnsafe.get(null);
            ModLog.info("[LaunchSync] Unsafe: obtained instance OK");

            // 重置 GraphicsEnvironment.headless = false
            resetStaticBooleanViaUnsafe(unsafe, "java.awt.GraphicsEnvironment", "headless", false);
            // 重置 GraphicsEnvironment.defaultHeadless = false
            resetStaticBooleanViaUnsafe(unsafe, "java.awt.GraphicsEnvironment", "defaultHeadless", false);
            // 重置 GraphicsEnvironment.localEnv = null（强制重新创建非 headless 实例）
            resetStaticObjectViaUnsafe(unsafe, "java.awt.GraphicsEnvironment", "localEnv", null);
            // 重置 Toolkit.toolkit = null（强制重新创建非 headless 实例）
            resetStaticObjectViaUnsafe(unsafe, "java.awt.Toolkit", "toolkit", null);

        } catch (Exception e) {
            ModLog.warn("[LaunchSync] Unsafe approach failed: %s: %s", e.getClass().getSimpleName(), e.getMessage());
        }
    }

    private static void resetStaticBooleanViaUnsafe(sun.misc.Unsafe unsafe,
            String className, String fieldName, boolean value) {
        try {
            Class<?> clazz = Class.forName(className);
            java.lang.reflect.Field f = clazz.getDeclaredField(fieldName);
            // Unsafe 不需要 setAccessible！直接获取偏移量
            long offset = unsafe.staticFieldOffset(f);
            Object base = unsafe.staticFieldBase(f);
            unsafe.putBoolean(base, offset, value);
            ModLog.info("[LaunchSync] Unsafe: set %s.%s=%s OK", className, fieldName, value);
        } catch (NoSuchFieldException e) {
            ModLog.info("[LaunchSync] Unsafe: %s.%s field not found", className, fieldName);
        } catch (Exception e) {
            ModLog.warn("[LaunchSync] Unsafe: %s.%s failed: %s", className, fieldName, e.getMessage());
        }
    }

    private static void resetStaticObjectViaUnsafe(sun.misc.Unsafe unsafe,
            String className, String fieldName, Object value) {
        try {
            Class<?> clazz = Class.forName(className);
            java.lang.reflect.Field f = clazz.getDeclaredField(fieldName);
            long offset = unsafe.staticFieldOffset(f);
            Object base = unsafe.staticFieldBase(f);
            unsafe.putObject(base, offset, value);
            ModLog.info("[LaunchSync] Unsafe: set %s.%s=%s OK", className, fieldName, value);
        } catch (NoSuchFieldException e) {
            ModLog.info("[LaunchSync] Unsafe: %s.%s field not found", className, fieldName);
        } catch (Exception e) {
            ModLog.warn("[LaunchSync] Unsafe: %s.%s failed: %s", className, fieldName, e.getMessage());
        }
    }

    private static LaunchSyncResult handleFetchError(ModUpdaterConfig config, Path configPath,
                                                     String errorMessage, String modsLabel) {
        ModLog.warn("[LaunchSync] Failed to fetch manifest: %s", errorMessage);
        boolean firstRun = config.currentVersionId == null || config.currentVersionId.isEmpty();
        String message = (firstRun
                ? "无法获取整合包清单：\n  " + errorMessage + "\n\n"
                  + "这是首次启动，本地没有已同步的模组。\n"
                  + "游戏将继续加载。\n"
                  + "当前使用的清单 URL: " + config.effectiveManifestUrl() + "\n"
                  + "（在 config/mcmodupdater/mcmodupdater.properties 中修改 manifestUrl 可换源）"
                : "无法获取整合包清单：\n  " + errorMessage + "\n\n"
                  + "本地已有版本 " + config.currentVersionId + " 的模组，"
                  + "游戏将继续使用本地模组加载。");
        try {
            SimpleDialog.show(
                    "MC Mod Auto-Updater — " + modsLabel,
                    message,
                    javax.swing.JOptionPane.WARNING_MESSAGE);
        } catch (Throwable t) {
            ModLog.warn("[LaunchSync] SimpleDialog failed (likely headless): %s", t.getMessage());
        }
        return LaunchSyncResult.fetchFailed(errorMessage, firstRun);
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
    }

    /** 简单对话框助手（用于错误提示）。 */
    private static final class SimpleDialog {
        static void show(String title, String message, int messageType) {
            try {
                javax.swing.SwingUtilities.invokeAndWait(() ->
                        javax.swing.JOptionPane.showMessageDialog(null, message, title, messageType));
            } catch (Exception e) {
                ModLog.warn("[LaunchSync] Failed to show simple dialog: %s", e.getMessage());
                javax.swing.JOptionPane.showMessageDialog(null, message, title, messageType);
            }
        }

        /**
         * 显示一个弹窗，指定毫秒后自动关闭。
         * 不用 invokeAndWait——直接在调用线程上创建 dialog 并 setVisible，
         * 和 show() 的 fallback 路径完全一致（showMessageDialog 内部也是这么做的）。
         * setVisible(true) 会启动嵌套事件循环，Timer 事件能在其中被处理。
         */
        static void showAutoClose(String title, String message, int messageType, int delayMs) {
            ModLog.info("[LaunchSync] showAutoClose: called (headless=%s, thread=%s)",
                    java.awt.GraphicsEnvironment.isHeadless(), Thread.currentThread().getName());
            try {
                javax.swing.JOptionPane pane = new javax.swing.JOptionPane(message, messageType);
                javax.swing.JDialog dialog = pane.createDialog(title);
                dialog.setDefaultCloseOperation(javax.swing.JDialog.DISPOSE_ON_CLOSE);
                javax.swing.Timer timer = new javax.swing.Timer(delayMs, e -> {
                    ModLog.info("[LaunchSync] showAutoClose: timer fired, disposing dialog");
                    dialog.dispose();
                });
                timer.setRepeats(false);
                timer.start();
                ModLog.info("[LaunchSync] showAutoClose: setVisible(true), will auto-close in %dms", delayMs);
                dialog.setVisible(true);
                ModLog.info("[LaunchSync] showAutoClose: dialog closed");
            } catch (Exception e) {
                ModLog.warn("[LaunchSync] showAutoClose: dialog failed: %s", e.toString());
                // fallback: 和 show() 完全一样，直接调 showMessageDialog
                try {
                    javax.swing.JOptionPane.showMessageDialog(null, message, title, messageType);
                } catch (Exception e2) {
                    ModLog.warn("[LaunchSync] showAutoClose: fallback also failed: %s", e2.toString());
                }
            }
        }
    }
}
