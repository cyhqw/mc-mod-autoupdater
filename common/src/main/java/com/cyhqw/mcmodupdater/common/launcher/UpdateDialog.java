package com.cyhqw.mcmodupdater.common.launcher;

import com.cyhqw.mcmodupdater.common.syncer.ModSyncer;
import com.cyhqw.mcmodupdater.common.syncer.ModSyncer.DiffAction;
import com.cyhqw.mcmodupdater.common.syncer.ModSyncer.DiffEntry;
import com.cyhqw.mcmodupdater.common.syncer.ModSyncer.DiffResult;
import com.cyhqw.mcmodupdater.common.syncer.ModSyncer.SyncResult;
import com.cyhqw.mcmodupdater.common.util.ModLog;

import javax.swing.BorderFactory;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JDialog;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JProgressBar;
import javax.swing.JScrollPane;
import javax.swing.JTable;
import javax.swing.JTextArea;
import javax.swing.SwingUtilities;
import javax.swing.WindowConstants;
import javax.swing.table.AbstractTableModel;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.Frame;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 更新对话框 GUI。包含：
 * - 标题 + 状态文本
 * - 差异摘要（X 个新增 / Y 个更新 / Z 个删除 / W 个保留）
 * - 可展开的差异详情表格（默认收起）
 * - 进度条 + 当前正在处理的文件名
 * - 按钮：确认更新 / 取消 / 关闭
 *
 * <p>使用 Swing 实现，JDK 自带，不依赖 MC 类。所有 UI 操作在 EDT 上执行。</p>
 *
 * <p>使用流程：</p>
 * <ol>
 *   <li>构造时传入 CheckResult 和 DiffResult</li>
 *   <li>调用 {@link #showAndAwaitConfirm()} 阻塞等待用户选择</li>
 *   <li>若用户点确认，调用方开始同步，通过 {@link ProgressCallback} 回调更新进度</li>
 *   <li>同步完成后调用 {@link #showComplete} 显示结果</li>
 * </ol>
 */
public final class UpdateDialog {

    private final String modsLabel;
    private final String modpackName;
    private final String localVersion;
    private final String remoteVersion;
    private final DiffResult diff;

    private JDialog dialog;
    private JLabel statusLabel;
    private JLabel summaryLabel;
    private JLabel currentFileLabel;
    private JProgressBar progressBar;
    private JButton toggleDetailsBtn;
    private JPanel detailsPanel;
    private JTable detailsTable;
    private JTextArea logArea;
    private JButton confirmBtn;
    private JButton cancelBtn;
    private JButton closeBtn;

    private boolean detailsVisible = false;
    private final AtomicReference<UserChoice> choice = new AtomicReference<>(UserChoice.NONE);

    enum UserChoice { NONE, CONFIRM, CANCEL, CLOSED }

    public UpdateDialog(String modsLabel, String modpackName,
                        String localVersion, String remoteVersion,
                        DiffResult diff) {
        this.modsLabel = modsLabel;
        this.modpackName = modpackName;
        this.localVersion = localVersion;
        this.remoteVersion = remoteVersion;
        this.diff = diff;
    }

    /**
     * 显示对话框并阻塞等待用户选择（确认/取消）。
     * 必须在非 EDT 线程调用（会阻塞调用线程）。
     *
     * @return true 表示用户点了"确认更新"，false 表示取消或关闭
     */
    public boolean showAndAwaitConfirm() throws Exception {
        SwingUtilities.invokeAndWait(this::buildAndShowDialog);

        // 等待用户选择
        while (choice.get() == UserChoice.NONE) {
            try {
                Thread.sleep(50);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        return choice.get() == UserChoice.CONFIRM;
    }

    private void buildAndShowDialog() {
        // 用非 modal dialog，避免 setVisible(true) 阻塞 EDT
        // 这样 invokeLater 的进度更新能正常工作
        dialog = new JDialog((Frame) null, "MC Mod Auto-Updater — " + modsLabel, false);
        dialog.setDefaultCloseOperation(WindowConstants.DO_NOTHING_ON_CLOSE);
        dialog.addWindowListener(new WindowAdapter() {
            @Override
            public void windowClosing(WindowEvent e) {
                if (choice.compareAndSet(UserChoice.NONE, UserChoice.CLOSED)) {
                    dialog.dispose();
                }
            }
        });

        JPanel root = new JPanel();
        root.setLayout(new BoxLayout(root, BoxLayout.Y_AXIS));
        root.setBorder(BorderFactory.createEmptyBorder(12, 12, 12, 12));

        // 1. 标题
        JLabel titleLabel = new JLabel("检测到整合包更新");
        titleLabel.setFont(titleLabel.getFont().deriveFont(Font.BOLD, 16f));
        titleLabel.setAlignmentX(0f);
        root.add(titleLabel);
        root.add(vstrut(6));

        // 2. 状态文本
        statusLabel = new JLabel(String.format(
                "<html>整合包名称: <b>%s</b><br>本地版本: <b>%s</b> → 远端版本: <b>%s</b></html>",
                modpackName, localVersion, remoteVersion));
        statusLabel.setAlignmentX(0f);
        root.add(statusLabel);
        root.add(vstrut(8));

        // 3. 差异摘要
        int addCount = 0, updateCount = 0, removeCount = 0, keepCount = 0, playerOwnedCount = 0;
        for (DiffEntry e : diff.toDownload) {
            if (e.action == DiffAction.ADD) addCount++;
            else if (e.action == DiffAction.UPDATE) updateCount++;
        }
        removeCount = diff.toRemove.size();
        keepCount = diff.toKeep.size();
        playerOwnedCount = diff.playerOwned.size();
        summaryLabel = new JLabel(String.format(
                "<html>差异摘要: <font color='#2e7d32'>新增 %d</font> / "
                + "<font color='#1565c0'>更新 %d</font> / "
                + "<font color='#c62828'>删除 %d</font> / "
                + "<font color='#616161'>保留 %d</font> / "
                + "<font color='#9e9e9e'>玩家 mod %d（不受影响）</font></html>",
                addCount, updateCount, removeCount, keepCount, playerOwnedCount));
        summaryLabel.setAlignmentX(0f);
        root.add(summaryLabel);
        root.add(vstrut(8));

        // 4. 展开/收起按钮 + 详情表格
        JPanel togglePanel = new JPanel(new FlowLayout(FlowLayout.LEFT, 0, 0));
        toggleDetailsBtn = new JButton("▶ 显示详情");
        toggleDetailsBtn.addActionListener(e -> toggleDetails());
        togglePanel.add(toggleDetailsBtn);
        togglePanel.setAlignmentX(0f);
        root.add(togglePanel);

        detailsPanel = new JPanel(new BorderLayout());
        detailsPanel.setVisible(false);
        detailsTable = new JTable(new DiffTableModel(diff));
        detailsTable.setAutoCreateRowSorter(true);
        detailsTable.setFillsViewportHeight(true);
        JScrollPane scrollPane = new JScrollPane(detailsTable);
        scrollPane.setPreferredSize(new Dimension(700, 200));
        detailsPanel.add(scrollPane, BorderLayout.CENTER);
        detailsPanel.setAlignmentX(0f);
        root.add(detailsPanel);
        root.add(vstrut(8));

        // 5. 进度区域（初始隐藏）
        JPanel progressPanel = new JPanel();
        progressPanel.setLayout(new BoxLayout(progressPanel, BoxLayout.Y_AXIS));
        progressPanel.setAlignmentX(0f);
        currentFileLabel = new JLabel(" ");
        currentFileLabel.setFont(currentFileLabel.getFont().deriveFont(11f));
        currentFileLabel.setForeground(new Color(80, 80, 80));
        currentFileLabel.setAlignmentX(0f);
        progressPanel.add(currentFileLabel);
        progressPanel.add(vstrut(4));
        progressBar = new JProgressBar(0, 100);
        progressBar.setStringPainted(true);
        progressBar.setString("等待确认");
        progressBar.setValue(0);
        progressBar.setPreferredSize(new Dimension(700, 20));
        progressBar.setAlignmentX(0f);
        progressPanel.add(progressBar);
        root.add(progressPanel);
        root.add(vstrut(8));

        // 6. 日志区域（可选，默认收起在 detailsPanel 外）
        logArea = new JTextArea(4, 60);
        logArea.setEditable(false);
        logArea.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 11));
        JScrollPane logScroll = new JScrollPane(logArea);
        logScroll.setPreferredSize(new Dimension(700, 80));
        logScroll.setAlignmentX(0f);
        root.add(logScroll);
        root.add(vstrut(8));

        // 7. 按钮区
        JPanel buttonPanel = new JPanel(new FlowLayout(FlowLayout.RIGHT));
        confirmBtn = new JButton("确认更新");
        confirmBtn.addActionListener(e -> {
            if (choice.compareAndSet(UserChoice.NONE, UserChoice.CONFIRM)) {
                confirmBtn.setEnabled(false);
                cancelBtn.setEnabled(false);
                progressBar.setString("准备中...");
            }
        });
        cancelBtn = new JButton("取消（使用本地版本）");
        cancelBtn.addActionListener(e -> {
            if (choice.compareAndSet(UserChoice.NONE, UserChoice.CANCEL)) {
                dialog.dispose();
            }
        });
        closeBtn = new JButton("关闭");
        closeBtn.addActionListener(e -> {
            if (choice.compareAndSet(UserChoice.NONE, UserChoice.CLOSED)) {
                dialog.dispose();
            }
        });
        closeBtn.setEnabled(false); // 同步完成后才启用
        buttonPanel.add(confirmBtn);
        buttonPanel.add(cancelBtn);
        buttonPanel.add(closeBtn);
        buttonPanel.setAlignmentX(0f);
        root.add(buttonPanel);

        dialog.setContentPane(root);
        dialog.pack();
        dialog.setLocationRelativeTo(null);
        try {
            dialog.setAlwaysOnTop(true);
        } catch (Exception ignored) {}
        dialog.setVisible(true);
    }

    private void toggleDetails() {
        detailsVisible = !detailsVisible;
        detailsPanel.setVisible(detailsVisible);
        toggleDetailsBtn.setText(detailsVisible ? "▼ 隐藏详情" : "▶ 显示详情");
        dialog.pack();
    }

    /** 进度回调。在 EDT 上调用。 */
    public void onProgress(int current, int total, String filename,
                           ModSyncer.ActionStatus status, String detail) {
        SwingUtilities.invokeLater(() -> {
            int pct = total > 0 ? (int) (current * 100L / total) : 0;
            progressBar.setMaximum(total);
            progressBar.setValue(current);
            progressBar.setString(String.format("%d / %d (%d%%)", current, total, pct));
            String statusIcon;
            if (status == ModSyncer.ActionStatus.DOWNLOADED) statusIcon = "✓";
            else if (status == ModSyncer.ActionStatus.FAILED) statusIcon = "✗";
            else statusIcon = "→";
            currentFileLabel.setText(String.format("%s [%d/%d] %s — %s",
                    statusIcon, current, total, filename, status));
            // 追加到日志
            logArea.append(String.format("%s  %s  %s\n", status, filename,
                    status == ModSyncer.ActionStatus.FAILED ? detail : ""));
            logArea.setCaretPosition(logArea.getDocument().getLength());
            // 更新表格行状态（通过重绘）
            ((DiffTableModel) detailsTable.getModel()).fireTableDataChanged();
        });
    }

    /** 同步完成时调用。 */
    public void showComplete(SyncResult result) {
        SwingUtilities.invokeLater(() -> {
            confirmBtn.setEnabled(false);
            cancelBtn.setEnabled(false);
            closeBtn.setEnabled(true);
            if (result.failed && result.errorMessage != null) {
                progressBar.setString("同步失败");
                currentFileLabel.setText("失败: " + result.errorMessage);
                logArea.append("\n=== 同步失败 ===\n" + result.errorMessage + "\n");
            } else {
                progressBar.setMaximum(100);
                progressBar.setValue(100);
                progressBar.setString("完成");
                currentFileLabel.setText(String.format(
                        "完成: 下载 %d, 跳过 %d, 失败 %d",
                        result.downloadedCount(), result.skippedCount(), result.failedCount()));
                logArea.append(String.format("\n=== 同步完成 ===\n下载: %d\n跳过: %d\n失败: %d\n删除孤儿: %d\n",
                        result.downloadedCount(), result.skippedCount(), result.failedCount(),
                        result.removedOrphans.size()));
                if (result.changed) {
                    logArea.append("\n请重启游戏以应用更新。\n");
                }
            }
            logArea.setCaretPosition(logArea.getDocument().getLength());
        });
    }

    /** 等待用户点击关闭按钮。 */
    public void awaitClose() {
        while (choice.get() != UserChoice.CLOSED && !closeBtn.isEnabled()) {
            try {
                Thread.sleep(50);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        // 等用户点关闭
        while (closeBtn.isEnabled() && dialog != null && dialog.isDisplayable()) {
            try {
                Thread.sleep(50);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
    }

    public void dispose() {
        if (dialog != null) {
            SwingUtilities.invokeLater(() -> dialog.dispose());
        }
    }

    private static javax.swing.Box.Filler vstrut(int height) {
        return new javax.swing.Box.Filler(
                new Dimension(0, height), new Dimension(0, height), new Dimension(0, height));
    }

    /** 差异表格的数据模型。 */
    private static final class DiffTableModel extends AbstractTableModel {
        private final String[] columns = {"#", "文件名", "操作", "本地大小", "远端大小", "URL"};
        private final List<DiffEntry> rows;
        private final java.util.List<ModSyncer.SyncAction> actions = new java.util.ArrayList<>();

        DiffTableModel(DiffResult diff) {
            rows = new java.util.ArrayList<>();
            rows.addAll(diff.toDownload);
            rows.addAll(diff.toRemove);
            rows.addAll(diff.toKeep);
            rows.addAll(diff.playerOwned);
        }

        @Override
        public int getRowCount() {
            return rows.size();
        }

        @Override
        public int getColumnCount() {
            return columns.length;
        }

        @Override
        public String getColumnName(int column) {
            return columns[column];
        }

        @Override
        public Object getValueAt(int row, int column) {
            DiffEntry e = rows.get(row);
            switch (column) {
                case 0: return row + 1;
                case 1: return e.filename;
                case 2: return actionLabel(e.action);
                case 3: return e.localSize > 0 ? humanSize(e.localSize) : "-";
                case 4: return e.remoteSize > 0 ? humanSize(e.remoteSize) : "-";
                case 5: return e.downloadUrl != null ? e.downloadUrl : "";
                default: return "";
            }
        }

        private String actionLabel(DiffAction a) {
            switch (a) {
                case ADD: return "新增";
                case UPDATE: return "更新";
                case KEEP: return "保留";
                case REMOVE: return "删除";
                case PLAYER_OWNED: return "玩家 mod";
                default: return a.toString();
            }
        }

        private String humanSize(long bytes) {
            if (bytes < 1024) return bytes + " B";
            if (bytes < 1024 * 1024) return String.format("%.1f KB", bytes / 1024.0);
            return String.format("%.1f MB", bytes / (1024.0 * 1024));
        }
    }
}
