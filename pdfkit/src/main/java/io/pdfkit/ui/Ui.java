package io.pdfkit.ui;

import javax.swing.BorderFactory;
import javax.swing.UIManager;
import java.awt.Color;
import java.awt.Font;

/**
 * 统一视觉：颜色、字体、组件默认值。
 *
 * <p>全应用的颜色和字体只从这里的常量取，界面代码里不出现字面量颜色值。
 * 想调整配色时改这一个文件，不用满项目搜 {@code new Color(...)}。
 *
 * <p>{@link #install()} 必须在<b>创建任何 Swing 组件之前</b>调用：
 * 它通过 {@code UIManager.put} 设置的是「后续新建组件的默认值」，
 * 已经创建出来的组件不会受影响。
 */
public final class Ui {

    /** 主色，用于强调、选中态、主要按钮。 */
    public static final Color ACCENT = new Color(37, 99, 235);
    /** 窗口背景。 */
    public static final Color BG = new Color(248, 249, 251);
    /** 卡片/面板背景（纯白，比 BG 亮，形成层次）。 */
    public static final Color CARD = Color.WHITE;
    /** 边框色。 */
    public static final Color BORDER = new Color(226, 229, 235);
    /** 次要文字（提示、说明）。 */
    public static final Color MUTED = new Color(107, 114, 128);
    /** 成功状态文字。 */
    public static final Color OK = new Color(22, 143, 88);
    /** 失败状态文字。 */
    public static final Color ERR = new Color(197, 48, 48);

    /** 工具类，禁止实例化。 */
    private Ui() {
    }

    /**
     * 应用全局视觉设置。
     *
     * <p>这些 key 大多来自 FlatLaf（如 {@code Button.arc}、{@code TitlePane.unifiedBackground}），
     * 少数是 Swing 标准的（{@code Table.rowHeight}、{@code Panel.background}）。
     * 换主题时这些 key 可能不生效，需要对照 FlatLaf 的文档重新确认。
     *
     * <p>行高与滚动条宽度都显式设了值，不依赖 LAF 默认值：Windows 与 Linux 的默认行高
     * 差异很大，不固定的话界面在不同机器上疏密不一，布局断言（见 {@code UiTest}）也会不稳。
     */
    public static void install() {
        Font base = pickFont();
        // defaultFont 是 FlatLaf 的扩展 key，一次性把未单独设置字体的组件都覆盖到
        UIManager.put("defaultFont", base);
        UIManager.put("Panel.background", BG);
        UIManager.put("OptionPane.background", BG);
        UIManager.put("Table.font", base);
        UIManager.put("Table.rowHeight", 26);
        UIManager.put("Tree.font", base);
        UIManager.put("Tree.rowHeight", 24);
        // 圆角用 8：与 FlatLaf 默认的 6 相比稍柔和，但不会到「过分圆润」的程度
        UIManager.put("Button.arc", 8);
        UIManager.put("Component.arc", 8);
        UIManager.put("TextComponent.arc", 8);
        UIManager.put("ScrollBar.width", 10);
        UIManager.put("Component.focusWidth", 1);
        // 表格只画横线不画竖线：竖线会让本来就窄的任务表显得局促
        UIManager.put("Table.showHorizontalLines", true);
        UIManager.put("Table.showVerticalLines", false);
        // 选中态用浅蓝底 + 黑字，而不是默认的深蓝底 + 白字：
        // 深色选中块在浅色界面里太重，长时间看会累
        UIManager.put("Table.selectionBackground", new Color(219, 234, 254));
        UIManager.put("Table.selectionForeground", Color.BLACK);
        UIManager.put("Tree.selectionBackground", new Color(219, 234, 254));
        UIManager.put("Tree.selectionForeground", Color.BLACK);
        // 标题栏与内容区同色，避免出现「一条深色标题栏压在白底上」的分割感
        UIManager.put("TitlePane.unifiedBackground", true);
    }

    /**
     * 挑选界面字体：优先微软雅黑，逐级退到 Segoe UI、Dialog。
     *
     * <p><b>用 {@code getFamily().equalsIgnoreCase(name)} 判断字体是否真的存在</b>，
     * 这一步不能省：{@code new Font("不存在的字体", ...)} 不会抛异常，也不会返回 null，
     * 而是<b>静默替换成 Dialog</b>。所以只能反过来问「它最后变成了什么」——
     * 家族名与请求名一致，才说明系统里真有这个字体。
     *
     * <p>{@code Dialog} 是无条件接受的那个兜底：它是 Java 的逻辑字体，
     * 在任何平台都必然可用（会映射到系统默认字体）。
     *
     * <p>为什么优先微软雅黑：它在中文字形覆盖、屏幕可读性、字重搭配上都是 Windows 下
     * 最稳妥的选择；而且和内置 PDF 字体（思源黑体）同属无衬线，视觉上连贯。
     */
    private static Font pickFont() {
        String[] candidates = {"Microsoft YaHei UI", "Microsoft YaHei", "Segoe UI", "Dialog"};
        for (String name : candidates) {
            Font f = new Font(name, Font.PLAIN, 13);
            if (f.getFamily().equalsIgnoreCase(name) || name.equals("Dialog")) {
                return f;
            }
        }
        return new Font("Dialog", Font.PLAIN, 13);
    }

    /**
     * 卡片边框：1px 细边框 + 内边距。
     *
     * <p>用于内容分块。内边距上 10 左右 12、下 10——左右比上下略大，
     * 因为文字贴着竖直边界时视觉上比贴着水平边界更「挤」。
     */
    public static javax.swing.border.Border card() {
        return BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(BORDER),
                BorderFactory.createEmptyBorder(10, 12, 10, 12));
    }

    /**
     * 带标题的分区边框，例如「任务队列」「执行结果」。
     *
     * <p>标题由 {@code TitledBorder} 画在边框线上，会吃掉一部分上边距，
     * 所以内边距要比 {@link #card()} 小一些，否则内容离标题太远。
     */
    public static javax.swing.border.Border section(String title) {
        return BorderFactory.createCompoundBorder(
                BorderFactory.createTitledBorder(BorderFactory.createLineBorder(BORDER), title),
                BorderFactory.createEmptyBorder(6, 8, 6, 8));
    }
}
