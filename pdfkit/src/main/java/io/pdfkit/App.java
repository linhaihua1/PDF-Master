package io.pdfkit;

import com.formdev.flatlaf.FlatLightLaf;
import io.pdfkit.ui.MainFrame;
import io.pdfkit.ui.Ui;
import io.pdfkit.util.Log;
import io.pdfkit.util.Sys;

import javax.swing.JOptionPane;
import javax.swing.SwingUtilities;
import javax.swing.UIManager;
import java.awt.GraphicsEnvironment;

/** 程序入口。所有依赖随安装包分发，用户机器不需要装 Java 或任何运行库。 */
public final class App {

    private App() {
    }

    public static void main(String[] args) {
        Log.init(Sys.logsDir());
        Log.info("PdfKit 启动，数据目录 %s", Sys.dataDir());
        Log.info("Java %s / %s / %s", System.getProperty("java.version"),
                System.getProperty("java.vendor"), System.getProperty("os.name"));

        if (!Sys.acquireSingleInstance()) {
            JOptionPane.showMessageDialog(null, "PdfKit 已经在运行了。", "提示", JOptionPane.INFORMATION_MESSAGE);
            return;
        }

        Thread.setDefaultUncaughtExceptionHandler((t, e) -> Log.error("未捕获异常，线程 " + t.getName(), e));

        try {
            if (!GraphicsEnvironment.isHeadless()) {
                FlatLightLaf.setup();
                Ui.install();
            }
        } catch (Exception e) {
            Log.warn("主题初始化失败，使用默认外观：%s", e.getMessage());
            try {
                UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName());
            } catch (Exception ignored) {
                // 默认外观也能用
            }
        }

        SwingUtilities.invokeLater(() -> {
            try {
                MainFrame frame = new MainFrame();
                frame.setVisible(true);
                Log.info("主窗口已显示，注册能力 %d 项", io.pdfkit.core.Registry.count());
            } catch (Exception e) {
                Log.error("主窗口创建失败", e);
                JOptionPane.showMessageDialog(null, "启动失败：" + e.getMessage() + "\n日志：" + Log.currentFile(),
                        "启动失败", JOptionPane.ERROR_MESSAGE);
            }
        });
    }
}
