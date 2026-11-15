package io.pdfkit;

import io.pdfkit.core.Capability;
import io.pdfkit.core.Param;
import io.pdfkit.core.Registry;
import io.pdfkit.ui.FormPanel;
import io.pdfkit.ui.MainFrame;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

import javax.swing.JCheckBox;
import javax.swing.JComboBox;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JLayeredPane;
import javax.swing.JPanel;
import javax.swing.JPasswordField;
import javax.swing.JTextField;
import javax.swing.JTree;
import javax.swing.SwingUtilities;
import javax.swing.tree.DefaultMutableTreeNode;
import java.awt.Component;
import java.awt.Container;
import java.awt.GraphicsEnvironment;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeFalse;

/**
 * S2 UI 测试：桌面 Swing 应用无法用 Playwright，改为控件树级遍历。
 *
 * <p>覆盖：能力树是否 1:1 映射注册表、每项能力能否构建表单、控件类型是否与参数声明一致、
 * 默认值是否回填、无参能力是否给出提示。
 */
@Timeout(value = 300, unit = TimeUnit.SECONDS)
class UiTest {

    private static final List<String> violations = new ArrayList<>();
    private static MainFrame frame;

    @BeforeAll
    static void setUp() throws Exception {
        assumeFalse(GraphicsEnvironment.isHeadless(), "无图形环境，S2 无法执行");
        frame = onEdt(() -> {
            MainFrame f = new MainFrame();
            // 移到可视区外：需要真实窗口 peer 才能算出分区高度，但不必打扰屏幕
            f.setLocation(-4000, -4000);
            f.setVisible(true);
            return f;
        });
    }

    @BeforeEach
    void resetViolations() {
        violations.clear();
    }

    @AfterAll
    static void tearDown() throws Exception {
        if (frame != null) {
            onEdt(() -> {
                frame.dispose();
                return null;
            });
        }
    }

    // ==================== T2.1 能力树完整性 ====================

    @Test
    void treeMapsEveryCapabilityOnce() throws Exception {
        JTree tree = (JTree) read(frame, "tree");
        DefaultMutableTreeNode root = (DefaultMutableTreeNode) tree.getModel().getRoot();
        assertEquals(Registry.categories().size(), root.getChildCount(), "一级分类数应与注册表一致");

        List<String> leafTitles = new ArrayList<>();
        for (int i = 0; i < root.getChildCount(); i++) {
            DefaultMutableTreeNode cat = (DefaultMutableTreeNode) root.getChildAt(i);
            for (int j = 0; j < cat.getChildCount(); j++) {
                DefaultMutableTreeNode leaf = (DefaultMutableTreeNode) cat.getChildAt(j);
                leafTitles.add(leaf.toString());
            }
        }
        assertEquals(Registry.count(), leafTitles.size(), "叶子节点数应等于注册能力数");
        assertEquals(leafTitles.size(), leafTitles.stream().distinct().count(), "树里存在同名叶子（用户无法区分）");
        assertTrue(violations.isEmpty(), String.join("\n", violations));
    }

    // ==================== T2.2 表单构建 ====================

    @Test
    void everyCapabilityBuildsFormWithMatchingControls() throws Exception {
        for (Capability cap : Registry.all()) {
            FormPanel panel = onEdt(() -> new FormPanel(cap.params()));
            panel.setSize(600, 400);
            // 触发布局，暴露 NPE / 非法默认值导致的异常
            onEdt(() -> {
                panel.doLayout();
                return null;
            });

            Map<String, JComponent> fields = fields(panel);
            if (cap.params().isEmpty()) {
                if (!hasTextContaining(panel, "不需要额外设置")) {
                    violations.add("能力[" + cap.id() + "] 无参数却没显示操作提示");
                }
                continue;
            }
            if (fields.size() != cap.params().size()) {
                violations.add("能力[" + cap.id() + "] 参数 " + cap.params().size()
                        + " 个，表单控件 " + fields.size() + " 个");
            }
            for (Param p : cap.params()) {
                JComponent c = fields.get(p.key());
                if (c == null) {
                    violations.add("能力[" + cap.id() + "] 参数[" + p.key() + "] 没有生成控件");
                    continue;
                }
                boolean ok = switch (p.type()) {
                    case BOOL -> c instanceof JCheckBox;
                    case CHOICE -> c instanceof JComboBox<?>;
                    case PASSWORD -> c instanceof JPasswordField;
                    case TEXT, NUMBER -> c instanceof JTextField;
                };
                if (!ok) {
                    violations.add("能力[" + cap.id() + "] 参数[" + p.key() + "] 控件类型与声明 " + p.type() + " 不符："
                            + c.getClass().getSimpleName());
                }
            }
        }
        assertTrue(violations.isEmpty(), summary("T2.2 表单构建"));
    }

    // ==================== T2.3 默认值回填 ====================

    @Test
    void formDefaultsRoundTrip() throws Exception {
        for (Capability cap : Registry.all()) {
            FormPanel panel = onEdt(() -> new FormPanel(cap.params()));
            Map<String, String> values = panel.values();
            for (Param p : cap.params()) {
                String actual = values.get(p.key());
                String expect = switch (p.type()) {
                    case PASSWORD -> "";
                    case BOOL -> p.def() == null || p.def().isBlank() ? "false" : p.def();
                    case CHOICE -> p.def() == null || p.def().isBlank()
                            ? (p.choices().isEmpty() ? "" : p.choices().get(0))
                            : p.def();
                    default -> p.def() == null ? "" : p.def();
                };
                if (actual == null || !actual.equals(expect)) {
                    violations.add("能力[" + cap.id() + "] 参数[" + p.key() + "] 默认值回填错误："
                            + "界面显示【" + actual + "】，声明为【" + expect + "】");
                }
            }
        }
        assertTrue(violations.isEmpty(), summary("T2.3 默认值回填"));
    }

    // ==================== T2.4 逐项选中不报错 ====================

    @Test
    void selectingEveryCapabilityRendersWithoutError() throws Exception {
        Method select = MainFrame.class.getDeclaredMethod("selectCapability", Capability.class);
        select.setAccessible(true);
        JPanel host = (JPanel) read(frame, "formHost");

        for (Capability cap : Registry.all()) {
            onEdt(() -> {
                try {
                    select.invoke(frame, cap);
                } catch (Exception e) {
                    violations.add("能力[" + cap.id() + "] 切换时抛异常：" + rootCause(e));
                }
                return null;
            });
            Object current = read(frame, "current");
            if (current != cap) {
                violations.add("能力[" + cap.id() + "] 切换后 current 未更新");
            }
            if (host.getComponentCount() == 0) {
                violations.add("能力[" + cap.id() + "] 切换后表单区为空");
            }
            // 标题必须出现在表单区，否则用户不知道当前在哪个功能
            if (!hasTextContaining(host, cap.title())) {
                violations.add("能力[" + cap.id() + "] 表单区未显示能力标题「" + cap.title() + "」");
            }
        }
        assertTrue(violations.isEmpty(), summary("T2.4 能力切换"));
    }

    // ==================== T2.5 未选文件时的拦截 ====================

    @Test
    void runWithoutFilesShowsHintInsteadOfSubmitting() throws Exception {
        Method run = MainFrame.class.getDeclaredMethod("run");
        run.setAccessible(true);
        JLabel status = (JLabel) read(frame, "status");
        Object jobsBefore = read(frame, "jobModel");

        onEdt(() -> {
            status.setText("就绪");
            run.invoke(frame);
            return null;
        });

        if (!status.getText().contains("请先选择文件")) {
            violations.add("未选文件点「开始执行」，状态栏提示为【" + status.getText() + "】，应提示「请先选择文件」");
        }
        assertSame(jobsBefore, read(frame, "jobModel"), "未选文件不应产生任务");
        assertTrue(violations.isEmpty(), summary("T2.5 空输入拦截"));
    }

    // ==================== T2.6 主区分区高度可用性 ====================

    @Test
    void jobTableAndResultAreaHaveUsableHeight() throws Exception {
        javax.swing.JTable table = (javax.swing.JTable) read(frame, "jobTable");
        javax.swing.JTextArea result = (javax.swing.JTextArea) read(frame, "resultArea");
        // 默认尺寸与最小尺寸都要够用：小窗口能显示，大窗口才有意义
        int[][] sizes = {{1180, 780}, {1080, 720}};
        for (int[] sz : sizes) {
            onEdt(() -> {
                frame.setSize(sz[0], sz[1]);
                frame.validate();
                return null;
            });
            // 表头约 25px，至少还要露出 2 行数据（每行约 22px）
            if (table.getHeight() < 70) {
                violations.add(sz[0] + "x" + sz[1] + " 下任务表可视高度仅 " + table.getHeight()
                        + " px，表头与任务行会被压扁（应 ≥70）");
            }
            if (result.getHeight() < 50) {
                violations.add(sz[0] + "x" + sz[1] + " 下执行结果区高度仅 " + result.getHeight()
                        + " px，分析类结果无法阅读（应 ≥50）");
            }
        }
        assertTrue(violations.isEmpty(), summary("T2.6 分区高度"));
    }

    // ==================== 脚手架 ====================

    @SuppressWarnings("unchecked")
    private static Map<String, JComponent> fields(FormPanel panel) throws Exception {
        Field f = FormPanel.class.getDeclaredField("fields");
        f.setAccessible(true);
        return (Map<String, JComponent>) f.get(panel);
    }

    private static Object read(Object target, String name) throws Exception {
        Field f = target.getClass().getDeclaredField(name);
        f.setAccessible(true);
        return f.get(target);
    }

    /** 在控件树里找是否有一段文字包含目标串。 */
    private static boolean hasTextContaining(Container root, String needle) {
        for (Component c : root.getComponents()) {
            if (c instanceof JLabel l && l.getText() != null && l.getText().contains(needle)) {
                return true;
            }
            if (c instanceof Container sub && hasTextContaining(sub, needle)) {
                return true;
            }
        }
        return false;
    }

    private static String rootCause(Throwable t) {
        Throwable c = t;
        while (c.getCause() != null && c.getCause() != c) {
            c = c.getCause();
        }
        return c.getClass().getSimpleName() + ": " + c.getMessage();
    }

    private static String summary(String title) {
        StringBuilder sb = new StringBuilder("\n===== " + title + " 违约 " + violations.size() + " 项 =====\n");
        violations.forEach(v -> sb.append("  · ").append(v).append('\n'));
        return sb.toString();
    }

    /** 所有 Swing 操作必须回到 EDT。 */
    private static <T> T onEdt(Callable<T> action) throws Exception {
        AtomicReference<T> ref = new AtomicReference<>();
        AtomicReference<Exception> err = new AtomicReference<>();
        SwingUtilities.invokeAndWait(() -> {
            try {
                ref.set(action.call());
            } catch (Exception e) {
                err.set(e);
            }
        });
        if (err.get() != null) {
            throw err.get();
        }
        return ref.get();
    }
}
