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

/** 按 Param 声明动态生成表单。新增能力不需要改这里。 */
public final class FormPanel extends JPanel {

    private final Map<String, JComponent> fields = new LinkedHashMap<>();
    private final Map<String, Param> defs = new LinkedHashMap<>();

    public FormPanel(List<Param> params) {
        setLayout(new BoxLayout(this, BoxLayout.Y_AXIS));
        setBorder(new EmptyBorder(4, 4, 4, 4));
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
            add(Box.createVerticalStrut(6));
        }
    }

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
                JCheckBox cb = new JCheckBox();
                cb.setSelected(Boolean.parseBoolean(p.def()));
                field = cb;
            }
            case CHOICE -> {
                JComboBox<String> combo = new JComboBox<>(p.choices().toArray(new String[0]));
                if (p.def() != null && !p.def().isBlank()) {
                    combo.setSelectedItem(p.def());
                }
                field = combo;
            }
            case PASSWORD -> field = new JPasswordField(18);
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

    public Map<String, String> values() {
        Map<String, String> out = new LinkedHashMap<>();
        for (var e : fields.entrySet()) {
            JComponent c = e.getValue();
            String v;
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

    public Param def(String key) {
        return defs.get(key);
    }
}
