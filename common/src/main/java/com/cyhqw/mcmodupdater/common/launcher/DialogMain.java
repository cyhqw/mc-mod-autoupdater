package com.cyhqw.mcmodupdater.common.launcher;

import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JDialog;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JTextArea;
import javax.swing.JTextField;
import javax.swing.JScrollPane;
import javax.swing.Timer;
import javax.swing.SwingUtilities;
import java.awt.BorderLayout;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Frame;
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;

/**
 * 独立 JVM 进程入口，用于在 headless 的 Minecraft 进程之外显示 Swing 弹窗。
 *
 * <p>Minecraft modloading 阶段强制 java.awt.headless=true，且 Java 17+ 模块系统
 * 阻止反射解除。与其在当前 JVM 内挣扎（Unsafe 会导致 native 崩溃），
 * 不如启动一个全新的子 JVM 进程，自带 -Djava.awt.headless=false。</p>
 *
 * <p>Modes:
 * <ul>
 *   <li><b>autoclose</b> — 显示弹窗，delayMs 毫秒后自动关闭。退出码 0。</li>
 *   <li><b>show</b> — 显示模态弹窗，等待用户点确定。退出码 0。</li>
 *   <li><b>confirm</b> — 显示确认弹窗（是/否）。退出码 0=是, 1=否, 2=异常。</li>
 *   <li><b>config</b> — 显示开发者选项配置弹窗（正则排除模组）。
 *       参数: &lt;configPath&gt;。退出码 0=已保存, 1=取消, 2=异常。</li>
 * </ul>
 *
 * <p>退出码：0=正常，1=用户取消/否，2=异常。</p>
 */
public final class DialogMain {

    public static void main(String[] args) {
        try {
            if (args.length < 1) {
                System.err.println("Usage: DialogMain <mode> ...");
                System.exit(2);
                return;
            }

            String mode = args[0];

            switch (mode) {
                case "autoclose": {
                    if (args.length < 3) { System.exit(2); return; }
                    String title = args[1];
                    String message = args[2];
                    int delayMs = args.length > 3 ? Integer.parseInt(args[3]) : 5000;
                    int messageType = args.length > 4 ? Integer.parseInt(args[4]) : JOptionPane.INFORMATION_MESSAGE;
                    showAutoCloseDialog(title, message, messageType, delayMs);
                    break;
                }
                case "show": {
                    if (args.length < 3) { System.exit(2); return; }
                    String title = args[1];
                    String message = args[2];
                    int messageType = args.length > 3 ? Integer.parseInt(args[3]) : JOptionPane.INFORMATION_MESSAGE;
                    JOptionPane.showMessageDialog(null, message, title, messageType);
                    break;
                }
                case "confirm": {
                    if (args.length < 3) { System.exit(2); return; }
                    String title = args[1];
                    String message = args[2];
                    int result = showConfirmDialog(title, message);
                    System.exit(result);
                    return;
                }
                case "config": {
                    if (args.length < 2) { System.exit(2); return; }
                    String configPath = args[1];
                    int result = showConfigDialog(configPath);
                    System.exit(result);
                    return;
                }
                default:
                    System.err.println("Unknown mode: " + mode);
                    System.exit(2);
                    return;
            }
            System.exit(0);
        } catch (Throwable t) {
            t.printStackTrace();
            System.exit(2);
        }
    }

    /**
     * 显示确认对话框。返回退出码：0=是, 1=否。
     */
    private static int showConfirmDialog(String title, String message) {
        int choice = JOptionPane.showConfirmDialog(
                null, message, title, JOptionPane.YES_NO_OPTION, JOptionPane.QUESTION_MESSAGE);
        return choice == JOptionPane.YES_OPTION ? 0 : 1;
    }

    /**
     * 显示开发者选项配置弹窗。允许编辑 skipModsRegex（正则排除模组）。
     * 保存后写入配置文件。
     *
     * @param configPath 配置文件路径
     * @return 0=已保存, 1=取消
     */
    private static int showConfigDialog(String configPathStr) {
        Path configPath = Path.of(configPathStr);

        // 加载现有配置
        Properties props = new Properties();
        if (Files.exists(configPath)) {
            try {
                props.load(Files.newInputStream(configPath));
            } catch (IOException ignored) {
            }
        }
        String currentRegex = props.getProperty("skipModsRegex", "");

        // 用数组包装实现 final 引用的可变结果
        final int[] result = {1}; // 默认取消

        // 创建对话框
        JDialog dialog = new JDialog((Frame) null, "MC Mod Auto-Updater — 开发者选项", true);
        dialog.setDefaultCloseOperation(JDialog.DISPOSE_ON_CLOSE);

        JPanel root = new JPanel();
        root.setLayout(new BoxLayout(root, BoxLayout.Y_AXIS));
        root.setBorder(BorderFactory.createEmptyBorder(16, 16, 16, 16));

        // 标题
        JLabel titleLabel = new JLabel("开发者选项");
        titleLabel.setFont(titleLabel.getFont().deriveFont(java.awt.Font.BOLD, 16f));
        titleLabel.setAlignmentX(0f);
        root.add(titleLabel);
        root.add(Box.createVerticalStrut(12));

        // 说明
        JTextArea descArea = new JTextArea(
            "正则排除模组：\n" +
            "在此输入正则表达式，匹配文件名（不含路径）的模组将不会被更新。\n" +
            "多个正则用逗号分隔。留空表示不排除任何模组。\n\n" +
            "示例：openysm.*,^jei-.*,create-.*"
        );
        descArea.setEditable(false);
        descArea.setWrapStyleWord(true);
        descArea.setLineWrap(true);
        descArea.setBackground(root.getBackground());
        descArea.setFont(descArea.getFont().deriveFont(12f));
        descArea.setAlignmentX(0f);
        root.add(descArea);
        root.add(Box.createVerticalStrut(12));

        // 输入框
        JLabel fieldLabel = new JLabel("skipModsRegex:");
        fieldLabel.setAlignmentX(0f);
        root.add(fieldLabel);
        root.add(Box.createVerticalStrut(4));
        JTextField regexField = new JTextField(currentRegex, 50);
        regexField.setMaximumSize(new Dimension(Integer.MAX_VALUE, regexField.getPreferredSize().height));
        regexField.setAlignmentX(0f);
        root.add(regexField);
        root.add(Box.createVerticalStrut(16));

        // 验证提示
        JLabel validationLabel = new JLabel(" ");
        validationLabel.setFont(validationLabel.getFont().deriveFont(11f));
        validationLabel.setForeground(new java.awt.Color(200, 0, 0));
        validationLabel.setAlignmentX(0f);
        root.add(validationLabel);
        root.add(Box.createVerticalStrut(8));

        // 按钮区
        JPanel buttonPanel = new JPanel(new FlowLayout(FlowLayout.RIGHT));
        JButton saveBtn = new JButton("保存并关闭");
        JButton cancelBtn = new JButton("取消");

        saveBtn.addActionListener(e -> {
            String regex = regexField.getText().trim();
            // 验证正则表达式是否合法
            if (!regex.isEmpty()) {
                for (String part : regex.split(",")) {
                    String pattern = part.trim();
                    if (!pattern.isEmpty()) {
                        try {
                            java.util.regex.Pattern.compile(pattern);
                        } catch (java.util.regex.PatternSyntaxException ex) {
                            validationLabel.setText("无效的正则表达式: " + ex.getMessage());
                            return;
                        }
                    }
                }
            }
            // 保存到配置文件
            try {
                props.setProperty("skipModsRegex", regex);
                Files.createDirectories(configPath.getParent());
                try (var out = Files.newOutputStream(configPath)) {
                    props.store(out, "MC Mod Auto-Updater client configuration");
                }
                result[0] = 0; // 已保存
                JOptionPane.showMessageDialog(dialog, "配置已保存。\n正则排除规则将在下次启动时生效。",
                        "保存成功", JOptionPane.INFORMATION_MESSAGE);
                dialog.dispose();
            } catch (IOException ex) {
                JOptionPane.showMessageDialog(dialog, "保存失败: " + ex.getMessage(),
                        "错误", JOptionPane.ERROR_MESSAGE);
            }
        });

        cancelBtn.addActionListener(e -> {
            dialog.dispose();
        });

        buttonPanel.add(saveBtn);
        buttonPanel.add(cancelBtn);
        buttonPanel.setAlignmentX(0f);
        root.add(buttonPanel);

        dialog.setContentPane(root);
        dialog.pack();
        dialog.setLocationRelativeTo(null);
        dialog.setVisible(true);

        return result[0];
    }

    private static void showAutoCloseDialog(String title, String message, int messageType, int delayMs) {
        JOptionPane pane = new JOptionPane(message, messageType);
        JDialog dialog = pane.createDialog(title);
        dialog.setDefaultCloseOperation(JDialog.DISPOSE_ON_CLOSE);

        Timer timer = new Timer(delayMs, new ActionListener() {
            @Override
            public void actionPerformed(ActionEvent e) {
                dialog.dispose();
                System.exit(0);
            }
        });
        timer.setRepeats(false);
        timer.start();

        dialog.setVisible(true);
        System.exit(0);
    }

    private DialogMain() {
    }
}
