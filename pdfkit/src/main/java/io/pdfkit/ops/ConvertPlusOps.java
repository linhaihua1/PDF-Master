package io.pdfkit.ops;

import io.pdfkit.core.Capability;
import io.pdfkit.core.Ctx;
import io.pdfkit.core.Err;
import io.pdfkit.core.Param;
import io.pdfkit.util.PdfUtil;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.rendering.ImageType;
import org.apache.pdfbox.rendering.PDFRenderer;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.pdfbox.text.TextPosition;

import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageWriter;
import javax.imageio.stream.ImageOutputStream;
import java.awt.image.BufferedImage;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * 转换增强：Markdown、Word、多页 TIFF。
 *
 * <p>三项都是「把 PDF 转成另一种格式」，但难度与信息损失程度差别很大：
 * <ul>
 *   <li><b>Markdown</b> —— 靠字号聚类猜标题层级，再用启发式规则识别列表。
 *       输出是纯文本，必然丢失精确排版，但保住了「结构感」。</li>
 *   <li><b>Word（docx）</b> —— 同理猜标题，再生成 OOXML 段落。表格会退化成段落文字，
 *       图片不导出。可编辑性是它的价值所在。</li>
 *   <li><b>多页 TIFF</b> —— 逐页渲染后编码成一个多帧 TIFF。信息损失最大（全是位图），
 *       但符合某些归档/传真系统的硬性要求。</li>
 * </ul>
 *
 * <p><b>docx 是手写 OOXML，没有引入 Apache POI</b>：POI 会给安装包增加约 10 MB，
 * 而这里只需要「若干段落 + 三级标题样式 + 页面尺寸」。手写几个 XML 文件打进 zip
 * 就是一份合法 docx，体积代价为零。这是「体积优先」原则在依赖选择上的体现。
 */
public final class ConvertPlusOps {

    private ConvertPlusOps() {
    }

    // ================= PDF → Markdown =================

    /**
     * PDF 转 Markdown：按字号识别标题，其余行按段落/列表输出。
     *
     * <p><b>输出的标题整体下移一级</b>：文件首行用 {@code # 文件名} 作总标题，
     * 所以原文的 H1 变成 {@code ##}、H2 变成 {@code ###}。公式是 {@code lv + 2}
     * （lv 是 0 基下标），上限是 {@code maxLevel + 1} 个井号，避免出现六七个井号的畸形标题。
     *
     * <p><b>信息会丢失</b>：本项只保留「文字顺序 + 标题层级 + 列表符号」，字体、颜色、
     * 字号、缩进、表格结构、图片全部不保留。表格会被摊平成以空行分隔的纯文本。
     * 这是 Markdown 表达能力的限制，不是实现缺陷。
     *
     * <p>「保留分页标记」会在页与页之间插入 {@code ---}（Markdown 的水平分割线），
     * 首行不插——开头就一条横线很突兀。
     */
    public static final Capability TO_MARKDOWN = new Capability(
            "convert.toMarkdown", "PDF 转 Markdown", "格式转换",
            "按字号识别标题层级，输出 .md 文件。适合技术文档、手册再编辑。表格转为空行分隔的纯文本。",
            List.of(
                    Param.num("maxLevel", "最大标题层级", "3", "1-4"),
                    Param.bool("keepPageBreak", "保留分页标记", false, "每页之间插入分隔线")
            ),
            ctx -> {
                int maxLevel = ctx.intVal("maxLevel", 3, 1, 4);
                try (PDDocument doc = PdfUtil.load(ctx.input(), ctx.str("password"))) {
                    ctx.report(15, "分析版面");
                    List<Row> rows = collect(doc);
                    if (rows.isEmpty()) {
                        throw new Err("ERR_NO_TEXT", "这个 PDF 没有可提取的文字（可能是扫描件，请先用 OCR 工具处理）");
                    }
                    float body = bodySize(rows);
                    List<String> levels = headingSizes(rows, body, maxLevel);
                    StringBuilder md = new StringBuilder();
                    String base = PdfUtil.baseName(ctx.input());
                    md.append("# ").append(base).append("\n\n");
                    int lastPage = -1;
                    for (Row r : rows) {
                        ctx.checkCancel();
                        String t = r.text.trim();
                        if (t.isEmpty()) {
                            continue;
                        }
                        if (ctx.bool("keepPageBreak", false) && r.page != lastPage && lastPage != -1) {
                            md.append("\n---\n\n");
                        }
                        lastPage = r.page;
                        // levels 是 bucket 名单（H1、H2…），indexOf 得到 0 基层级；
                        // 返回 -1 表示这行不是标题。井号数量见本能力 javadoc
                        int lv = levels.indexOf(r.bucket);
                        if (lv >= 0) {
                            md.append("#".repeat(Math.min(maxLevel + 1, lv + 2))).append(' ').append(t).append("\n\n");
                        } else if (isBullet(t)) {
                            md.append("- ").append(stripBullet(t)).append('\n');
                        } else {
                            md.append(t).append("\n\n");
                        }
                    }
                    Path out = ctx.outDerived("", ".md");
                    Files.writeString(out, md.toString(), StandardCharsets.UTF_8);
                    ctx.addOutput(out);
                    ctx.report(100, "已转换 " + rows.size() + " 行");
                }
            });

    // ================= PDF → Word =================

    public static final Capability TO_WORD = new Capability(
            "convert.toWord", "PDF 转 Word", "格式转换",
            "生成可编辑 .docx（标题按字号分级、段落按行还原）。不做像素级还原，图片不嵌入。",
            List.of(
                    Param.num("maxLevel", "最大标题层级", "3", "1-4"),
                    Param.num("fontSize", "正文字号(pt)", "10", "8-14")
            ),
            ctx -> {
                int maxLevel = ctx.intVal("maxLevel", 3, 1, 4);
                int fontSize = ctx.intVal("fontSize", 10, 8, 14);
                try (PDDocument doc = PdfUtil.load(ctx.input(), ctx.str("password"))) {
                    ctx.report(15, "分析版面");
                    List<Row> rows = collect(doc);
                    if (rows.isEmpty()) {
                        throw new Err("ERR_NO_TEXT", "这个 PDF 没有可提取的文字（可能是扫描件）");
                    }
                    float body = bodySize(rows);
                    List<String> levels = headingSizes(rows, body, maxLevel);

                    StringBuilder bodyXml = new StringBuilder();
                    for (Row r : rows) {
                        ctx.checkCancel();
                        String t = r.text.trim();
                        if (t.isEmpty()) {
                            continue;
                        }
                        int lv = levels.indexOf(r.bucket);
                        if (lv >= 0) {
                            bodyXml.append(para("Heading" + Math.min(lv + 1, maxLevel), t));
                        } else if (isBullet(t)) {
                            bodyXml.append(para("ListParagraph", "• " + stripBullet(t)));
                        } else {
                            bodyXml.append(para(null, t));
                        }
                    }
                    Path out = ctx.outDerived("", ".docx");
                    ctx.report(70, "写出 docx");
                    writeDocx(out, bodyXml.toString(), fontSize);
                    ctx.addOutput(out);
                    ctx.report(100, "已转换 " + rows.size() + " 行");
                }
            });

    // ================= PDF → 多页 TIFF =================

    public static final Capability TO_TIFF = new Capability(
            "convert.toTiff", "PDF 转多页 TIFF", "格式转换",
            "把全部页面输出成一个多页 TIFF。归档、传真、老系统交付常用。",
            List.of(
                    Param.num("dpi", "分辨率", "200", "96-600"),
                    Param.num("gray", "灰度(1)或彩色(0)", "0", "0=彩色，1=灰度"),
                    Param.choice("compress", "压缩方式", "LZW", "LZW", "Deflate", "不压缩")
            ),
            ctx -> {
                int dpi = ctx.intVal("dpi", 200, 96, 600);
                boolean gray = ctx.intVal("gray", 0, 0, 1) == 1;
                String comp = ctx.str("compress", "LZW");
                try (PDDocument doc = PdfUtil.load(ctx.input(), ctx.str("password"))) {
                    int total = doc.getNumberOfPages();
                    Path out = ctx.outDerived("", ".tif");
                    ImageWriter writer = ImageIO.getImageWritersByFormatName("tiff").next();
                    if (writer == null) {
                        throw new Err("ERR_NO_TIFF", "当前运行环境缺少 TIFF 编码器");
                    }
                    PDFRenderer renderer = new PDFRenderer(doc);
                    ImageWriteParam param = writer.getDefaultWriteParam();
                    if (param.canWriteCompressed()) {
                        param.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
                        String type = "Deflate".equals(comp) ? "Deflate" : ("不压缩".equals(comp) ? "None" : "LZW");
                        param.setCompressionType(type);
                    }
                    try (ImageOutputStream ios = ImageIO.createImageOutputStream(out.toFile())) {
                        writer.setOutput(ios);
                        writer.prepareWriteSequence(null);
                        for (int i = 0; i < total; i++) {
                            ctx.checkCancel();
                            ctx.report(5 + i * 90 / total, "渲染第 " + (i + 1) + "/" + total + " 页");
                            BufferedImage img = renderer.renderImageWithDPI(i, dpi,
                                    gray ? ImageType.GRAY : ImageType.RGB);
                            writer.writeToSequence(new IIOImage(img, null, null), param);
                        }
                        writer.endWriteSequence();
                    } finally {
                        writer.dispose();
                    }
                    ctx.addOutput(out);
                    ctx.report(100, total + " 页写入一个 TIFF");
                }
            });

    // ================= 共用：版面提取 =================

    static final class Row {
        final int page;
        final float size;
        final float y;
        final String text;
        String bucket;

        Row(int page, float size, float y, String text) {
            this.page = page;
            this.size = size;
            this.y = y;
            this.text = text;
        }
    }

    /**
     * 提取全文并按版面顺序收集成 {@link Row} 列表，供 Markdown / Word 两种输出共用。
     *
     * <p>{@code setSortByPosition(true)} 不能省：不排序时得到的是内容流的原始顺序，
     * 双栏排版会变成「左栏全文 + 右栏全文」，段落顺序彻底错乱。
     *
     * <p>每行的字号取<b>该行所有字符的最大值</b>（而非平均值）：一行里若混着 18pt 标题
     * 和 10pt 注释，取平均值会得到 14pt，两头都判不准；取最大值确保带大字的行仍被识别为标题。
     */
    static List<Row> collect(PDDocument doc) throws Exception {
        Collector c = new Collector();
        c.setSortByPosition(true);
        c.getText(doc);
        return c.rows;
    }

    /** 文本行收集器：把每一行连同字号、纵坐标、页码一起记下来。 */
    static final class Collector extends PDFTextStripper {
        final List<Row> rows = new ArrayList<>();

        @Override
        protected void writeString(String text, List<TextPosition> positions) {
            if (text == null || text.isBlank() || positions.isEmpty()) {
                return;
            }
            // 取最大值，理由见 collect() 的说明
            float size = 0;
            for (TextPosition tp : positions) {
                size = Math.max(size, tp.getFontSizeInPt());
            }
            // getYDirAdj() 返回的是「自上而下」坐标（页面顶部为 0），与 PDF 原生坐标相反。
            // 用它是因为后续按阅读顺序处理更直观，不需要再做 Y 轴翻转
            rows.add(new Row(getCurrentPageNo(), size, positions.get(0).getYDirAdj(), text));
        }
    }

    /** 正文字号：按字数加权取出现最多的字号。量化到半磅的理由见 BookmarkOps.bodyFontSize。 */
    static float bodySize(List<Row> rows) {
        Map<Integer, Integer> weight = new HashMap<>();
        for (Row r : rows) {
            int key = Math.round(r.size * 2);
            weight.merge(key, Math.max(1, r.text.length()), Integer::sum);
        }
        int best = weight.entrySet().stream().max(Map.Entry.comparingByValue()).map(Map.Entry::getKey).orElse(20);
        return best / 2f;
    }

    /**
     * 把大于正文的字号去重降序，映射成 bucket 名 H1..Hn，并回写到每一行的 {@code bucket}。
     *
     * <p><b>为什么需要「相邻字号差 &lt; 3% 视为同一级」这一步</b>：PDF 里的字号常出现
     * 18.0 与 17.9 这种微小差异（源于字体缩放矩阵的浮点误差），若按「不同字号就是不同级别」
     * 处理，一份文档会被拆出七八个层级，标题全乱。3% 是经验阈值，能容下误差又不会
     * 把真正的相邻级别（通常差 10% 以上）合并掉。
     *
     * <p><b>必须单独维护每级的代表字号</b>（{@code levelSizes}）。曾经用
     * {@code buckets.get(...).substring(1)} 去取「上一级的字号」，但那取到的是层级号
     * 1、2、3，与字号（如 18）比较永远不会小于 3%，导致判重完全失效。
     *
     * <p>最后一段是浮点误差兜底：先按精确字号查 map，查不到时找差值在 0.5pt 以内的
     * 最近一级。没有这段的话，正文里混入的 17.95 之类字号会既不是标题也匹配不上标题，
     * 落成空 bucket。
     */
    static List<String> headingSizes(List<Row> rows, float body, int maxLevel) {
        List<Float> sizes = rows.stream()
                .map(r -> r.size)
                .filter(s -> s > body * 1.12f)
                .distinct()
                .sorted(Comparator.reverseOrder())
                .toList();
        Map<Float, String> map = new LinkedHashMap<>();
        List<String> buckets = new ArrayList<>();
        // 每一级的代表字号，用于判重。与 buckets 一一对应（下标 0 = H1）
        List<Float> levelSizes = new ArrayList<>();
        int lv = 0;
        for (Float s : sizes) {
            if (lv >= maxLevel) {
                break;
            }
            // 相邻字号差 < 3% 视为同一级
            if (!levelSizes.isEmpty()) {
                float prev = levelSizes.get(levelSizes.size() - 1);
                if (Math.abs(prev - s) / prev < 0.03f) {
                    map.put(s, "H" + lv);
                    continue;
                }
            }
            lv++;
            levelSizes.add(s);
            buckets.add("H" + lv);
            map.put(s, "H" + lv);
        }
        for (Row r : rows) {
            r.bucket = map.getOrDefault(r.size, "");
            if (r.bucket.isEmpty()) {
                // 浮点误差兜底：找最接近的标题字号
                for (Map.Entry<Float, String> e : map.entrySet()) {
                    if (Math.abs(e.getKey() - r.size) < 0.5f) {
                        r.bucket = e.getValue();
                        break;
                    }
                }
            }
        }
        return buckets;
    }

    /**
     * 判断一行是否像列表项：圆点/方点开头，或 {@code - *} 加空格开头，或 {@code 1. 1) 1、} 开头。
     *
     * <p>这是启发式判断，<b>必然有误判</b>：正文里以「- 」开头的破折号段落会被当成列表。
     * 但转 Markdown/Word 的预期本来就是「尽力保留结构」，误判成列表比丢失列表更能接受。
     *
     * <p>注意数字序号的正则要求后面必须跟空格，避免把「2024.」这种年份开头的句子当成有序列表。
     */
    static boolean isBullet(String t) {
        return t.startsWith("•") || t.startsWith("·") || t.startsWith("▪")
                || t.matches("^[-*]\\s+.*") || t.matches("^\\d+[.)、]\\s+.*");
    }

    /**
     * 去掉列表符号，只留正文。
     *
     * <p>圆点类直接删掉；<b>数字序号保留</b>（{@code 1.} 只压缩空格，不删序号），
     * 因为序号是内容的一部分——Markdown 的有序列表虽然会自动编号，但原文里的序号
     * 可能不是从 1 开始或不连续，删掉就丢了信息。
     */
    static String stripBullet(String t) {
        return t.replaceFirst("^[•·▪]\\s*", "").replaceFirst("^[-*]\\s+", "").replaceFirst("^(\\d+[.)、])\\s+", "$1 ");
    }

    // ================= 共用：docx 生成 =================

    static String para(String style, String text) {
        StringBuilder sb = new StringBuilder("<w:p>");
        if (style != null) {
            sb.append("<w:pPr><w:pStyle w:val=\"").append(style).append("\"/></w:pPr>");
        }
        sb.append("<w:r><w:t xml:space=\"preserve\">").append(xml(text)).append("</w:t></w:r></w:p>");
        return sb.toString();
    }

    /**
     * 手工拼出 docx 所需的全部 OOXML 部件并打包成 zip。
     *
     * <p>合法的 docx 至少需要这几个部件：
     * {@code [Content_Types].xml}、{@code _rels/.rels}、{@code word/document.xml}、
     * {@code word/_rels/document.xml.rels}、{@code word/styles.xml}。
     * 少了任何一个，Word 打开时会报「文件已损坏」。
     *
     * <p>页面尺寸写死 A4（{@code w:w="11906" w:h="16838"}，单位是 1/20 pt），
     * 边距 1440（即 1 英寸）。不做「还原原 PDF 页面尺寸」——转 Word 的目的通常是继续编辑，
     * 用标准 A4 比还原某个奇怪的定制尺寸更实用。
     *
     * <p>样式里同时设了 {@code w:ascii} 与 {@code w:eastAsia} 为微软雅黑：
     * Word 对中西文用两套字体设置，只设 ascii 的话中文会 fallback 成默认字体。
     * <p><b>已知限制</b>：styles.xml 只定义了 Heading1/2/3 三个标题样式，而参数面板允许
     * {@code maxLevel} 取到 4。用户选 4 时，第 4 级标题会引用不存在的 Heading4 样式，
     * Word 打开后退化为正文外观（内容不丢，只是层级和字号体现不出来）。要支持第 4 级，
     * 需在此处补一段 {@code w:styleId="Heading4"} 的定义。
     *
     * <p>{@code w:sz} 的单位是<b>半磅</b>，所以传进来的 {@code fontSize} 要乘 2。
     * 写成 pt 值会让字号只有一半大。
     */
    static void writeDocx(Path out, String bodyXml, int fontSize) throws Exception {
        String doc = """
                <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
                <w:document xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main">
                <w:body>%s<w:sectPr><w:pgSz w:w="11906" w:h="16838"/>
                <w:pgMar w:top="1440" w:right="1440" w:bottom="1440" w:left="1440"/></w:sectPr>
                </w:body></w:document>
                """.formatted(bodyXml);

        String styles = """
                <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
                <w:styles xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main">
                <w:docDefaults><w:rPrDefault><w:rPr>
                <w:rFonts w:ascii="Microsoft YaHei" w:eastAsia="Microsoft YaHei" w:hAnsi="Microsoft YaHei"/>
                <w:sz w:val="%d"/><w:szCs w:val="%d"/></w:rPr></w:rPrDefault></w:docDefaults>
                <w:style w:type="paragraph" w:styleId="Heading1"><w:name w:val="heading 1"/>
                <w:pPr><w:spacing w:before="360" w:after="120"/><w:outlineLvl w:val="0"/></w:pPr>
                <w:rPr><w:b/><w:sz w:val="36"/></w:rPr></w:style>
                <w:style w:type="paragraph" w:styleId="Heading2"><w:name w:val="heading 2"/>
                <w:pPr><w:spacing w:before="280" w:after="100"/><w:outlineLvl w:val="1"/></w:pPr>
                <w:rPr><w:b/><w:sz w:val="30"/></w:rPr></w:style>
                <w:style w:type="paragraph" w:styleId="Heading3"><w:name w:val="heading 3"/>
                <w:pPr><w:spacing w:before="220" w:after="80"/><w:outlineLvl w:val="2"/></w:pPr>
                <w:rPr><w:b/><w:sz w:val="26"/></w:rPr></w:style>
                <w:style w:type="paragraph" w:styleId="ListParagraph"><w:name w:val="List Paragraph"/>
                <w:pPr><w:ind w:left="420"/></w:pPr></w:style>
                </w:styles>
                """.formatted(fontSize * 2, fontSize * 2);

        String contentTypes = """
                <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
                <Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types">
                <Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/>
                <Default Extension="xml" ContentType="application/xml"/>
                <Override PartName="/word/document.xml"
                  ContentType="application/vnd.openxmlformats-officedocument.wordprocessingml.document.main+xml"/>
                <Override PartName="/word/styles.xml"
                  ContentType="application/vnd.openxmlformats-officedocument.wordprocessingml.styles+xml"/>
                </Types>
                """;

        String rels = """
                <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
                <Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">
                <Relationship Id="rId1"
                  Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument"
                  Target="word/document.xml"/>
                </Relationships>
                """;

        String docRels = """
                <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
                <Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">
                <Relationship Id="rId1"
                  Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/styles"
                  Target="styles.xml"/>
                </Relationships>
                """;

        Files.createDirectories(out.getParent());
        try (OutputStream os = Files.newOutputStream(out);
             ZipOutputStream zip = new ZipOutputStream(os, StandardCharsets.UTF_8)) {
            put(zip, "[Content_Types].xml", contentTypes);
            put(zip, "_rels/.rels", rels);
            put(zip, "word/document.xml", doc);
            put(zip, "word/styles.xml", styles);
            put(zip, "word/_rels/document.xml.rels", docRels);
        }
    }

    /**
     * 往 zip 里写一个文本部件。
     *
     * <p>条目名必须是包内相对路径（如 {@code word/document.xml}），不能以 {@code /} 开头——
     * 前导斜杠会让 Word 认为部件路径非法。
     *
     * <p>zip 用 UTF-8 建（见 {@link #writeDocx} 的构造函数），内容也按 UTF-8 写；
     * 两者不一致时中文会变乱码。
     */
    static void put(ZipOutputStream zip, String name, String content) throws Exception {
        zip.putNextEntry(new ZipEntry(name));
        zip.write(content.getBytes(StandardCharsets.UTF_8));
        zip.closeEntry();
    }

    /**
     * XML 文本转义。
     *
     * <p><b>顺序不能乱：{@code &} 必须第一个替换</b>。若先替换 {@code <} 为 {@code &lt;}，
     * 再替换 {@code &}，那刚生成的 {@code &lt;} 会被二次转义成 {@code &amp;lt;}，
     * 文档里就会原样显示 {@code &lt;} 这串字符。
     */
    static String xml(String s) {
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
                .replace("\"", "&quot;").replace("'", "&apos;");
    }

    /** 供其它能力复用的空页占位。 */
    static void blank(PDPage page) {
        // 保留接口：后续需要空页时统一在此处理
    }
}
