package io.pdfkit.ui;

import io.pdfkit.core.Param;

import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JCheckBox;
import javax.swing.JComboBox;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JPasswordField;
import javax.swing.JTextField;
import javax.swing.border.EmptyBorder;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 按 Param 声明动态生成表单。新增能力不需要改这里。
 *
 * <p>这是「60 项能力共用一套界面」的实现处：界面里没有任何一个针对具体能力的输入框，
 * 全部由 {@link Param} 列表驱动。加新能力时只写 {@code Capability} 声明，
 * 表单自动就有对应的输入行。
 *
 * <p><b>两个 Map 的分工</b>：
 * <ul>
 *   <li>{@link #fields} —— key → 控件，{@link #values()} 靠它读回用户填的值；</li>
 *   <li>{@link #defs} —— key → 参数定义，供给需要知道参数元信息的地方（如校验时的取值范围）。</li>
 * </ul>
 * 都用 {@link LinkedHashMap} 保持声明顺序，这样读出的参数表顺序与界面上的行一致，
 * 日志里看参数时能和界面对得上。
 *
 * <p><b>读取值必须在 EDT 上调</b>：Swing 组件不是线程安全的，界面线程之外读控件会得到
 * 未定义行为。调用方（{@code MainFrame}）在按钮回调里同步取值，天然满足这一条。
 */
public final class FormPanel extends JPanel {

    /** key → 控件。读值时按这个顺序遍历，保证参数顺序与界面一致。 */
    private final Map<String, JComponent> fields = new LinkedHashMap<>();
    /** key → 参数定义。 */
    private final Map<String, Param> defs = new LinkedHashMap<>();

    /**
     * 按参数列表构建表单。
     *
     * <p>无参数的能力（如「文档基础信息」「移除签名」）给一行说明文字而不是留空白——
     * 空白区域会让用户以为界面没加载出来。
     *
     * @param params 参数定义列表，可为空
     */
    public FormPanel(List<Param> params) {
        // Y_AXIS 纵向堆叠：每个参数一行，行高由内容撑开
        setLayout(new BoxLayout(this, BoxLayout.Y_AXIS));
        setBorder(new EmptyBorder(4, 4, 4, 4));
        // 透明：让父容器的背景色（Ui.BG）透上来，避免出现一块突兀的白色矩形
        setOpaque(false);
        if (params.isEmpty()) {
            JLabel empty = new JLabel("这个功能不需要额外设置，选择文件后直接点「开始执行」。");
            empty.setForeground(Ui.MUTED);
            empty.setAlignmentX(Component.LEFT_ALIGNMENT);
            add(empty);
            return;
        }
        for (Param p : params) {
            defs.put(p.key(), p);
            add(row(p));
            // 行间距用弹性空白而不是行内 padding：BoxLayout 下 setBorder 加在行上会
            // 与外层 FlowLayout 的间距叠加，导致行距不一致
            add(Box.createVerticalStrut(6));
        }
    }

    /**
     * 生成一行：左侧标签、中间控件、右侧说明。
     *
     * <p>标签宽度固定 130px 是为了多行之间左对齐好看。固定宽度不算优雅，
     * 但按文字实际宽度自适应会让每个参数行的控件起始位置都不一样，视觉上更乱。
     *
     * <p>说明文字同时设成 tooltip 和行内小字：行内小字便于一眼看到，
     * tooltip 兜住那些太长放不下的说明。
     */
    private JPanel row(Param p) {
        JPanel row = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 2));
        row.setOpaque(false);
        row.setAlignmentX(Component.LEFT_ALIGNMENT);

        JLabel label = new JLabel(p.label());
        label.setPreferredSize(new Dimension(130, 24));
        if (p.help() != null && !p.help().isBlank()) {
            label.setToolTipText(p.help());
        }
        row.add(label);

        JComponent field;
        switch (p.type()) {
            case BOOL -> {
                // 勾选框本身不带文字：说明已经在左边的 label 上了，
                // 再放一遍会让界面上出现两处一模一样的字
                JCheckBox cb = new JCheckBox();
                cb.setSelected(Boolean.parseBoolean(p.def()));
                field = cb;
            }
            case CHOICE -> {
                JComboBox<String> combo = new JComboBox<>(p.choices().toArray(new String[0]));
                // 默认值可能不在候选列表里（改过参数声明但没改默认值），
                // setSelectedItem 对不存在项会静默忽略，不会抛异常
                if (p.def() != null && !p.def().isBlank()) {
                    combo.setSelectedItem(p.def());
                }
                field = combo;
            }
            case PASSWORD -> field = new JPasswordField(18);
            // TEXT 与 NUMBER 共用文本框：数字的合法性在执行时由 Ctx.intVal 校验，
            // 界面不做输入限制——让用户能正常打字，错了在执行时给出明确提示
            default -> {
                JTextField tf = new JTextField(p.def() == null ? "" : p.def(), 20);
                field = tf;
            }
        }
        field.setPreferredSize(new Dimension(220, field.getPreferredSize().height));
        if (p.help() != null && !p.help().isBlank()) {
            field.setToolTipText(p.help());
        }
        fields.put(p.key(), field);
        row.add(field);

        if (p.help() != null && !p.help().isBlank()) {
            JLabel hint = new JLabel(p.help());
            hint.setFont(hint.getFont().deriveFont(Font.PLAIN, 11f));
            hint.setForeground(Ui.MUTED);
            row.add(hint);
        }
        return row;
    }

    /**
     * 读出全部参数值，key 与 {@link Param#key()} 对应。
     *
     * <p>全部统一成字符串是有意为之：{@code Ctx} 的取值方法（{@code str / intVal / bool}）
     * 都基于字符串解析，这样「默认值、界面输入、执行时读取」只有一种表示，
     * 不必为每种类型各写一遍转换与空值处理。
     *
     * <p>密码字段用 {@code new String(getPassword())} 取出明文——它必须进入
     * {@code Ctx.params} 才能用于解密。但要注意：这个字符串会一直留在内存里到任务结束，
     * 且 {@code Ctx} 可能被日志打印，所以 {@code Log} 侧对密码参数做了脱敏。
     *
     * @return 参数表，顺序与界面行顺序一致
     */
    public Map<String, String> values() {
        Map<String, String> out = new LinkedHashMap<>();
        for (var e : fields.entrySet()) {
            JComponent c = e.getValue();
            String v;
            // 按控件类型分支读值。用 instanceof 模式匹配而不是记录类型：
            // 控件类型与 Param.Type 并非一一对应（TEXT 与 NUMBER 共用 JTextField）
            if (c instanceof JCheckBox cb) {
                v = Boolean.toString(cb.isSelected());
            } else if (c instanceof JComboBox<?> combo) {
                Object sel = combo.getSelectedItem();
                v = sel == null ? "" : sel.toString();
            } else if (c instanceof JPasswordField pf) {
                v = new String(pf.getPassword());
            } else if (c instanceof JTextField tf) {
                v = tf.getText();
            } else {
                v = "";
            }
            out.put(e.getKey(), v);
        }
        return out;
    }

    /** 按 key 取参数定义，找不到返回 null。 */
    public Param def(String key) {
        return defs.get(key);
    }
}
