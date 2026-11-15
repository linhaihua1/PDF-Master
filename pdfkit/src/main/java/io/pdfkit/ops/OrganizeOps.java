package io.pdfkit.ops;

import io.pdfkit.core.Capability;
import io.pdfkit.core.Ctx;
import io.pdfkit.core.Err;
import io.pdfkit.core.Param;
import io.pdfkit.util.PdfUtil;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.util.Matrix;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/** 页面组织类能力：合并、拆分、旋转、裁剪、删除、重排、提取。 */
/**
 * 页面组织类能力（9 项）：合并、拆分、旋转、裁剪、删除页、提取页、重排、缩放、按章节拆。
 *
 * <p>这是能力的核心类，也是全项目唯一提供「抽页工具」的地方——
 * {@link #writeSubset} 被 {@link FilterOps} 等类复用，不要在别处另写一份。
 *
 * <p><b>两条贯穿全类的技术要点</b>：
 * <ol>
 *   <li><b>抽页用 {@code importPage}</b>：它把源页的内容流、资源、注释深拷贝进目标文档。
 *       代价是页面被真正复制（多页共享同一源页时体积会翻倍），好处是目标文档自包含、
 *       不依赖源文件。拼版类功能（{@link LayoutOps}）用 Form XObject 引用，
 *       是另一种取舍。</li>
 *   <li><b>页码一律 1 基</b>：用户和界面看到的都是 1 基，只在调用 PDFBox 的
 *       {@code getPage(i)} 前用 {@link PdfUtil#toIndices} 转成 0 基。不要在方法内部
 *       随手减一，那会变成「减了两次」。</li>
 * </ol>
 *
 * <p><b>书签不会跟着 {@code importPage} 走</b>：PDFBox 只复制页面本身，文档级的
 * 书签（Outline）、表单（AcroForm）、命名目标都不会自动迁移。所以合并功能里
 * 手动为每个源文件补了一级书签；其它抽页类能力则确实会丢掉原书签——
 * 这是已知限制，不是 bug。
 */
public final class OrganizeOps {

    private OrganizeOps() {
    }

    // ---------- 合并 ----------

    public static final Capability MERGE = new Capability(
            "organize.merge", "合并 PDF", "页面组织",
            "把多个 PDF 按选择顺序合并成一个文件，页序与源文件一致。",
            List.of(Param.bool("keepBookmark", "保留书签", true, "为每个源文件生成一级书签")),
            ctx -> {
                List<Path> inputs = ctx.inputs();
                // 两级判断分开：空输入报 ERR_NO_INPUT（提示「请选文件」），
                // 只选了一个才报 ERR_NEED_TWO（提示「至少要两个」）。
                // 合成一条判断的话，用户没选文件时也会收到「至少需要 2 个」，令人困惑。
                if (inputs.isEmpty()) {
                    throw new Err("ERR_NO_INPUT", "请先选择要合并的 PDF 文件");
                }
                if (inputs.size() < 2) {
                    throw new Err("ERR_NEED_TWO", "合并至少需要 2 个 PDF 文件，当前只选了 1 个");
                }
                Path out = ctx.outDerived("_合并", ".pdf");
                boolean keepBookmark = ctx.bool("keepBookmark", true);
                try (PDDocument dst = new PDDocument()) {
                    List<String> names = new ArrayList<>();
                    int pageOffset = 0;
                    org.apache.pdfbox.pdmodel.interactive.documentnavigation.outline.PDDocumentOutline outline = null;
                    if (keepBookmark) {
                        outline = new org.apache.pdfbox.pdmodel.interactive.documentnavigation.outline.PDDocumentOutline();
                        dst.getDocumentCatalog().setDocumentOutline(outline);
                    }
                    for (int i = 0; i < inputs.size(); i++) {
                        ctx.report(i * 90 / inputs.size(), "正在合并：" + inputs.get(i).getFileName());
                        try (PDDocument src = PdfUtil.load(inputs.get(i), ctx.str("password"))) {
                            for (PDPage page : src.getPages()) {
                                dst.importPage(page);
                            }
                            names.add(inputs.get(i).getFileName().toString());
                            if (keepBookmark && dst.getNumberOfPages() > pageOffset) {
                                var item = new org.apache.pdfbox.pdmodel.interactive.documentnavigation.outline.PDOutlineItem();
                                item.setTitle(PdfUtil.baseName(inputs.get(i)));
                                var dest = new org.apache.pdfbox.pdmodel.interactive.documentnavigation.destination.PDPageFitDestination();
                                dest.setPage(dst.getPage(pageOffset));
                                item.setDestination(dest);
                                outline.addLast(item);
                            }
                            pageOffset = dst.getNumberOfPages();
                        }
                    }
                    ctx.report(95, "写入文件");
                    PdfUtil.save(dst, out);
                    ctx.addOutput(out);
                    ctx.report(100, "合并完成，共 " + dst.getNumberOfPages() + " 页");
                }
            });

    // ---------- 拆分 ----------

    public static final Capability SPLIT = new Capability(
            "organize.split", "拆分 PDF", "页面组织",
            "按每 N 页拆成多个文件，或按指定页码范围拆成单个文件。",
            List.of(
                    Param.choice("mode", "拆分方式", "按页数", "按页数", "按页码范围"),
                    Param.num("every", "每几页一个文件", "10", "按页数模式生效"),
                    Param.text("range", "页码范围", "", "按范围模式生效，如 1-3,4-8,9-")
            ),
            ctx -> {
                try (PDDocument src = PdfUtil.load(ctx.input(), ctx.str("password"))) {
                    int total = src.getNumberOfPages();
                    ctx.report(10, "共 " + total + " 页");
                    String mode = ctx.str("mode", "按页数");
                    List<int[]> chunks = new ArrayList<>();
                    List<String> labels = new ArrayList<>();
                    if ("按页码范围".equals(mode)) {
                        String range = ctx.str("range");
                        if (range.isBlank()) {
                            throw new Err("ERR_BAD_PARAM", "请填写页码范围，如 1-3,4-8");
                        }
                        for (String part : range.split("[,，;；]+")) {
                            if (part.isBlank()) {
                                continue;
                            }
                            List<Integer> pages = PdfUtil.parsePageSpec(part, total);
                            chunks.add(PdfUtil.toIndices(pages));
                            labels.add("p" + pages.get(0) + "-" + pages.get(pages.size() - 1));
                        }
                    } else {
                        int every = ctx.intVal("every", 10, 1, 10000);
                        for (int start = 1; start <= total; start += every) {
                            int end = Math.min(start + every - 1, total);
                            List<Integer> pages = new ArrayList<>();
                            for (int p = start; p <= end; p++) {
                                pages.add(p);
                            }
                            chunks.add(PdfUtil.toIndices(pages));
                            labels.add("p" + start + "-" + end);
                        }
                    }
                    String base = PdfUtil.baseName(ctx.input());
                    for (int i = 0; i < chunks.size(); i++) {
                        ctx.report(15 + i * 80 / chunks.size(), "生成第 " + (i + 1) + "/" + chunks.size() + " 个文件");
                        Path out = ctx.out(base + "_" + labels.get(i) + ".pdf");
                        writeSubset(src, chunks.get(i), out);
                        ctx.addOutput(out);
                    }
                    ctx.report(100, "拆分完成，共 " + chunks.size() + " 个文件");
                }
            });

    // ---------- 旋转 ----------

    /**
     * 旋转页面。
     *
     * <p><b>只改页面字典的 {@code /Rotate} 属性，不旋转内容流</b>。这是无损且可逆的做法：
     * 文件体积几乎不变、文字仍可搜索、矢量图形不糊，而且再用别的工具转回来就能恢复。
     * 代价是「页面物理尺寸的宽高」没有真正交换——某些工具（尤其打印驱动）会忽略
     * {@code /Rotate}，仍按原始方向输出。
     *
     * <p><b>角度是累加而非覆盖</b>：{@code (page.getRotation() + angle) % 360}。
     * 已经转过 90° 的页面再转 90° 会变成 180°，而不是又回到 90°。这符合「再转一次」
     * 的直觉，但对误以为「设成 90 就是 90」的用户会显得奇怪。
     */
    public static final Capability ROTATE = new Capability(
            "organize.rotate", "旋转页面", "页面组织",
            "顺时针旋转全部或指定页面的显示方向。",
            List.of(
                    Param.choice("angle", "旋转角度", "90", "90", "180", "270"),
                    Param.text("pages", "作用页", "", "留空=全部页，或 1-3,5")
            ),
            ctx -> {
                int angle = ctx.intVal("angle", 90);
                try (PDDocument doc = PdfUtil.load(ctx.input(), ctx.str("password"))) {
                    List<Integer> pages = PdfUtil.parsePageSpec(ctx.str("pages"), doc.getNumberOfPages());
                    int i = 0;
                    for (int p : pages) {
                        ctx.report(10 + i++ * 80 / pages.size(), "旋转第 " + p + " 页");
                        PDPage page = doc.getPage(p - 1);
                        // 累加：见本能力 javadoc。% 360 防止多次旋转后数值无限增大
                        page.setRotation((page.getRotation() + angle) % 360);
                    }
                    Path out = ctx.outDerived("_旋转" + angle, ".pdf");
                    PdfUtil.save(doc, out);
                    ctx.addOutput(out);
                    ctx.report(100, "已旋转 " + pages.size() + " 页");
                }
            });

    // ---------- 裁剪 ----------

    /**
     * 裁剪页面：只改可见窗口（CropBox）。
     *
     * <p><b>裁掉的内容仍然在文件里</b>。{@code setCropBox} 只是告诉阅读器「只看这一块」，
     * 页面内容流、字体、图片一个字节都没删。用文本提取工具或把 CropBox 改回去，
     * 被裁掉的文字就能拿到。<b>所以本项绝不能用来「隐藏敏感信息」</b>——那要用涂黑。
     * 如果目的是减小体积或去掉扫描黑边，用「优化」里的裁边功能更合适。
     *
     * <p><b>负数边距是向外扩</b>：{@code w = 原宽 - left - right}，left 为负时宽度反而增大，
     * 相当于把可见区域扩展到页面原始边界之外（页面外会显示为空白）。参数说明里已注明。
     *
     * <p>{@code w <= 10 || h <= 10} 是安全下限：边距填得离谱时（如 500mm）会算出负数尺寸，
     * 直接写成非法 CropBox 会让文件打不开。10pt 约等于 3.5mm。
     */
    public static final Capability CROP = new Capability(
            "organize.crop", "裁剪页面", "页面组织",
            "按毫米为单位裁掉四周留白，或统一裁成指定尺寸。",
            List.of(
                    Param.num("top", "上边距(mm)", "0", "正数向内裁，负数向外扩"),
                    Param.num("bottom", "下边距(mm)", "0", ""),
                    Param.num("left", "左边距(mm)", "0", ""),
                    Param.num("right", "右边距(mm)", "0", ""),
                    Param.text("pages", "作用页", "", "留空=全部页")
            ),
            ctx -> {
                ctx.input(); // 先确认已选文件，再校验参数，避免用户没选文件却看到参数报错
                float mm = 72f / 25.4f;
                float top = ctx.intVal("top", 0) * mm;
                float bottom = ctx.intVal("bottom", 0) * mm;
                float left = ctx.intVal("left", 0) * mm;
                float right = ctx.intVal("right", 0) * mm;
                if (top == 0 && bottom == 0 && left == 0 && right == 0) {
                    throw new Err("ERR_BAD_PARAM", "四个边距都为 0，没有可裁剪的内容");
                }
                try (PDDocument doc = PdfUtil.load(ctx.input(), ctx.str("password"))) {
                    List<Integer> pages = PdfUtil.parsePageSpec(ctx.str("pages"), doc.getNumberOfPages());
                    int i = 0;
                    for (int p : pages) {
                        ctx.report(10 + i++ * 80 / pages.size(), "裁剪第 " + p + " 页");
                        PDPage page = doc.getPage(p - 1);
                        PDRectangle box = page.getCropBox();
                        float w = box.getWidth() - left - right;
                        float h = box.getHeight() - top - bottom;
                        if (w <= 10 || h <= 10) {
                            // 注意 Err.of 走 String.format，文案里的 % 必须写成 %%；
                            // 这里的 %.0f 是给 w、h 用的转换符，不要误加百分号
                            throw Err.of("ERR_CROP_TOO_MUCH", "第 %d 页裁剪后仅剩 %.0f×%.0f pt，边距过大", p, w, h);
                        }
                        // 新 CropBox 的左下角要加上左/下边距，尺寸取算出的 w/h
                        page.setCropBox(new PDRectangle(box.getLowerLeftX() + left, box.getLowerLeftY() + bottom, w, h));
                    }
                    Path out = ctx.outDerived("_裁剪", ".pdf");
                    PdfUtil.save(doc, out);
                    ctx.addOutput(out);
                    ctx.report(100, "已裁剪 " + pages.size() + " 页");
                }
            });

    // ---------- 删除 / 提取 / 重排 ----------

    /**
     * 删除页面：算出「要保留的页」再重建文件。
     *
     * <p>本项的实现是反过来的——不是逐页删，而是先算保留集合，再用
     * {@link #writeSubset} 重新生成。{@code PDDocument} 没有可靠的「删除单页」API
     * （直接改 pages 树会留下悬空引用），重建是唯一稳的做法。
     *
     * <p><b>不能删光</b>：{@code del.size() >= total} 就报错。零页的 PDF 无法打开，
     * 与其生成一个坏文件，不如直接拒绝。
     *
     * <p>已知的性能问题：内层用 {@code del.contains(p)} 判断，List 的线性查找让整体
     * 复杂度到 O(n²)。几百页时感知不到；若将来要处理上万页，把 {@code del} 换成
     * {@code HashSet} 即可。
     */
    public static final Capability DELETE_PAGES = new Capability(
            "organize.delete-pages", "删除页面", "页面组织",
            "删除指定页面，其余页面保持原顺序。",
            List.of(Param.text("pages", "要删除的页", "", "必填，如 2,5-7")),
            ctx -> {
                ctx.input(); // 先确认已选文件
                String spec = ctx.str("pages");
                if (spec.isBlank()) {
                    throw new Err("ERR_BAD_PARAM", "请填写要删除的页码");
                }
                try (PDDocument src = PdfUtil.load(ctx.input(), ctx.str("password"))) {
                    int total = src.getNumberOfPages();
                    List<Integer> del = PdfUtil.parsePageSpec(spec, total);
                    if (del.size() >= total) {
                        throw new Err("ERR_DELETE_ALL", "不能删除全部页面（共 " + total + " 页，要删 " + del.size() + " 页）");
                    }
                    List<Integer> keep = new ArrayList<>();
                    for (int p = 1; p <= total; p++) {
                        // 页号是 1 基，del 里存的也是 1 基，直接比较即可
                        if (!del.contains(p)) {
                            keep.add(p);
                        }
                    }
                    Path out = ctx.outDerived("_已删页", ".pdf");
                    ctx.report(30, "保留 " + keep.size() + " 页");
                    writeSubset(src, PdfUtil.toIndices(keep), out);
                    ctx.addOutput(out);
                    ctx.report(100, "已删除 " + del.size() + " 页，剩余 " + keep.size() + " 页");
                }
            });

    /**
     * 提取页面：只保留指定页，源文件不动。
     *
     * <p>与「删除页面」的关系：功能互补（一个说保留哪些、一个说删掉哪些），实现都落在
     * {@link #writeSubset}。区别是「提取」可以提取全部页（等于复制一份），不必像删除那样
     * 拦住「全删」的情况。
     */
    public static final Capability EXTRACT_PAGES = new Capability(
            "organize.extract", "提取页面", "页面组织",
            "只保留指定页面，生成新文件，源文件不变。",
            List.of(Param.text("pages", "要保留的页", "", "必填，如 1-3,8")),
            ctx -> {
                try (PDDocument src = PdfUtil.load(ctx.input(), ctx.str("password"))) {
                    List<Integer> keep = PdfUtil.parsePageSpec(ctx.str("pages"), src.getNumberOfPages());
                    Path out = ctx.outDerived("_提取", ".pdf");
                    ctx.report(30, "提取 " + keep.size() + " 页");
                    writeSubset(src, PdfUtil.toIndices(keep), out);
                    ctx.addOutput(out);
                    ctx.report(100, "完成");
                }
            });

    public static final Capability REORDER = new Capability(
            "organize.reorder", "重排页面", "页面组织",
            "按输入顺序重排页面，可重复某页实现「复制页」。",
            List.of(Param.text("order", "新页序", "", "必填，如 3,1,2,5-8")),
            ctx -> {
                try (PDDocument src = PdfUtil.load(ctx.input(), ctx.str("password"))) {
                    List<Integer> order = PdfUtil.parsePageSpec(ctx.str("order"), src.getNumberOfPages());
                    Path out = ctx.outDerived("_重排", ".pdf");
                    ctx.report(30, "按新顺序生成 " + order.size() + " 页");
                    writeSubset(src, PdfUtil.toIndices(order), out);
                    ctx.addOutput(out);
                    ctx.report(100, "完成");
                }
            });

    // ---------- 缩放（用变换矩阵改页面尺寸） ----------

    public static final Capability SCALE = new Capability(
            "organize.scale", "缩放页面", "页面组织",
            "按百分比缩放页面内容，可选同时调整纸张尺寸。",
            List.of(
                    Param.num("percent", "缩放比例(%)", "100", "50-200"),
                    Param.text("pages", "作用页", "", "留空=全部页")
            ),
            ctx -> {
                int pct = ctx.intVal("percent", 100, 50, 200);
                try (PDDocument doc = PdfUtil.load(ctx.input(), ctx.str("password"))) {
                    List<Integer> pages = PdfUtil.parsePageSpec(ctx.str("pages"), doc.getNumberOfPages());
                    float factor = pct / 100f;
                    int i = 0;
                    for (int p : pages) {
                        ctx.report(10 + i++ * 80 / pages.size(), "缩放第 " + p + " 页");
                        PDPage page = doc.getPage(p - 1);
                        PDRectangle box = page.getMediaBox();
                        if (pct != 100) {
                            try (PDPageContentStream cs = new PDPageContentStream(doc, page,
                                    PDPageContentStream.AppendMode.PREPEND, true, true)) {
                                cs.transform(new Matrix(factor, 0, 0, factor, 0, 0));
                            }
                            page.setMediaBox(new PDRectangle(box.getWidth() * factor, box.getHeight() * factor));
                        }
                    }
                    Path out = ctx.outDerived("_缩放" + pct, ".pdf");
                    PdfUtil.save(doc, out);
                    ctx.addOutput(out);
                    ctx.report(100, "完成");
                }
            });

    // ---------- 按章节拆分 ----------

    public static final Capability SPLIT_BY_BOOKMARK = new Capability(
            "organize.split-by-bookmark", "按章节拆分", "页面组织",
            "按书签把文档拆成多个文件，每个一级（或指定级）章节一个文件。",
            List.of(
                    Param.num("level", "按第几级书签", "1", "1-3"),
                    Param.bool("keepTitle", "文件名带章节标题", true, "")
            ),
            ctx -> {
                int level = ctx.intVal("level", 1, 1, 3);
                try (PDDocument src = PdfUtil.load(ctx.input(), ctx.str("password"))) {
                    var outline = src.getDocumentCatalog().getDocumentOutline();
                    if (outline == null || outline.getFirstChild() == null) {
                        throw new Err("ERR_NO_BOOKMARK", "这个 PDF 没有书签，无法按章节拆分。可先用「自动生成书签」。");
                    }
                    int total = src.getNumberOfPages();
                    List<int[]> ranges = new ArrayList<>();
                    List<String> titles = new ArrayList<>();
                    collectSections(outline.getFirstChild(), 1, level, src, total, ranges, titles);
                    if (ranges.isEmpty()) {
                        throw new Err("ERR_NO_BOOKMARK", "第 " + level + " 级书签下没有可拆分的章节");
                    }
                    String base = PdfUtil.baseName(ctx.input());
                    for (int i = 0; i < ranges.size(); i++) {
                        ctx.report(10 + i * 85 / ranges.size(), "生成第 " + (i + 1) + "/" + ranges.size() + " 个章节");
                        int[] r = ranges.get(i);
                        List<Integer> pages = new ArrayList<>();
                        for (int p = r[0]; p <= r[1]; p++) {
                            pages.add(p);
                        }
                        String suffix = ctx.bool("keepTitle", true)
                                ? "_" + String.format("%02d", i + 1) + "_" + safeName(titles.get(i))
                                : "_部分" + (i + 1);
                        Path out = ctx.out(base + suffix + ".pdf");
                        writeSubset(src, PdfUtil.toIndices(pages), out);
                        ctx.addOutput(out);
                    }
                    ctx.report(100, "按章节拆出 " + ranges.size() + " 个文件");
                }
            });

    /**
     * 按书签层级切分：把目标层级的每个书签当作一节，从它的起始页切到下一个同级书签的前一页。
     *
     * <p><b>节的结束页由「下一个兄弟节点头」推出</b>：{@code endPage = 下一节起始页 - 1}。
     * 最后一个节点没有下一个兄弟，直接取文档总页数。这是 PDF 大纲结构决定的——
     * 书签只记录「从哪页开始」，不记录「到哪页结束」。
     *
     * <p>{@code currentLevel != targetLevel} 时往下钻而不是继续平级遍历：目标是第 N 层，
     * 就必须一路递归到第 N 层才收集。{@code targetLevel} 由用户参数决定（通常 1，即按章拆）。
     *
     * <p>{@code endPage < startPage} 的兜底：书签页码乱序（多个书签指向同一页、
     * 或后一个书签反而更靠前）时会产生负区间，退化成「单页一节」。
     */
    private static void collectSections(org.apache.pdfbox.pdmodel.interactive.documentnavigation.outline.PDOutlineItem first,
                                        int currentLevel, int targetLevel, PDDocument doc, int total,
                                        List<int[]> ranges, List<String> titles) {
        if (first == null) {
            return;
        }
        var cur = first;
        while (cur != null) {
            if (currentLevel == targetLevel) {
                int startPage = pageOfItem(cur, doc);
                var next = cur.getNextSibling();
                int endPage = next == null ? total : pageOfItem(next, doc) - 1;
                if (endPage < startPage) {
                    endPage = startPage;
                }
                ranges.add(new int[]{Math.max(1, startPage), Math.min(total, endPage)});
                titles.add(cur.getTitle() == null ? "未命名" : cur.getTitle());
            } else {
                collectSections(cur.getFirstChild(), currentLevel + 1, targetLevel, doc, total, ranges, titles);
            }
            cur = cur.getNextSibling();
        }
    }

    private static int pageOfItem(org.apache.pdfbox.pdmodel.interactive.documentnavigation.outline.PDOutlineItem item,
                                  PDDocument doc) {
        try {
            var dest = item.getDestination();
            if (dest instanceof org.apache.pdfbox.pdmodel.interactive.documentnavigation.destination.PDPageDestination pd
                    && pd.getPage() != null) {
                return doc.getPages().indexOf(pd.getPage()) + 1;
            }
        } catch (Exception ignored) {
            // 目的地解析失败按第 1 页
        }
        return 1;
    }

    /**
     * 把书签名清洗成安全的文件名片段。
     *
     * <p>书签标题里常带 {@code \ / : * ? " < > |} 这些 Windows 非法字符（章节号、
     * 引号书名很常见），直接当文件名会创建失败。全部替换成下划线。
     *
     * <p>截断到 30 个字符而不是保留全长：Windows 的完整路径上限约 260 字符，
     * 而文件名前面还有输出目录、序号前缀。30 是个保守值，够区分又不会撞上限。
     * 截断按 char 而不是按 code point，理论上可能把一个代理对（emoji）切成两半，
     * 极端情况下文件名会出现乱码字符——但这不影响文件创建，可以接受。
     */
    private static String safeName(String s) {
        String t = s.replaceAll("[\\\\/:*?\"<>|\\s]+", "_");
        return t.length() > 30 ? t.substring(0, 30) : t;
    }

    // ---------- 共用 ----------

    /**
     * 按 0 基索引抽出若干页，另存为新文档。全项目共用的抽页工具。
     *
     * <p>用 {@code importPage} 逐页深拷贝：内容流、资源、注释都会带到新文档，
     * 目标文件自包含，不依赖源文件存在。
     *
     * <p><b>不会保留的东西</b>：文档级书签（Outline）、表单字段定义、命名目标、
     * 文档级 JavaScript。这些属于「文档结构」而非「页面内容」，{@code importPage} 不管。
     * 需要保留书签的场景得自己重建（参考合并功能里补一级书签的写法）。
     *
     * <p>调用方负责把 {@code out} 登记到 {@code ctx.addOutput}——本方法只管写文件，
     * 不碰上下文，这样它才能被各种能力自由复用。
     *
     * @param src     源文档（只读，不会被修改）
     * @param indices 要抽取的页索引，0 基，可重复、可乱序（重排页面就靠乱序）
     * @param out     输出路径，经由 {@link PdfUtil#save} 原子写入
     */
    static void writeSubset(PDDocument src, int[] indices, Path out) throws Exception {
        try (PDDocument dst = new PDDocument()) {
            for (int idx : indices) {
                dst.importPage(src.getPage(idx));
            }
            PdfUtil.save(dst, out);
        }
    }
}
