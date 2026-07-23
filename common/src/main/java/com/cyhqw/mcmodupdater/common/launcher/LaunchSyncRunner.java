package com.cyhqw.mcmodupdater.common.launcher;

import com.cyhqw.mcmodupdater.common.config.ModUpdaterConfig;
import com.cyhqw.mcmodupdater.common.syncer.ModSyncer;
import com.cyhqw.mcmodupdater.common.util.ModLog;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

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
            StringBuilder msgBuilder = new StringBuilder();
            msgBuilder.append(String.format("整合包已是最新版本，无需更新。\n\n本地版本: %s\n远端版本: %s",
                    localLabel, check.remoteVersionId.isEmpty() ? "(未声明)" : check.remoteVersionId));
            // 如果做了哈希校验，在弹窗中显示校验结果
            if (check.hashMethod != null && !check.hashMethod.isEmpty()) {
                msgBuilder.append(String.format("\n\n文件校验 (%s): %s", check.hashMethod, check.hashSummary));
            }
            SimpleDialog.showAutoClose("MC Mod Auto-Updater — " + modsLabel,
                    msgBuilder.toString(), javax.swing.JOptionPane.INFORMATION_MESSAGE, 5000);
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
            // Swing 不可用 → 通过子进程弹窗确认，绝不静默同步
            ModLog.info("[LaunchSync] Swing not available, using subprocess confirm dialog (firstRun=%s)",
                    check.localVersionId.isEmpty());
            ModLog.info("[LaunchSync] Diff: %d to download, %d to keep, %d to remove",
                    diff.toDownload.size(), diff.toKeep.size(), diff.toRemove.size());

            // 构建确认信息
            StringBuilder confirmMsg = new StringBuilder();
            confirmMsg.append(String.format("检测到整合包更新！\n\n整合包: %s\n本地版本: %s → 远端版本: %s\n\n",
                    modpackName,
                    check.localVersionId.isEmpty() ? "(首次安装)" : check.localVersionId,
                    check.remoteVersionId.isEmpty() ? "(未声明)" : check.remoteVersionId));
            int addCount = 0, updateCount = 0, removeCount = 0;
            for (ModSyncer.DiffEntry e : diff.toDownload) {
                if (e.action == ModSyncer.DiffAction.ADD) addCount++;
                else if (e.action == ModSyncer.DiffAction.UPDATE) updateCount++;
            }
            removeCount = diff.toRemove.size();
            confirmMsg.append(String.format("更新内容: 新增 %d / 更新 %d / 删除 %d\n\n", addCount, updateCount, removeCount));
            confirmMsg.append("是否立即更新？\n（点击「否」将使用本地现有模组继续游戏）");

            boolean confirmed = SimpleDialog.showConfirm(
                    "MC Mod Auto-Updater — " + modsLabel, confirmMsg.toString());
            if (!confirmed) {
                ModLog.info("[LaunchSync] User declined update via subprocess dialog. Continuing with local mods.");
                SimpleDialog.showAutoClose("MC Mod Auto-Updater — " + modsLabel,
                        "已取消更新，使用本地模组继续。\n如需修改开发者选项，请使用 config 模式。",
                        javax.swing.JOptionPane.INFORMATION_MESSAGE, 3000);
                return LaunchSyncResult.userCancelled(check.remoteVersionId);
            }

            // 用户确认，开始同步
            ModLog.info("[LaunchSync] User confirmed update. Starting sync...");
            ModSyncer.SyncResult result = syncer.sync(null);
            if (!result.failed && !result.remoteVersionId.isEmpty()) {
                config.currentVersionId = result.remoteVersionId;
                saveConfigQuietly(configPath, config);
            }
            ModLog.info("[LaunchSync] Sync done: %d downloaded, %d skipped, %d failed",
                    result.downloadedCount(), result.skippedCount(), result.failedCount());

            // 显示同步结果
            StringBuilder doneMsg = new StringBuilder();
            if (result.failed) {
                doneMsg.append(String.format("同步完成（有失败）！\n下载: %d\n跳过: %d\n失败: %d\n\n请查看日志了解详情。",
                        result.downloadedCount(), result.skippedCount(), result.failedCount()));
            } else if (result.changed) {
                doneMsg.append(String.format("更新完成！\n下载: %d\n跳过: %d\n\n请重启游戏以应用更新。",
                        result.downloadedCount(), result.skippedCount()));
            } else {
                doneMsg.append("所有文件已是最新，无需下载。");
            }
            SimpleDialog.show("MC Mod Auto-Updater — " + modsLabel,
                    doneMsg.toString(), javax.swing.JOptionPane.INFORMATION_MESSAGE);
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
            ModLog.error("[LaunchSync] In-process dialog failed, falling back to subprocess confirm", e);
            // 弹窗失败 → 不自动同步，改为子进程确认
            dialog.dispose();
            String confirmMsg = String.format(
                    "检测到整合包更新！\n\n整合包: %s\n本地版本: %s → 远端版本: %s\n\n更新内容: 新增/更新 %d, 删除 %d\n\n是否更新？",
                    modpackName,
                    check.localVersionId.isEmpty() ? "(首次安装)" : check.localVersionId,
                    check.remoteVersionId.isEmpty() ? "(未声明)" : check.remoteVersionId,
                    diff.toDownload.size(), diff.toRemove.size());
            confirmed = SimpleDialog.showConfirm("MC Mod Auto-Updater — " + modsLabel, confirmMsg);
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
     * 尝试解除 AWT headless 模式。
     *
     * <p>Minecraft modloading 阶段强制 java.awt.headless=true。Java 17+ 模块系统
     * 阻止反射重置 (InaccessibleObjectException)，而 sun.misc.Unsafe 直接操作
     * AWT 内部状态会导致 JVM native 崩溃 (EXCEPTION_ACCESS_VIOLATION)。</p>
     *
     * <p>因此本方法只设置系统属性（无害，某些代码可能检查它），始终返回 false。
     * 所有弹窗改为通过独立 JVM 子进程显示（见 SimpleDialog.spawnDialogProcess），
     * 子进程自带 -Djava.awt.headless=false，完全不碰当前 JVM 的 AWT 状态。</p>
     *
     * @return 始终 false（当前 JVM 内 Swing 不可用）
     */
    private static boolean forceEnableAwt() {
        System.setProperty("java.awt.headless", "false");
        boolean headless = java.awt.GraphicsEnvironment.isHeadless();
        ModLog.info("[LaunchSync] AWT headless=%s (in-process Swing disabled, using subprocess dialogs)",
                headless);
        return false;
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

    /**
     * 简单对话框助手。通过启动独立 JVM 子进程显示弹窗，
     * 完全绕过当前 JVM 的 headless 限制。
     */
    private static final class SimpleDialog {

        static void show(String title, String message, int messageType) {
            spawnDialogProcess("show", title, message, 0, messageType);
        }

        static void showAutoClose(String title, String message, int messageType, int delayMs) {
            spawnDialogProcess("autoclose", title, message, delayMs, messageType);
        }

        /**
         * 显示确认对话框（是/否），阻塞等待用户选择。
         * @return true=是, false=否
         */
        static boolean showConfirm(String title, String message) {
            return spawnConfirmProcess(title, message);
        }

        /**
         * 启动确认对话框子进程，返回用户选择。
         * 退出码 0=是, 1=否, 2=异常。
         */
        private static boolean spawnConfirmProcess(String title, String message) {
            ModLog.info("[LaunchSync] Spawning confirm dialog subprocess");
            try {
                String javaBin = getJavaBinary();
                String jarPath = getOurJarPath();
                if (javaBin == null || jarPath == null) {
                    ModLog.warn("[LaunchSync] Cannot spawn confirm dialog: java=%s jar=%s", javaBin, jarPath);
                    return false;
                }

                List<String> cmd = new java.util.ArrayList<>();
                cmd.add(javaBin);
                cmd.add("-Djava.awt.headless=false");
                cmd.add("-Dstdout.encoding=UTF-8");
                cmd.add("-Dstderr.encoding=UTF-8");
                cmd.add("-cp");
                cmd.add(jarPath);
                cmd.add("com.cyhqw.mcmodupdater.common.launcher.DialogMain");
                cmd.add("confirm");
                cmd.add(title);
                cmd.add(message);

                ModLog.info("[LaunchSync] Command: %s", String.join(" ", cmd));

                ProcessBuilder pb = new ProcessBuilder(cmd);
                pb.redirectErrorStream(true);
                Process p = pb.start();

                // 后台读取输出
                Thread outputReader = new Thread(() -> {
                    try {
                        byte[] out = p.getInputStream().readAllBytes();
                        if (out.length > 0) {
                            String text = new String(out, java.nio.charset.StandardCharsets.UTF_8).trim();
                            if (!text.isEmpty()) {
                                ModLog.info("[LaunchSync] Confirm dialog output: %s", text);
                            }
                        }
                    } catch (Exception ignored) {
                    }
                }, "confirm-dialog-reader");
                outputReader.setDaemon(true);
                outputReader.start();

                boolean exited = p.waitFor(120000, java.util.concurrent.TimeUnit.MILLISECONDS);
                if (!exited) {
                    p.destroyForcibly();
                    ModLog.warn("[LaunchSync] Confirm dialog timed out, treating as NO");
                    return false;
                }
                int exitCode = p.exitValue();
                ModLog.info("[LaunchSync] Confirm dialog exited with code %d", exitCode);
                return exitCode == 0;
            } catch (Exception e) {
                ModLog.warn("[LaunchSync] Failed to spawn confirm dialog: %s", e.toString());
                return false;
            }
        }

        /**
         * 启动独立 JVM 子进程显示弹窗。子进程自带 -Djava.awt.headless=false，
         * 不受当前 JVM 的 headless 限制，也不碰当前 JVM 的 AWT 状态（不会崩溃）。
         */
        private static void spawnDialogProcess(String mode, String title, String message,
                                                int delayMs, int messageType) {
            ModLog.info("[LaunchSync] Spawning dialog subprocess: mode=%s delayMs=%d", mode, delayMs);
            try {
                String javaBin = getJavaBinary();
                String jarPath = getOurJarPath();
                ModLog.info("[LaunchSync] java=%s jar=%s", javaBin, jarPath);
                if (javaBin == null || jarPath == null) {
                    ModLog.warn("[LaunchSync] Cannot spawn dialog: java=%s jar=%s", javaBin, jarPath);
                    return;
                }

                List<String> cmd = new java.util.ArrayList<>();
                cmd.add(javaBin);
                cmd.add("-Djava.awt.headless=false");
                // 在 Windows 上指定输出编码为 UTF-8，避免乱码
                cmd.add("-Dstdout.encoding=UTF-8");
                cmd.add("-Dstderr.encoding=UTF-8");
                cmd.add("-cp");
                cmd.add(jarPath);
                cmd.add("com.cyhqw.mcmodupdater.common.launcher.DialogMain");
                cmd.add(mode);
                cmd.add(title);
                cmd.add(message);
                cmd.add(String.valueOf(delayMs));
                cmd.add(String.valueOf(messageType));

                ModLog.info("[LaunchSync] Command: %s", String.join(" ", cmd));

                ProcessBuilder pb = new ProcessBuilder(cmd);
                pb.redirectErrorStream(true);
                Process p = pb.start();

                // 在后台线程读取子进程输出，防止管道阻塞
                Thread outputReader = new Thread(() -> {
                    try {
                        byte[] out = p.getInputStream().readAllBytes();
                        if (out.length > 0) {
                            // 尝试 UTF-8，回退到系统默认编码
                            String text = new String(out, java.nio.charset.StandardCharsets.UTF_8).trim();
                            if (!text.isEmpty()) {
                                ModLog.info("[LaunchSync] Dialog subprocess output: %s", text);
                            }
                        }
                    } catch (Exception ignored) {
                    }
                }, "dialog-output-reader");
                outputReader.setDaemon(true);
                outputReader.start();

                // 等待子进程退出，超时则强制终止
                int timeout = (delayMs > 0 ? delayMs : 30000) + 10000;
                boolean exited = p.waitFor(timeout, java.util.concurrent.TimeUnit.MILLISECONDS);
                if (!exited) {
                    p.destroyForcibly();
                    ModLog.warn("[LaunchSync] Dialog subprocess timed out, killed");
                } else {
                    ModLog.info("[LaunchSync] Dialog subprocess exited with code %d", p.exitValue());
                }
            } catch (Exception e) {
                ModLog.warn("[LaunchSync] Failed to spawn dialog subprocess: %s", e.toString());
            }
        }

        /** 获取 java 可执行文件路径。 */
        private static String getJavaBinary() {
            String javaHome = System.getProperty("java.home");
            if (javaHome == null) return null;
            String osName = System.getProperty("os.name", "").toLowerCase();
            String exe = osName.contains("win") ? "java.exe" : "java";
            java.io.File f = new java.io.File(javaHome, "bin" + java.io.File.separator + exe);
            return f.exists() ? f.getAbsolutePath() : null;
        }

        /**
         * 获取当前 jar 文件路径，用于子进程 classpath。
         * 多种方式尝试，确保 Windows/Linux 都能正确获取路径。
         */
        private static String getOurJarPath() {
            // 方式 1：通过 ProtectionDomain CodeSource
            try {
                java.security.CodeSource cs = LaunchSyncRunner.class.getProtectionDomain().getCodeSource();
                if (cs != null && cs.getLocation() != null) {
                    java.net.URL url = cs.getLocation();
                    ModLog.info("[LaunchSync] CodeSource URL: %s", url);
                    // 用 File(url.toURI()) 而不是 url.toURI().getPath()，
                    // 前者正确处理 Windows 路径 (去掉前导 /)
                    java.io.File file = new java.io.File(url.toURI());
                    if (file.exists()) {
                        ModLog.info("[LaunchSync] Jar found via CodeSource: %s", file.getAbsolutePath());
                        return file.getAbsolutePath();
                    }
                    ModLog.warn("[LaunchSync] CodeSource file does not exist: %s", file.getAbsolutePath());
                }
            } catch (Exception e) {
                ModLog.warn("[LaunchSync] CodeSource method failed: %s", e.getMessage());
            }

            // 方式 2：通过 class resource URL
            try {
                String className = LaunchSyncRunner.class.getName().replace('.', '/') + ".class";
                java.net.URL url = LaunchSyncRunner.class.getClassLoader().getResource(className);
                if (url != null) {
                    ModLog.info("[LaunchSync] Class resource URL: %s", url);
                    // URL 格式：jar:file:/path/to/jar!/com/.../Class.class
                    String urlStr = url.toString();
                    if (urlStr.startsWith("jar:")) {
                        // 提取 jar 路径
                        int idx = urlStr.indexOf("!/");
                        if (idx > 0) {
                            String jarUrl = urlStr.substring(4, idx); // 去掉 "jar:" 和 "!/"
                            java.io.File file = new java.io.File(new java.net.URI(jarUrl));
                            if (file.exists()) {
                                ModLog.info("[LaunchSync] Jar found via resource URL: %s", file.getAbsolutePath());
                                return file.getAbsolutePath();
                            }
                        }
                    } else if (urlStr.startsWith("file:")) {
                        // 可能在开发环境（classes 目录）
                        java.io.File file = new java.io.File(url.toURI());
                        // 找上级直到 jar 目录——开发环境直接用 classpath
                        ModLog.info("[LaunchSync] Dev environment detected, class dir: %s", file);
                    }
                }
            } catch (Exception e) {
                ModLog.warn("[LaunchSync] Resource URL method failed: %s", e.getMessage());
            }

            // 方式 3：在 mods 目录搜索 mc-mod-autoupdater*.jar
            try {
                String gameDir = System.getProperty("user.dir");
                if (gameDir != null) {
                    java.io.File modsDir = new java.io.File(gameDir, "mods");
                    if (modsDir.exists() && modsDir.isDirectory()) {
                        ModLog.info("[LaunchSync] Searching mods dir: %s", modsDir.getAbsolutePath());
                        java.io.File[] jars = modsDir.listFiles((dir, name) ->
                                name.toLowerCase().contains("mc-mod-autoupdater") && name.toLowerCase().endsWith(".jar"));
                        if (jars != null && jars.length > 0) {
                            ModLog.info("[LaunchSync] Jar found via mods dir search: %s", jars[0].getAbsolutePath());
                            return jars[0].getAbsolutePath();
                        }
                    }
                }
            } catch (Exception e) {
                ModLog.warn("[LaunchSync] Mods dir search failed: %s", e.getMessage());
            }

            ModLog.warn("[LaunchSync] Could not find own jar path by any method");
            return null;
        }
    }
}
