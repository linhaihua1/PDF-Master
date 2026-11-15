package io.pdfkit.ui;

import javax.swing.BorderFactory;
import javax.swing.UIManager;
import java.awt.Color;
import java.awt.Font;

/** 统一视觉：颜色、字体、组件默认值。 */
public final class Ui {

    public static final Color ACCENT = new Color(37, 99, 235);
    public static final Color BG = new Color(248, 249, 251);
    public static final Color CARD = Color.WHITE;
    public static final Color BORDER = new Color(226, 229, 235);
    public static final Color MUTED = new Color(107, 114, 128);
    public static final Color OK = new Color(22, 143, 88);
    public static final Color ERR = new Color(197, 48, 48);

    private Ui() {
    }

    public static void install() {
        Font base = pickFont();
        UIManager.put("defaultFont", base);
        UIManager.put("Panel.background", BG);
        UIManager.put("OptionPane.background", BG);
        UIManager.put("Table.font", base);
        UIManager.put("Table.rowHeight", 26);
        UIManager.put("Tree.font", base);
        UIManager.put("Tree.rowHeight", 24);
        UIManager.put("Button.arc", 8);
        UIManager.put("Component.arc", 8);
        UIManager.put("TextComponent.arc", 8);
        UIManager.put("ScrollBar.width", 10);
        UIManager.put("Component.focusWidth", 1);
        UIManager.put("Table.showHorizontalLines", true);
        UIManager.put("Table.showVerticalLines", false);
        UIManager.put("Table.selectionBackground", new Color(219, 234, 254));
        UIManager.put("Table.selectionForeground", Color.BLACK);
        UIManager.put("Tree.selectionBackground", new Color(219, 234, 254));
        UIManager.put("Tree.selectionForeground", Color.BLACK);
        UIManager.put("TitlePane.unifiedBackground", true);
    }

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

    public static javax.swing.border.Border card() {
        return BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(BORDER),
                BorderFactory.createEmptyBorder(10, 12, 10, 12));
    }

    public static javax.swing.border.Border section(String title) {
        return BorderFactory.createCompoundBorder(
                BorderFactory.createTitledBorder(BorderFactory.createLineBorder(BORDER), title),
                BorderFactory.createEmptyBorder(6, 8, 6, 8));
    }
}
