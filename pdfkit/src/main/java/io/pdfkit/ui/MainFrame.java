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

/**
 * 主窗口：左侧能力树，右侧参数表单，下方任务队列。
 *
 * <p>布局自上而下分四块：
 * <pre>
 * ┌─────────────────────────────────────────┐
 * │ 顶部：文件选择、输出目录、执行按钮        │  NORTH
 * ├──────────┬──────────────────────────────┤
 * │ 能力树   │ 当前能力的说明 + 参数表单      │  CENTER（左右 JSplitPane）
 * ├──────────┴──────────────────────────────┤
 * │ 任务队列表 + 执行结果区                  │  CENTER 内的上下 JSplitPane
 * ├─────────────────────────────────────────┤
 * │ 状态栏                                   │  SOUTH
 * └─────────────────────────────────────────┘
 * </pre>
 *
 * <p><b>界面里没有任何针对具体能力的代码</b>：左侧树由 {@code Registry} 生成，
 * 右侧表单由 {@code FormPanel} 按参数定义生成。加一项能力不需要动本类。
 *
 * <p><b>EDT 约束（最容易改坏的地方）</b>：{@link io.pdfkit.core.JobQueue} 的状态回调
 * 来自<b>后台工作线程</b>，而本类所有界面更新方法都直接操作 Swing 组件。
 * 所以构造队列时用 {@code SwingUtilities.invokeLater} 把回调包了一层，
 * 保证 {@link #onJobChanged} 一定在 EDT 上执行。
 * <b>去掉这层包装会导致偶发的界面错乱</b>，且很难复现、很难定位。
 *
 * <p><b>分区高度为什么用 JSplitPane 而不是固定高度</b>：任务表与结果区的内容长度
 * 变化很大（一次合并可能产出几十行，一次分析可能输出几百行文字），
 * 固定高度总有一边不够用。给用户可拖动的分隔条最省心。
 * 但要注意设 {@code setMinimumSize}——不设的话拖到极限会把某一块压成 0 高，
 * 表现为「表头被压成一条线」（这是 S2 阶段实测抓出的缺陷，见 {@code UiTest} 的回归断言）。
 */
public final class MainFrame extends JFrame {

    private final List<Path> files = new ArrayList<>();
    private final JobQueue queue;
    private final JobTableModel jobModel = new JobTableModel();

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

        // 关键：回调来自后台工作线程，必须用 invokeLater 切回 EDT 才能碰 Swing 组件。
        // 这层包装不能省——见类注释里的 EDT 约束。
        queue = new JobQueue(2, job -> SwingUtilities.invokeLater(() -> onJobChanged(job)));

        tree = buildTree();
        add(buildTop(), BorderLayout.NORTH);
        add(buildCenter(), BorderLayout.CENTER);
        add(buildBottom(), BorderLayout.SOUTH);

        selectDefaultCapability();
        refreshFileLabel();
    }

    // ---------- 顶部：文件与输出目录 ----------

    /**
     * 顶部卡片：已选文件提示 + 选择/清空按钮 + 输出目录。
     *
     * <p>外层用 {@code BoxLayout(Y_AXIS)}、内层卡片用 {@code BorderLayout}，是因为
     * 顶部区域以后可能要往下叠第二张卡片（如「最近使用」），竖排容器更好扩展；
     * 而卡片内部只有三块（文件标签、按钮、输出目录），BorderLayout 直接够用。
     *
     * <p>注意 {@code fileLabel} 是字段而不是局部变量——{@link #refreshFileLabel()} 需要
     * 随时改它的文字与颜色，所以必须在构造时创建、复用同一个实例。
     */
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

    /**
     * 中部：能力树 + 参数表单（上），任务队列 + 执行结果（下）。
     *
     * <p><b>三层 JSplitPane 嵌套</b>：最外层纵向切「表单区 / 任务区」，上半部分再横向切
     * 「树 / 表单」，下半部分再纵向切「任务表 / 结果区」。三道分隔条都可拖动，
     * 因为不同用户对这两块的高度偏好差别很大（有人常看结果，有人只看任务列表）。
     *
     * <p><b>每个滚动面板都要 setMinimumSize</b>。JSplitPane 在窗口缩小时按比例压缩子组件，
     * 但 JTable / JTextArea 的「最小尺寸」默认会跟着内容算，结果任务表被压成一条细线
     * 甚至完全消失。显式给最小尺寸后，窗口再小也能看到至少两行任务。
     * 这是 S2 界面测试里报过的缺陷（D-03），不要删掉这两行。
     *
     * <p>双击任务行的行为是「打开产物所在文件夹 + 若有无文本结果则显示在结果区」。
     * 打开的是 {@code outputs().get(0).getParent()}，即<b>第一个产物的父目录</b>——
     * 所以各能力登记产物时必须登记文件、不能登记目录，否则这里会打开上一级目录。
     */
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

    /**
     * 底部状态栏：左侧状态文字 + 右侧「查看日志」「执行」按钮。
     *
     * <p>日志按钮直接打开日志目录而不是弹窗显示内容：日志行数多、需要跨天比对，
     * 让用户用自己熟悉的编辑器打开更实用。
     */
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

    /**
     * 构建能力树：分类为父节点，能力为叶子。
     *
     * <p><b>{@link Node#toString()} 决定节点显示的文字</b>——JTree 默认拿
     * {@code getUserObject().toString()} 渲染，所以名称放在 toString 里最省事，
     * 不用为树单独建一套 model。
     *
     * <p>启动时全部展开：一共 8 个分类、60 项，全展开刚好一屏能扫完；折叠起来
     * 反而让用户多点几次。
     */
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

    /**
     * 树的节点数据。
     *
     * <p>{@code cap == null} 表示这是分类节点（不可点选执行），非空表示是具体能力。
     * 用同一个 record 表示两种节点，是为了让「分类」与「能力」共用一套渲染与遍历逻辑。
     */
    private record Node(String label, Capability cap) {
        @Override
        public String toString() {
            return label;
        }
    }

    /** 树节点渲染：分类节点加粗并降低饱和度，与可执行的能力节点区分开。 */
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

    /**
     * 启动时默认选中「合并 PDF」。
     *
     * <p>选它而不是列表里的第一项，是因为「把几个 PDF 拼成一个」是使用频率最高的功能，
     * 打开就能直接操作。找不到时（例如该能力被改名）退回第一项，保证界面不会空白无表单。
     */
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

    /**
     * 切换到某项能力：重建参数表单。
     *
     * <p><b>每次切换都新建 {@link FormPanel}，不复用旧实例</b>。不同能力的参数项数量、
     * 类型、默认值完全不同，复用需要清空再重建，反而更容易残留上一次的输入。
     * 代价是切换时有一瞬间的重绘，用户感知不到。
     *
     * <p><b>描述文字必须裹一层 HTML</b>：{@code JLabel} 默认不换行，长描述会把表单
     * 撑到几百像素宽、把横向滚动条逼出来。写成 {@code <html><div style='width:560px'>}
     * 才能按指定宽度自动折行。因为用了 HTML，描述里的 {@code <} 与 {@code &}
     * 必须经 {@link #escape} 转义，否则会被当成标签。
     *
     * <p>{@code revalidate() + repaint()} 两个都要调：前者让布局管理器重新计算尺寸，
     * 后者触发重绘。只调一个会出现「表单挤在左上角」或「内容变了但画面没变」。
     */
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

    /**
     * 选择输入文件（多选）。
     *
     * <p><b>是「替换」不是「追加」</b>：选完后先 {@code clear()} 再加入，
     * 上一次的选择会被丢弃。这样简单直接，但用户想分两批选时会被意外清空——
     * 若将来改成追加语义，记得同时处理重复文件的去重。
     *
     * <p>记住上次所在的目录（{@code files.get(0).getParent()}）：批量处理时
     * 用户通常反复从同一个文件夹取文件，每次都回到主目录很折磨人。
     */
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

    /**
     * 取当前表单值并提交任务。
     *
     * <p>提交前做两项本地校验（没选能力、没选文件、输出目录不可写），
     * 目的是让最常见的错误在界面上即时反馈，而不是等任务跑起来再失败——
     * 后者要走一轮「排队 → 执行 → 失败」的流程，用户等了几秒才被告知「请先选择文件」。
     *
     * <p>参数校验（数字格式、取值范围）不在这里做，交给 {@code Ctx.intVal}：
     * 校验规则属于能力自身，界面不该知道「页码最大是多少」这类业务细节。
     *
     * <p>提交后立即返回，界面不阻塞。后续状态由 {@link #onJobChanged} 异步刷新。
     */
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
        jobTable.setRowSelectionInterval(row, row);
    }

    /**
     * 按任务 id 查当前所在行号，找不到返回 null。
     *
     * <p><b>为什么不用「id → 行号」的 HashMap 缓存</b>：新任务总是插到第 0 行
     * （最新的在最上面），每插入一条，已存在的所有行号都要加一。缓存若不同步整体平移，
     * 状态刷新就会落到错误的行上——表现为「A 任务完成，B 那一行变成完成」。
     * 任务表最多几十行，线性查找的开销可以忽略，比维护一份易失步的缓存可靠。
     */
    private Integer rowOf(String jobId) {
        for (int i = 0; i < jobModel.jobs.size(); i++) {
            if (jobModel.jobs.get(i).id().equals(jobId)) {
                return i;
            }
        }
        return null;
    }

    private void onJobChanged(Job job) {
        Integer row = rowOf(job.id());
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

    /**
     * 取消当前选中任务的请求。
     *
     * <p>这里只是把 {@code cancelFlag} 置位，<b>并不强制中断线程</b>。原因见
     * {@code JobQueue} 的说明：PDFBox 的 IO 与渲染不响应线程中断，强行 interrupt
     * 可能留下写了一半的文件。所以能力实现内部要在循环里主动 {@code checkCancel()}。
     *
     * <p>文案用「已请求取消」而不是「已取消」，是因为真正的停止要等能力走到下一个
     * 检查点——大文件的渲染可能要几秒。用「已取消」会让用户以为立刻生效。
     */
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

    /**
     * 关闭窗口时先停线程池，再释放窗口。
     *
     * <p>{@code queue.shutdown()} 必须调：工作线程是 daemon 线程，不主动停的话
     * JVM 退出时可能正在写文件，留下截断的 PDF。放在 {@code super.dispose()} 之前，
     * 保证先拒绝新任务、再销毁界面。
     */
    @Override
    public void dispose() {
        queue.shutdown();
        super.dispose();
    }

    // ---------- 任务表 ----------

    /**
     * 任务表模型。数据源是内存里的 {@code List<Job>}，不做持久化——关掉程序任务列表就没了，
     * 这是有意的：产物已经在磁盘上，任务列表只是个临时的执行记录。
     */
    private static final class JobTableModel extends AbstractTableModel {

        final List<Job> jobs = new ArrayList<>();
        private final String[] cols = {"功能", "状态", "进度", "消息", "开始时间"};

        /**
         * 插入新任务，返回它所在的行号（恒为 0）。
         *
         * <p>插到第 0 行让最新的任务显示在最上面。<b>注意这会让所有已有任务的行号加一</b>，
         * 所以外部不能缓存行号——状态更新时请用 {@code rowOf(id)} 重新查，理由见该方法。
         */
        int add(Job j) {
            jobs.add(0, j);
            fireTableDataChanged();
            return 0;
        }

        /** 只刷新一行，比 {@code fireTableDataChanged()} 轻——后者会让整表重绘、丢失选择。 */
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
