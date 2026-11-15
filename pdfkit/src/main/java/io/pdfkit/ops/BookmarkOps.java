package io.pdfkit.ops;

import io.pdfkit.core.Capability;
import io.pdfkit.core.Err;
import io.pdfkit.core.Param;
import io.pdfkit.util.PdfUtil;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.interactive.documentnavigation.destination.PDPageFitDestination;
import org.apache.pdfbox.pdmodel.interactive.documentnavigation.outline.PDDocumentOutline;
import org.apache.pdfbox.pdmodel.interactive.documentnavigation.outline.PDOutlineItem;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.pdfbox.text.TextPosition;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;
import javax.xml.transform.OutputKeys;
import javax.xml.transform.Transformer;
import javax.xml.transform.TransformerFactory;
import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.stream.StreamResult;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 书签类能力：自动生成、导出、导入。自动书签是本项目自研算法（不依赖任何外部引擎）。
 *
 * <p><b>自动书签的原理</b>：PDF 没有「这是标题」的语义标记，只能从排版特征反推。
 * 做法是：
 * <ol>
 *   <li>抽出每行文字及其字号、是否加粗；</li>
 *   <li>取全文的<b>正文基准字号</b>（出现最多的字号）；</li>
 *   <li>明显大于基准字号、或加粗的行，视为标题候选；</li>
 *   <li>按字号降序排出层级——字号最大的是一级标题，次之为二级，依此类推。</li>
 * </ol>
 * 这是启发式，不是语义理解，所以参数里留了「字号阈值」「只识别加粗」「最大层级」三个旋钮
 * 供用户按具体文档调。识别不准时优先调阈值，而不是改算法。
 *
 * <p><b>导入功能必须防 XXE</b>：书签 XML 是用户提供的输入文件，恶意文件可以借
 * 外部实体引用读取本机任意文件。见 {@link #newSafeBuilder()} 与 {@link #safeTransformerFactory()}，
 * 这是安全测试里抓出的高危问题，改动这两处前请先读注释。
 */
public final class BookmarkOps {

    private BookmarkOps() {
    }

    /**
     * 自动生成书签：识别标题 → 按字号分层 → 写入大纲。
     *
     * <p><b>四步原理</b>：① 用 {@link BookmarkStripper} 抓每一行文字及其字号、是否加粗；
     * ② 统计出「正文基准字号」；③ 比基准大一定比例的行判为标题；④ 按字号从大到小依次
     * 映射成 L1、L2…层级。
     *
     * <p><b>层级是按字号排序位次定的，不是按倍率算的</b>。假设正文 10pt，识别出 18pt、
     * 14pt、12pt 三种标题字号，那么它们分别是 1、2、3 级——即使 12pt 只比正文大 20%。
     * 这样反直觉的地方在于：文档若只有一种标题字号，所有标题都会成为 1 级，
     * 不会因为「只比正文大一点」就自降为 3 级。
     *
     * <p><b>扫描件必然失败</b>：没有文字层就没有字号信息，会抛 {@code ERR_NO_TEXT}。
     * 这是设计选择而不是缺陷——OCR 后的 PDF 才有文字层，本项不做图像识别。
     *
     * <p>建议先勾「只预览不生成文件」看识别效果：书签一旦写入就要重新生成文件，
     * 试错成本比预览高。
     */
    public static final Capability AUTO = new Capability(
            "bookmark.auto", "自动生成书签", "书签与目录",
            "按字号与加粗特征识别标题，自动生成多级书签。适合技术手册、报告、论文。",
            List.of(
                    Param.num("maxLevel", "最大层级", "3", "1-5"),
                    Param.num("ratio", "字号阈值(%)", "108", "100-200，比正文大多少算标题"),
                    Param.bool("onlyBold", "只识别加粗标题", false, "扫描件常无加粗信息，建议关闭"),
                    Param.num("maxLen", "标题最大长度", "80", "10-200"),
                    Param.bool("previewOnly", "只预览不生成文件", false, "先在结果里看识别效果")
            ),
            ctx -> {
                int maxLevel = ctx.intVal("maxLevel", 3, 1, 5);
                int ratioPct = ctx.intVal("ratio", 108, 100, 200);
                boolean onlyBold = ctx.bool("onlyBold", false);
                int maxLen = ctx.intVal("maxLen", 80, 10, 200);

                try (PDDocument doc = PdfUtil.load(ctx.input(), ctx.str("password"))) {
                    ctx.report(10, "提取文本行与字号");
                    BookmarkStripper stripper = new BookmarkStripper();
                    stripper.setSortByPosition(true);
                    stripper.setStartPage(1);
                    stripper.setEndPage(doc.getNumberOfPages());
                    stripper.getText(doc);

                    List<BookmarkStripper.Line> lines = stripper.lines;
                    if (lines.isEmpty()) {
                        throw new Err("ERR_NO_TEXT", "没有提取到文字，可能是扫描件。请先对 PDF 做 OCR，再生成书签。");
                    }
                    ctx.report(40, "共 " + lines.size() + " 行，正在统计字号");

                    float base = bodyFontSize(lines);
                    float threshold = base * ratioPct / 100f;

                    List<BookmarkStripper.Line> titles = new ArrayList<>();
                    for (BookmarkStripper.Line l : lines) {
                        // 单字行大概率是页码、装饰字，不算标题；超长行是正文段落被误判
                        if (l.text.length() < 2 || l.text.length() > maxLen) {
                            continue;
                        }
                        // 纯数字/标点/横线行（如「12」「···」「———」）多是页码或分隔线
                        if (l.text.matches("^[\\d\\s.\\-—_]+$")) {
                            continue;
                        }
                        if (l.size >= threshold || (onlyBold && l.bold)) {
                            // 勾了「只识别加粗」后，字号达标的非加粗行也要排除——
                            // 否则这个开关等于失效
                            if (onlyBold && !l.bold) {
                                continue;
                            }
                            titles.add(l);
                        }
                    }
                    if (titles.isEmpty()) {
                        throw new Err("ERR_NO_TITLE",
                                String.format("没有识别到标题。正文基准字号 %.1f pt，阈值 %.1f pt。"
                                                + "可把「字号阈值」调到 100 或更低，或关闭「只识别加粗」。",
                                        base, threshold));
                    }

                    // 字号降序 → 层级，最多 maxLevel 级
                    List<Float> distinct = titles.stream().map(t -> t.size).distinct()
                            .sorted(Comparator.reverseOrder()).toList();
                    Map<Float, Integer> levelOf = new LinkedHashMap<>();
                    for (Float s : distinct) {
                        int lvl = Math.min(maxLevel, distinct.indexOf(s) + 1);
                        levelOf.put(s, lvl);
                    }

                    StringBuilder preview = new StringBuilder();
                    preview.append("识别到 ").append(titles.size()).append(" 个标题，分 ")
                            .append(Math.min(maxLevel, distinct.size())).append(" 级（正文基准字号 ")
                            .append(String.format("%.1f", base)).append(" pt）\n\n");
                    int shown = 0;
                    for (BookmarkStripper.Line t : titles) {
                        int lvl = levelOf.get(t.size);
                        if (shown++ < 60) {
                            preview.append("  ".repeat(lvl - 1)).append("L").append(lvl)
                                    .append("  [p").append(t.page).append("] ")
                                    .append(t.text).append('\n');
                        }
                    }
                    if (titles.size() > 60) {
                        preview.append("  ... 其余 ").append(titles.size() - 60).append(" 条省略\n");
                    }
                    ctx.setResult(preview.toString());

                    if (ctx.bool("previewOnly", false)) {
                        ctx.report(100, "预览完成（未生成文件）");
                        return;
                    }

                    ctx.report(70, "写入书签");
                    PDDocumentOutline outline = new PDDocumentOutline();
                    // setDocumentOutline 会覆盖原有书签。若将来要做「保留已有书签、只追加」，
                    // 应改为读取现有 outline 再 addLast，而不是无条件新建
                    doc.getDocumentCatalog().setDocumentOutline(outline);
                    // 记录「每一层最近出现的那一项」，供下一层的标题挂靠。
                    // 标题在文档中是顺序出现的，所以同层最后一个必然是下层的正确父节点
                    Map<Integer, PDOutlineItem> lastAtLevel = new HashMap<>();
                    for (BookmarkStripper.Line t : titles) {
                        int lvl = levelOf.get(t.size);
                        PDOutlineItem item = new PDOutlineItem();
                        item.setTitle(t.text);
                        // PDPageFitDestination 表示「跳到该页并适配整页显示」，
                        // 这是最通用的目标类型，各阅读器都支持
                        PDPageFitDestination dest = new PDPageFitDestination();
                        dest.setPage(doc.getPage(t.page - 1));
                        item.setDestination(dest);
                        if (lvl <= 1) {
                            outline.addLast(item);
                        } else {
                            // 找不到上一级父节点（文档从 2 级标题开始，没有 L1）时降级挂到根，
                            // 否则这一条会被静默丢弃
                            PDOutlineItem parent = lastAtLevel.get(lvl - 1);
                            if (parent == null) {
                                outline.addLast(item);
                            } else {
                                parent.addLast(item);
                            }
                        }
                        lastAtLevel.put(lvl, item);
                        // 关键一步：清掉比当前更深的层级记录。否则 L3 标题之后又出现 L2 标题时，
                        // 新的 L3 会被挂到上一个 L3 所在分支下，形成错乱
                        lastAtLevel.keySet().removeIf(k -> k > lvl);
                    }
                    Path out = ctx.outDerived("_已加书签", ".pdf");
                    PdfUtil.save(doc, out);
                    ctx.addOutput(out);
                    ctx.report(100, "已生成 " + titles.size() + " 条书签");
                }
            });

    /**
     * 导出书签为 XML。
     *
     * <p><b>导出的是「标题 + 页码」而不是完整目标</b>：PDF 的目标（destination）可以是
     * 坐标级定位（跳到某页的某个位置、某种缩放），但这类信息对人不可读也不可编辑，
     * 导出成 XML 就失去了「手工改完再导回」的意义。只保留页码是刻意的取舍。
     *
     * <p>解析用加固后的建造器、序列化用加固后的工厂，两侧都关掉外部资源访问，
     * 具体理由见 {@link #newSafeBuilder()}。
     */
    public static final Capability EXPORT = new Capability(
            "bookmark.export", "导出书签", "书签与目录",
            "把书签树导出为 XML，便于备份或编辑后重新导入。",
            List.of(),
            ctx -> {
                try (PDDocument doc = PdfUtil.load(ctx.input(), ctx.str("password"))) {
                    var outline = doc.getDocumentCatalog().getDocumentOutline();
                    if (outline == null || outline.getFirstChild() == null) {
                        throw new Err("ERR_NO_BOOKMARK", "这个 PDF 没有书签");
                    }
                    ctx.report(30, "读取书签树");
                    Document xml = newSafeBuilder().newDocument();
                    Element root = xml.createElement("bookmarks");
                    xml.appendChild(root);
                    int count = appendItems(xml, root, outline.getFirstChild(), doc, 0);
                    Path out = ctx.outDerived("_书签", ".xml");
                    Transformer t = safeTransformerFactory().newTransformer();
                    t.setOutputProperty(OutputKeys.INDENT, "yes");
                    t.setOutputProperty(OutputKeys.ENCODING, "UTF-8");
                    t.transform(new DOMSource(xml), new StreamResult(Files.newBufferedWriter(out, StandardCharsets.UTF_8)));
                    ctx.addOutput(out);
                    ctx.report(100, "已导出 " + count + " 条书签");
                }
            });

    /**
     * 从 XML 导入书签，<b>覆盖原有书签</b>。
     *
     * <p>默认路径是「输入文件同目录下的同名 _书签.xml」，也就是导出时的默认产物名，
     * 这样「导出 → 手工编辑 → 导入」不需要用户额外记路径。
     *
     * <p>三道针对恶意 XML 的防线（本项目其余解析入口也应有同样思路）：
     * ① 文件大小上限 20 MB —— 正常书签 XML 只有几 KB 到几百 KB；
     * ② 解析器禁用 DOCTYPE 与外部实体 —— 挡 XXE 与 Billion Laughs；
     * ③ 递归深度上限 64 层 —— 挡深层嵌套导致的栈溢出。
     * 三道都只报错不静默降级，因为任何一条被触发都说明输入不可信。
     *
     * <p>{@code catch (Err e) { throw e; }} 必须在通用 catch 之前，
     * 否则「层级超过 64 层」这类明确提示会被包成笼统的「解析失败」。
     */
    public static final Capability IMPORT = new Capability(
            "bookmark.import", "导入书签", "书签与目录",
            "从导出的 XML 恢复书签树，覆盖原有书签。",
            List.of(Param.text("xmlPath", "XML 文件路径", "", "留空则读取输入文件同名 _书签.xml")),
            ctx -> {
                Path xmlPath;
                String custom = ctx.str("xmlPath");
                if (!custom.isBlank()) {
                    xmlPath = Path.of(custom);
                } else {
                    xmlPath = ctx.input().resolveSibling(PdfUtil.baseName(ctx.input()) + "_书签.xml");
                }
                if (!Files.isReadable(xmlPath)) {
                    throw new Err("ERR_NO_FILE", "找不到书签 XML：" + xmlPath);
                }
                // 书签 XML 只有几百 KB，超过 20 MB 几乎可以确定是恶意构造，直接拒绝避免内存耗尽
                try {
                    if (Files.size(xmlPath) > 20L * 1024 * 1024) {
                        throw new Err("ERR_BAD_XML", "书签 XML 超过 20 MB，已拒绝解析");
                    }
                } catch (IOException ignored) {
                    // 取不到大小就按正常流程继续，解析阶段还有兜底
                }
                try (PDDocument doc = PdfUtil.load(ctx.input(), ctx.str("password"))) {
                    Document xml = newSafeBuilder().parse(xmlPath.toFile());
                    Element root = xml.getDocumentElement();
                    PDDocumentOutline outline = new PDDocumentOutline();
                    doc.getDocumentCatalog().setDocumentOutline(outline);
                    int total = doc.getNumberOfPages();
                    int count = readItems(root, outline, doc, total, 0);
                    if (count == 0) {
                        throw new Err("ERR_BAD_XML", "XML 里没有可用的书签条目");
                    }
                    Path out = ctx.outDerived("_导入书签", ".pdf");
                    PdfUtil.save(doc, out);
                    ctx.addOutput(out);
                    ctx.report(100, "已导入 " + count + " 条书签");
                } catch (Err e) {
                    throw e;
                } catch (Exception e) {
                    throw new Err("ERR_BAD_XML", "书签 XML 解析失败：" + e.getMessage(), e);
                }
            });

    // ---------- 内部 ----------

    static final class BookmarkStripper extends PDFTextStripper {

        record Line(String text, float size, boolean bold, int page) {
        }

        final List<Line> lines = new ArrayList<>();

        BookmarkStripper() throws IOException {
            super();
        }

        @Override
        protected void writeString(String text, List<TextPosition> positions) throws IOException {
            if (positions == null || positions.isEmpty()) {
                return;
            }
            // 不换行空格 U+00A0 看着像普通空格但不参与 trim，会导致「标题」被误判为超长行
            String t = text.replace('\u00A0', ' ').trim();
            if (t.isEmpty()) {
                return;
            }
            // 一行内可能混着不同字号（如「第 3 章」用大字号、「概述」用小字号），
            // 取算术平均作为整行代表值。用最大值会让所有混合行都变成标题
            float sum = 0;
            for (TextPosition tp : positions) {
                sum += tp.getFontSizeInPt();
            }
            float size = sum / positions.size();
            String fontName = "";
            try {
                var f = positions.get(0).getFont();
                if (f != null && f.getName() != null) {
                    fontName = f.getName().toLowerCase();
                }
            } catch (Exception ignored) {
                // 字体不可读时按非加粗处理
            }
            // 只认字体名里的关键词，不看 FontDescriptor 的 Flags 位。这种做法对内嵌子集字体
            // （名字被改成 ABCDEF+SimHei 之类）仍有效，因为子集前缀不破坏「加粗」这个词；
            // 但名字完全无意义时（如 CMR10）会判不出加粗，此时用户应关闭「只识别加粗」
            boolean bold = fontName.contains("bold") || fontName.contains("heavy") || fontName.contains("semibold");
            lines.add(new Line(t, size, bold, getCurrentPageNo()));
        }
    }

    /**
     * 统计正文基准字号：按「字符数」加权而不是按「行数」。
     *
     * <p>一行 50 字的正文段落比一行 3 字的页眉更能代表正文，所以权重取字符数。
     * 若按行数统计，一本页眉很多的文档会把页眉字号误判成正文。
     *
     * <p><b>{@code Math.round(size * 2)} 是把字号量化到半磅</b>，用作 Map 的 key。
     * 直接拿 float 当 key 会因为浮点误差把同一个字号拆成多个桶（9.999999 与 10.0 不等），
     * 导致每个桶的计数都被摊薄，选不出真正的主字号。
     */
    static float bodyFontSize(List<BookmarkStripper.Line> lines) {
        Map<Integer, Integer> weight = new HashMap<>();
        for (BookmarkStripper.Line l : lines) {
            int key = Math.round(l.size * 2);
            // Math.max(1, ...) 防止空文本行贡献 0 权重（虽然 writeString 已过滤空行）
            weight.merge(key, Math.max(1, l.text.length()), Integer::sum);
        }
        // 缺省 24（即 12pt）：正常情况下走不到这里，因为调用前已确认 lines 非空
        int best = weight.entrySet().stream().max(Map.Entry.comparingByValue()).map(Map.Entry::getKey).orElse(24);
        return best / 2f;
    }

    /**
     * 递归遍历导出侧的书签树，写入 XML 的 {@code <item>} 节点。
     *
     * <p>深度上限 50：PDF 的 Outline 是链表 + 子树的组合结构，损坏文件里可能出现
     * 环状引用，无上限会直接栈溢出。
     *
     * @return 本层及以下写入了多少条，用于最终统计
     */
    private static int appendItems(Document xml, Element parent, PDOutlineItem first, PDDocument doc, int depth) {
        if (first == null || depth > 50) {
            return 0;
        }
        int count = 0;
        PDOutlineItem cur = first;
        // 同级节点靠 getNextSibling 串成链表，不是 List，所以只能 while 走
        while (cur != null) {
            Element el = xml.createElement("item");
            // getTitle 可能返回 null（大纲项没标题），而 setAttribute(null) 会抛异常
            el.setAttribute("title", cur.getTitle() == null ? "" : cur.getTitle());
            el.setAttribute("page", String.valueOf(pageOf(cur, doc)));
            parent.appendChild(el);
            // 先写自己（1 条）再递归写子树，最后累加
            count += 1 + appendItems(xml, el, cur.getFirstChild(), doc, depth + 1);
            cur = cur.getNextSibling();
        }
        return count;
    }

    /**
     * 取出书签项指向的页码（1 基）。解析不出来时返回 1。
     *
     * <p><b>第一个 instanceof 是冗余的</b>：{@code PDPageFitDestination} 继承自
     * {@code PDPageDestination}，第二个分支已经覆盖了它，两段合并成一段行为完全一致。
     * 保留的原因只是历史；重构时可以安全删掉第一个分支。
     */
    private static int pageOf(PDOutlineItem item, PDDocument doc) {
        try {
            var dest = item.getDestination();
            if (dest instanceof PDPageFitDestination d && d.getPage() != null) {
                return doc.getPages().indexOf(d.getPage()) + 1;
            }
            if (dest instanceof org.apache.pdfbox.pdmodel.interactive.documentnavigation.destination.PDPageDestination pd
                    && pd.getPage() != null) {
                return doc.getPages().indexOf(pd.getPage()) + 1;
            }
        } catch (Exception ignored) {
            // 目的地解析失败按第 1 页处理：宁可跳错页，也不要让整次导出失败
        }
        return 1;
    }

    private static int readItems(Element parent,
                                 org.apache.pdfbox.pdmodel.interactive.documentnavigation.outline.PDOutlineNode outlineParent,
                                 PDDocument doc, int totalPages, int depth) {
        // 恶意 XML 可以嵌套到几十万层把栈打爆，这里设上限
        if (depth > 64) {
            throw new Err("ERR_BAD_XML", "书签层级超过 64 层，疑似异常数据，已中止");
        }
        int count = 0;
        NodeList children = parent.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            Node n = children.item(i);
            if (n.getNodeType() != Node.ELEMENT_NODE || !"item".equals(n.getNodeName())) {
                continue;
            }
            Element el = (Element) n;
            String title = el.getAttribute("title");
            if (title == null || title.isBlank()) {
                continue;
            }
            int page = 1;
            try {
                page = Integer.parseInt(el.getAttribute("page"));
            } catch (NumberFormatException ignored) {
                // page 属性缺失时按第 1 页
            }
            page = Math.max(1, Math.min(totalPages, page));
            PDOutlineItem item = new PDOutlineItem();
            item.setTitle(title);
            PDPageFitDestination dest = new PDPageFitDestination();
            dest.setPage(doc.getPage(page - 1));
            item.setDestination(dest);
            outlineParent.addLast(item);
            count += 1 + readItems(el, item, doc, totalPages, depth + 1);
        }
        return count;
    }

    /**
     * 加固后的 XML 解析器：禁用 DOCTYPE 与外部实体。
     *
     * <p>书签 XML 属于用户可替换的输入，若不禁用外部实体，恶意文件可以用
     * {@code <!ENTITY x SYSTEM "file:///C:/...">} 读取本机文件并写进书签标题，
     * 或用 Billion Laughs 把内存撑爆。
     */
    private static DocumentBuilder newSafeBuilder() {
        try {
            DocumentBuilderFactory f = DocumentBuilderFactory.newInstance();
            f.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            f.setFeature("http://xml.org/sax/features/external-general-entities", false);
            f.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
            f.setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false);
            f.setXIncludeAware(false);
            f.setExpandEntityReferences(false);
            f.setNamespaceAware(false);
            return f.newDocumentBuilder();
        } catch (ParserConfigurationException e) {
            throw new Err("ERR_BAD_XML", "无法初始化 XML 解析器：" + e.getMessage(), e);
        }
    }

    /** 导出侧也关掉外部 DTD 与样式表访问，避免 XSLT 注入面。 */
    private static TransformerFactory safeTransformerFactory() {
        TransformerFactory tf = TransformerFactory.newInstance();
        try {
            tf.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
            tf.setAttribute(XMLConstants.ACCESS_EXTERNAL_STYLESHEET, "");
        } catch (IllegalArgumentException ignored) {
            // 某些实现不支持该属性，忽略即可（导出方向无外部输入）
        }
        return tf;
    }
}
