package io.pdfkit.ui;

import io.pdfkit.core.Capability;
import io.pdfkit.core.Job;
import io.pdfkit.core.JobQueue;
import io.pdfkit.core.Registry;
import io.pdfkit.util.Log;
import io.pdfkit.util.Sys;

import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JFileChooser;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JSplitPane;
import javax.swing.JTable;
import javax.swing.JTextArea;
import javax.swing.JTextField;
import javax.swing.JTree;
import javax.swing.ListSelectionModel;
import javax.swing.SwingUtilities;
import javax.swing.filechooser.FileNameExtensionFilter;
import javax.swing.table.AbstractTableModel;
import javax.swing.tree.DefaultMutableTreeNode;
import javax.swing.tree.DefaultTreeCellRenderer;
import javax.swing.tree.DefaultTreeModel;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Cursor;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** 主窗口：左侧能力树，右侧参数表单，下方任务队列。 */
public final class MainFrame extends JFrame {

    private final List<Path> files = new ArrayList<>();
    private final JobQueue queue;
    private final JobTableModel jobModel = new JobTableModel();
    private final Map<String, Integer> jobRows = new HashMap<>();

    private Capability current;
    private FormPanel form;
    private final JTree tree;
    private final JPanel formHost = new JPanel(new BorderLayout());
    private final JLabel fileLabel = new JLabel();
    private final JTextField outDirField = new JTextField(Sys.defaultOutDir().toString());
    private final JTable jobTable = new JTable(jobModel);
    private final JLabel status = new JLabel("就绪");
    private final JButton runBtn = new JButton("开始执行");
    private final JTextArea resultArea = new JTextArea(6, 40);

    public MainFrame() {
        super("PdfKit · PDF 工具箱");
        setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        setMinimumSize(new Dimension(1080, 720));
        setSize(1180, 780);
        setLocationRelativeTo(null);
        setLayout(new BorderLayout());

        queue = new JobQueue(2, job -> SwingUtilities.invokeLater(() -> onJobChanged(job)));

        tree = buildTree();
        add(buildTop(), BorderLayout.NORTH);
        add(buildCenter(), BorderLayout.CENTER);
        add(buildBottom(), BorderLayout.SOUTH);

        selectDefaultCapability();
        refreshFileLabel();
    }

    // ---------- 顶部：文件与输出目录 ----------

    private JPanel buildTop() {
        JPanel wrap = new JPanel();
        wrap.setLayout(new BoxLayout(wrap, BoxLayout.Y_AXIS));
        wrap.setBorder(BorderFactory.createEmptyBorder(10, 12, 4, 12));
        wrap.setBackground(Ui.BG);

        JPanel card = new JPanel(new BorderLayout(10, 6));
        card.setBackground(Ui.CARD);
        card.setBorder(Ui.card());

        fileLabel.setFont(fileLabel.getFont().deriveFont(Font.BOLD));
        card.add(fileLabel, BorderLayout.NORTH);

        JPanel btns = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 0));
        btns.setOpaque(false);
        JButton pick = new JButton("选择文件…");
        pick.addActionListener(e -> pickFiles());
        JButton clear = new JButton("清空");
        clear.addActionListener(e -> {
            files.clear();
            refreshFileLabel();
        });
        btns.add(pick);
        btns.add(clear);
        card.add(btns, BorderLayout.WEST);

        JPanel outRow = new JPanel(new FlowLayout(FlowLayout.RIGHT, 6, 0));
        outRow.setOpaque(false);
        outRow.add(new JLabel("输出到："));
        outDirField.setColumns(28);
        outRow.add(outDirField);
        JButton pickOut = new JButton("更改…");
        pickOut.addActionListener(e -> pickOutDir());
        outRow.add(pickOut);
        card.add(outRow, BorderLayout.EAST);

        wrap.add(card);
        return wrap;
    }

    // ---------- 中部：能力树 + 表单；下部任务表 ----------

    private Component buildCenter() {
        JScrollPane treeScroll = new JScrollPane(tree);
        treeScroll.setBorder(BorderFactory.createEmptyBorder());
        treeScroll.setPreferredSize(new Dimension(268, 400));

        formHost.setBackground(Ui.CARD);
        formHost.setBorder(Ui.card());
        JScrollPane formScroll = new JScrollPane(formHost);
        formScroll.setBorder(BorderFactory.createEmptyBorder());

        JSplitPane top = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, treeScroll, formScroll);
        top.setDividerLocation(268);
        top.setBorder(null);

        JPanel jobPanel = new JPanel(new BorderLayout(0, 6));
        jobPanel.setBorder(BorderFactory.createEmptyBorder(6, 12, 6, 12));
        jobPanel.setBackground(Ui.BG);

        JPanel jobHead = new JPanel(new BorderLayout());
        jobHead.setOpaque(false);
        JLabel t = new JLabel("任务");
        t.setFont(t.getFont().deriveFont(Font.BOLD, 13f));
        jobHead.add(t, BorderLayout.WEST);

        JPanel jobBtns = new JPanel(new FlowLayout(FlowLayout.RIGHT, 6, 0));
        jobBtns.setOpaque(false);
        JButton cancel = new JButton("取消任务");
        cancel.addActionListener(e -> cancelSelectedJob());
        JButton openOut = new JButton("打开输出目录");
        openOut.addActionListener(e -> Sys.openFolder(Path.of(outDirField.getText().trim())));
        jobBtns.add(cancel);
        jobBtns.add(openOut);
        jobHead.add(jobBtns, BorderLayout.EAST);
        jobPanel.add(jobHead, BorderLayout.NORTH);

        jobTable.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        jobTable.setFillsViewportHeight(true);
        jobTable.getTableHeader().setReorderingAllowed(false);
        jobTable.setDefaultRenderer(Object.class, new JobRenderer());
        jobTable.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent e) {
                if (e.getClickCount() == 2) {
                    Job job = selectedJob();
                    if (job != null && !job.outputs().isEmpty()) {
                        Sys.openFolder(job.outputs().get(0).getParent());
                    }
                    if (job != null && job.resultText() != null) {
                        showResult(job);
                    }
                }
            }
        });
        JScrollPane jobScroll = new JScrollPane(jobTable);
        jobScroll.setBorder(Ui.section("任务队列"));
        jobScroll.setMinimumSize(new Dimension(240, 110));

        resultArea.setEditable(false);
        resultArea.setFont(new Font("Consolas", Font.PLAIN, 12));
        resultArea.setBackground(new Color(250, 250, 252));
        JScrollPane resultScroll = new JScrollPane(resultArea);
        resultScroll.setBorder(Ui.section("执行结果（分析/预览类功能显示在这里）"));
        resultScroll.setMinimumSize(new Dimension(240, 70));

        // 任务表与结果区用可拖动分隔，避免结果区按首选高度把任务表压成一条线
        JSplitPane jobSplit = new JSplitPane(JSplitPane.VERTICAL_SPLIT, jobScroll, resultScroll);
        jobSplit.setDividerLocation(150);
        jobSplit.setResizeWeight(0.55);
        jobSplit.setBorder(null);
        jobPanel.add(jobSplit, BorderLayout.CENTER);

        JSplitPane main = new JSplitPane(JSplitPane.VERTICAL_SPLIT, top, jobPanel);
        // 330 而不是 400：780 高的窗口下要同时留住任务表和结果区
        main.setDividerLocation(330);
        main.setResizeWeight(0.5);
        main.setBorder(null);
        return main;
    }

    // ---------- 底部：执行按钮 + 状态 ----------

    private JPanel buildBottom() {
        JPanel bar = new JPanel(new BorderLayout());
        bar.setBorder(BorderFactory.createEmptyBorder(6, 12, 10, 12));
        bar.setBackground(Ui.BG);

        status.setForeground(Ui.MUTED);
        bar.add(status, BorderLayout.WEST);

        JPanel right = new JPanel(new FlowLayout(FlowLayout.RIGHT, 8, 0));
        right.setOpaque(false);
        JButton logBtn = new JButton("查看日志");
        logBtn.addActionListener(e -> Sys.openFolder(Sys.logsDir()));
        runBtn.setPreferredSize(new Dimension(140, 34));
        runBtn.setFont(runBtn.getFont().deriveFont(Font.BOLD));
        runBtn.addActionListener(e -> run());
        right.add(logBtn);
        right.add(runBtn);
        bar.add(right, BorderLayout.EAST);
        return bar;
    }

    // ---------- 能力树 ----------

    private JTree buildTree() {
        DefaultMutableTreeNode root = new DefaultMutableTreeNode("全部功能");
        for (String cat : Registry.categories()) {
            DefaultMutableTreeNode c = new DefaultMutableTreeNode(new Node(cat, null));
            for (Capability cap : Registry.byCategory(cat)) {
                c.add(new DefaultMutableTreeNode(new Node(cap.title(), cap)));
            }
            root.add(c);
        }
        JTree t = new JTree(new DefaultTreeModel(root));
        t.setRootVisible(false);
        t.setShowsRootHandles(true);
        t.setCellRenderer(new Renderer());
        t.setBackground(Color.WHITE);
        for (int i = 0; i < t.getRowCount(); i++) {
            t.expandRow(i);
        }
        t.addTreeSelectionListener(e -> {
            Object sel = t.getLastSelectedPathComponent();
            if (sel instanceof DefaultMutableTreeNode n && n.getUserObject() instanceof Node node && node.cap != null) {
                selectCapability(node.cap);
            }
        });
        return t;
    }

    private record Node(String label, Capability cap) {
        @Override
        public String toString() {
            return label;
        }
    }

    private static final class Renderer extends DefaultTreeCellRenderer {
        @Override
        public Component getTreeCellRendererComponent(JTree tree, Object value, boolean sel,
                                                      boolean expanded, boolean leaf, int row, boolean hasFocus) {
            super.getTreeCellRendererComponent(tree, value, sel, expanded, leaf, row, hasFocus);
            if (value instanceof DefaultMutableTreeNode n && n.getUserObject() instanceof Node node) {
                if (node.cap == null) {
                    setFont(getFont().deriveFont(Font.BOLD));
                    setForeground(sel ? Color.BLACK : Ui.MUTED);
                }
            }
            return this;
        }
    }

    private void selectDefaultCapability() {
        for (Capability c : Registry.all()) {
            if (c.id().equals("organize.merge")) {
                selectCapability(c);
                return;
            }
        }
        if (!Registry.all().isEmpty()) {
            selectCapability(Registry.all().get(0));
        }
    }

    private void selectCapability(Capability cap) {
        current = cap;
        formHost.removeAll();

        JPanel head = new JPanel();
        head.setLayout(new BoxLayout(head, BoxLayout.Y_AXIS));
        head.setOpaque(false);
        head.setBorder(BorderFactory.createEmptyBorder(0, 4, 8, 4));

        JLabel title = new JLabel(cap.title());
        title.setFont(title.getFont().deriveFont(Font.BOLD, 16f));
        title.setAlignmentX(Component.LEFT_ALIGNMENT);

        JLabel desc = new JLabel("<html><div style='width:560px'>" + escape(cap.desc()) + "</div></html>");
        desc.setForeground(Ui.MUTED);
        desc.setAlignmentX(Component.LEFT_ALIGNMENT);

        head.add(title);
        head.add(Box.createVerticalStrut(4));
        head.add(desc);
        head.add(Box.createVerticalStrut(10));

        form = new FormPanel(cap.params());
        form.setAlignmentX(Component.LEFT_ALIGNMENT);

        JPanel body = new JPanel();
        body.setLayout(new BoxLayout(body, BoxLayout.Y_AXIS));
        body.setOpaque(false);
        body.add(head);
        body.add(form);

        formHost.add(body, BorderLayout.NORTH);
        formHost.revalidate();
        formHost.repaint();
    }

    private static String escape(String s) {
        return s == null ? "" : s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }

    // ---------- 交互 ----------

    private void pickFiles() {
        JFileChooser fc = new JFileChooser();
        fc.setMultiSelectionEnabled(true);
        fc.setDialogTitle("选择文件（可多选）");
        fc.setFileFilter(new FileNameExtensionFilter("PDF 文件 (*.pdf)", "pdf"));
        Path last = files.isEmpty() ? Path.of(System.getProperty("user.home")) : files.get(0).getParent();
        if (last != null && Files.isDirectory(last)) {
            fc.setCurrentDirectory(last.toFile());
        }
        if (fc.showOpenDialog(this) == JFileChooser.APPROVE_OPTION) {
            files.clear();
            for (var f : fc.getSelectedFiles()) {
                files.add(f.toPath());
            }
            refreshFileLabel();
        }
    }

    private void pickOutDir() {
        JFileChooser fc = new JFileChooser();
        fc.setFileSelectionMode(JFileChooser.DIRECTORIES_ONLY);
        fc.setDialogTitle("选择输出目录");
        Path cur = Path.of(outDirField.getText().trim());
        if (Files.isDirectory(cur)) {
            fc.setCurrentDirectory(cur.toFile());
        }
        if (fc.showOpenDialog(this) == JFileChooser.APPROVE_OPTION) {
            outDirField.setText(fc.getSelectedFile().getAbsolutePath());
        }
    }

    private void refreshFileLabel() {
        if (files.isEmpty()) {
            fileLabel.setText("未选择文件");
            fileLabel.setForeground(Ui.MUTED);
        } else if (files.size() == 1) {
            fileLabel.setText("已选择：" + files.get(0).getFileName());
            fileLabel.setForeground(Color.BLACK);
        } else {
            fileLabel.setText("已选择 " + files.size() + " 个文件：" + files.get(0).getFileName() + " 等");
            fileLabel.setForeground(Color.BLACK);
        }
    }

    private void run() {
        if (current == null) {
            return;
        }
        if (files.isEmpty()) {
            status.setText("请先选择文件");
            status.setForeground(Ui.ERR);
            return;
        }
        Path outDir = Path.of(outDirField.getText().trim());
        try {
            Files.createDirectories(outDir);
        } catch (Exception e) {
            status.setText("输出目录不可写：" + outDir);
            status.setForeground(Ui.ERR);
            return;
        }
        Map<String, String> params = form == null ? Map.of() : form.values();
        Job job = queue.submit(current, new ArrayList<>(files), params, outDir);
        addJobRow(job);
        status.setText("已提交：" + current.title());
        status.setForeground(Ui.MUTED);
    }

    private void addJobRow(Job job) {
        int row = jobModel.add(job);
        jobRows.put(job.id(), row);
        jobTable.setRowSelectionInterval(row, row);
    }

    private void onJobChanged(Job job) {
        Integer row = jobRows.get(job.id());
        if (row == null) {
            return;
        }
        jobModel.fireRowChanged(row);
        if (job.status() == Job.Status.SUCCEEDED) {
            status.setText("完成：" + job.title() + " → " + job.message());
            status.setForeground(Ui.OK);
            if (job.resultText() != null && !job.resultText().isBlank()) {
                resultArea.setText(job.resultText());
                resultArea.setCaretPosition(0);
            }
        } else if (job.status() == Job.Status.FAILED) {
            status.setText("失败：" + job.title() + " → " + job.message());
            status.setForeground(Ui.ERR);
        } else if (job.status() == Job.Status.RUNNING) {
            status.setText(job.title() + " " + job.progress() + "% · " + job.message());
            status.setForeground(Ui.MUTED);
        }
    }

    private Job selectedJob() {
        int row = jobTable.getSelectedRow();
        return row >= 0 && row < jobModel.jobs.size() ? jobModel.jobs.get(row) : null;
    }

    private void cancelSelectedJob() {
        Job job = selectedJob();
        if (job == null || job.isTerminal()) {
            status.setText("请选中一个正在执行的任务");
            return;
        }
        job.cancelFlag().set(true);
        status.setText("已请求取消：" + job.title());
    }

    private void showResult(Job job) {
        if (job.resultText() != null) {
            resultArea.setText(job.resultText());
            resultArea.setCaretPosition(0);
        }
    }

    @Override
    public void dispose() {
        queue.shutdown();
        super.dispose();
    }

    // ---------- 任务表 ----------

    private static final class JobTableModel extends AbstractTableModel {

        final List<Job> jobs = new ArrayList<>();
        private final String[] cols = {"功能", "状态", "进度", "消息", "开始时间"};

        int add(Job j) {
            jobs.add(0, j);
            fireTableDataChanged();
            return 0;
        }

        void fireRowChanged(int row) {
            fireTableRowsUpdated(row, row);
        }

        @Override
        public int getRowCount() {
            return jobs.size();
        }

        @Override
        public int getColumnCount() {
            return cols.length;
        }

        @Override
        public String getColumnName(int c) {
            return cols[c];
        }

        @Override
        public Object getValueAt(int r, int c) {
            Job j = jobs.get(r);
            return switch (c) {
                case 0 -> j.title();
                case 1 -> j.status().label();
                case 2 -> j.status() == Job.Status.RUNNING ? j.progress() + "%" : (j.isTerminal() ? "" : "—");
                case 3 -> (j.errorCode() != null ? "[" + j.errorCode() + "] " : "") + j.message();
                case 4 -> j.startedAt() == null ? "" : j.startedAt().toLocalTime().withNano(0).toString();
                default -> "";
            };
        }
    }

    private static final class JobRenderer extends javax.swing.table.DefaultTableCellRenderer {
        @Override
        public Component getTableCellRendererComponent(JTable table, Object value, boolean isSelected,
                                                       boolean hasFocus, int row, int column) {
            Component c = super.getTableCellRendererComponent(table, value, isSelected, hasFocus, row, column);
            if (!isSelected) {
                c.setForeground(Color.BLACK);
                if (column == 1) {
                    String s = String.valueOf(value);
                    if ("失败".equals(s)) {
                        c.setForeground(Ui.ERR);
                    } else if ("已完成".equals(s)) {
                        c.setForeground(Ui.OK);
                    } else {
                        c.setForeground(Ui.MUTED);
                    }
                }
            }
            return c;
        }
    }
}
