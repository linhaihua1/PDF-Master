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

/** 书签类能力：自动生成、导出、导入。自动书签是本项目自研算法（不依赖任何外部引擎）。 */
public final class BookmarkOps {

    private BookmarkOps() {
    }

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
                        if (l.text.length() < 2 || l.text.length() > maxLen) {
                            continue;
                        }
                        if (l.text.matches("^[\\d\\s.\\-—_]+$")) {
                            continue;
                        }
                        if (l.size >= threshold || (onlyBold && l.bold)) {
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
                    doc.getDocumentCatalog().setDocumentOutline(outline);
                    Map<Integer, PDOutlineItem> lastAtLevel = new HashMap<>();
                    for (BookmarkStripper.Line t : titles) {
                        int lvl = levelOf.get(t.size);
                        PDOutlineItem item = new PDOutlineItem();
                        item.setTitle(t.text);
                        PDPageFitDestination dest = new PDPageFitDestination();
                        dest.setPage(doc.getPage(t.page - 1));
                        item.setDestination(dest);
                        if (lvl <= 1) {
                            outline.addLast(item);
                        } else {
                            PDOutlineItem parent = lastAtLevel.get(lvl - 1);
                            if (parent == null) {
                                outline.addLast(item);
                            } else {
                                parent.addLast(item);
                            }
                        }
                        lastAtLevel.put(lvl, item);
                        lastAtLevel.keySet().removeIf(k -> k > lvl);
                    }
                    Path out = ctx.outDerived("_已加书签", ".pdf");
                    PdfUtil.save(doc, out);
                    ctx.addOutput(out);
                    ctx.report(100, "已生成 " + titles.size() + " 条书签");
                }
            });

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
            String t = text.replace('\u00A0', ' ').trim();
            if (t.isEmpty()) {
                return;
            }
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
            boolean bold = fontName.contains("bold") || fontName.contains("heavy") || fontName.contains("semibold");
            lines.add(new Line(t, size, bold, getCurrentPageNo()));
        }
    }

    /** 出现次数 × 字符数加权最多的字号，作为正文基准。 */
    static float bodyFontSize(List<BookmarkStripper.Line> lines) {
        Map<Integer, Integer> weight = new HashMap<>();
        for (BookmarkStripper.Line l : lines) {
            int key = Math.round(l.size * 2);
            weight.merge(key, Math.max(1, l.text.length()), Integer::sum);
        }
        int best = weight.entrySet().stream().max(Map.Entry.comparingByValue()).map(Map.Entry::getKey).orElse(24);
        return best / 2f;
    }

    private static int appendItems(Document xml, Element parent, PDOutlineItem first, PDDocument doc, int depth) {
        if (first == null || depth > 50) {
            return 0;
        }
        int count = 0;
        PDOutlineItem cur = first;
        while (cur != null) {
            Element el = xml.createElement("item");
            el.setAttribute("title", cur.getTitle() == null ? "" : cur.getTitle());
            el.setAttribute("page", String.valueOf(pageOf(cur, doc)));
            parent.appendChild(el);
            count += 1 + appendItems(xml, el, cur.getFirstChild(), doc, depth + 1);
            cur = cur.getNextSibling();
        }
        return count;
    }

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
            // 目的地解析失败按第 1 页处理
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
