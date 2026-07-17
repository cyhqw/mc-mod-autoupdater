package com.cyhqw.mcmodupdater.common.launcher;

import javax.swing.JDialog;
import javax.swing.JOptionPane;
import javax.swing.Timer;
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;

/**
 * 独立 JVM 进程入口，用于在 headless 的 Minecraft 进程之外显示 Swing 弹窗。
 *
 * <p>Minecraft modloading 阶段强制 java.awt.headless=true，且 Java 17+ 模块系统
 * 阻止反射解除。与其在当前 JVM 内挣扎（Unsafe 会导致 native 崩溃），
 * 不如启动一个全新的子 JVM 进程，自带 -Djava.awt.headless=false。</p>
 *
 * <p>用法：<br>
 * <pre>
 * java -Djava.awt.headless=false -cp &lt;jar&gt; com.cyhqw.mcmodupdater.common.launcher.DialogMain \
 *     &lt;mode&gt; &lt;title&gt; &lt;message&gt; [delayMs] [messageType]
 * </pre>
 *
 * <p>Modes:
 * <ul>
 *   <li><b>autoclose</b> — 显示弹窗，delayMs 毫秒后自动关闭。退出码 0。</li>
 *   <li><b>show</b> — 显示模态弹窗，等待用户点确定。退出码 0。</li>
 * </ul>
 *
 * <p>退出码：0=正常关闭，2=异常。
 */
public final class DialogMain {

    public static void main(String[] args) {
        try {
            if (args.length < 3) {
                System.err.println("Usage: DialogMain <mode> <title> <message> [delayMs] [messageType]");
                System.exit(2);
                return;
            }

            String mode = args[0];
            String title = args[1];
            String message = args[2];
            int delayMs = args.length > 3 ? Integer.parseInt(args[3]) : 5000;
            int messageType = args.length > 4 ? Integer.parseInt(args[4]) : JOptionPane.INFORMATION_MESSAGE;

            switch (mode) {
                case "autoclose":
                    showAutoCloseDialog(title, message, messageType, delayMs);
                    break;
                case "show":
                    JOptionPane.showMessageDialog(null, message, title, messageType);
                    break;
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
        // 对话框关闭后（用户手动关或定时器触发），退出
        System.exit(0);
    }

    private DialogMain() {
    }
}
