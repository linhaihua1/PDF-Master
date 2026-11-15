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

/**
 * 程序入口。所有依赖随安装包分发，用户机器不需要装 Java 或任何运行库。
 *
 * <p><b>启动顺序是有讲究的，不要随意调整</b>：
 * <ol>
 *   <li>{@link Log#init} —— 必须最先。后面任何一步出问题都要靠日志定位，
 *       晚初始化会让最早期（也最容易出问题）的失败无声无息。</li>
 *   <li><b>单实例检查</b> —— 放在主题初始化之前。第二个实例只是为了弹个提示就退出，
 *       没必要先花时间加载 FlatLaf、构建 UI 主题。</li>
 *   <li><b>未捕获异常处理器</b> —— 在创建任何界面之前装上。否则窗口构建过程中的
 *       异常会直接打到看不见的 stderr，用户只看到「闪一下就没了」。</li>
 *   <li><b>外观（LAF）初始化</b> —— 必须在创建组件之前，理由见 {@link Ui#install}。</li>
 *   <li><b>建窗口</b> —— 在 EDT 上执行。</li>
 * </ol>
 *
 * <p><b>为什么主题失败不算致命</b>：FlatLaf 初始化可能因字体、图形环境异常而失败。
 * 这时退到系统默认外观，界面会难看一些但功能完好。为了「好看」而让程序起不来说不通。
 *
 * <p><b>无头环境检查</b>：{@code GraphicsEnvironment.isHeadless()} 为真时跳过 LAF 初始化。
 * 测试环境里跑启动探针（{@code StartupProbe}）时不会触发，但某些 CI 环境会是 headless，
 * 不跳过的话会在 LAF 阶段抛 HeadlessException。
 */
public final class App {

    /** 工具类，禁止实例化。 */
    private App() {
    }

    /**
     * 程序入口。
     *
     * @param args 当前未使用任何命令行参数
     */
    public static void main(String[] args) {
        // 1) 日志最先，保证后续每一步的失败都有痕迹
        Log.init(Sys.logsDir());
        Log.info("PdfKit 启动，数据目录 %s", Sys.dataDir());
        Log.info("Java %s / %s / %s", System.getProperty("java.version"),
                System.getProperty("java.vendor"), System.getProperty("os.name"));

        // 2) 单实例：已有实例在跑就直接提示退出，不重复占用内存与文件锁
        if (!Sys.acquireSingleInstance()) {
            JOptionPane.showMessageDialog(null, "PdfKit 已经在运行了。", "提示", JOptionPane.INFORMATION_MESSAGE);
            return;
        }

        // 3) 全局异常兜底：任何线程里未捕获的异常都进日志，而不是消失在控制台
        Thread.setDefaultUncaughtExceptionHandler((t, e) -> Log.error("未捕获异常，线程 " + t.getName(), e));

        // 4) 外观初始化。FlatLaf 失败时退到系统默认外观——难看但能用
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
                // 连系统外观都设不上时用 Swing 内置默认值，同样能跑
            }
        }

        // 5) 建窗口。必须走 invokeLater：Swing 组件的创建与修改只能在 EDT 上做
        SwingUtilities.invokeLater(() -> {
            try {
                MainFrame frame = new MainFrame();
                frame.setVisible(true);
                Log.info("主窗口已显示，注册能力 %d 项", io.pdfkit.core.Registry.count());
            } catch (Exception e) {
                Log.error("主窗口创建失败", e);
                // 把日志路径一起告诉用户，方便反馈问题时直接发文件
                JOptionPane.showMessageDialog(null, "启动失败：" + e.getMessage() + "\n日志：" + Log.currentFile(),
                        "启动失败", JOptionPane.ERROR_MESSAGE);
            }
        });
    }
}
