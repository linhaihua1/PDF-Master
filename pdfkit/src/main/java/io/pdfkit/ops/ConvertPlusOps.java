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

/** 转换增强：Markdown、Word、多页 TIFF。 */
public final class ConvertPlusOps {

    private ConvertPlusOps() {
    }

    // ================= PDF → Markdown =================

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

    static List<Row> collect(PDDocument doc) throws Exception {
        Collector c = new Collector();
        c.setSortByPosition(true);
        c.getText(doc);
        return c.rows;
    }

    static final class Collector extends PDFTextStripper {
        final List<Row> rows = new ArrayList<>();

        @Override
        protected void writeString(String text, List<TextPosition> positions) {
            if (text == null || text.isBlank() || positions.isEmpty()) {
                return;
            }
            float size = 0;
            for (TextPosition tp : positions) {
                size = Math.max(size, tp.getFontSizeInPt());
            }
            rows.add(new Row(getCurrentPageNo(), size, positions.get(0).getYDirAdj(), text));
        }
    }

    /** 正文字号：按字数加权取出现最多的字号。 */
    static float bodySize(List<Row> rows) {
        Map<Integer, Integer> weight = new HashMap<>();
        for (Row r : rows) {
            int key = Math.round(r.size * 2);
            weight.merge(key, Math.max(1, r.text.length()), Integer::sum);
        }
        int best = weight.entrySet().stream().max(Map.Entry.comparingByValue()).map(Map.Entry::getKey).orElse(20);
        return best / 2f;
    }

    /** 把大于正文的字号去重降序，映射成 bucket 名 H1..Hn。 */
    static List<String> headingSizes(List<Row> rows, float body, int maxLevel) {
        List<Float> sizes = rows.stream()
                .map(r -> r.size)
                .filter(s -> s > body * 1.12f)
                .distinct()
                .sorted(Comparator.reverseOrder())
                .toList();
        Map<Float, String> map = new LinkedHashMap<>();
        List<String> buckets = new ArrayList<>();
        int lv = 0;
        for (Float s : sizes) {
            if (lv >= maxLevel) {
                break;
            }
            // 相邻字号差 < 3% 视为同一级
            if (!buckets.isEmpty()) {
                float prev = Float.parseFloat(buckets.get(buckets.size() - 1).substring(1));
                if (Math.abs(prev - s) / prev < 0.03f) {
                    map.put(s, "H" + lv);
                    continue;
                }
            }
            lv++;
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

    static boolean isBullet(String t) {
        return t.startsWith("•") || t.startsWith("·") || t.startsWith("▪")
                || t.matches("^[-*]\\s+.*") || t.matches("^\\d+[.)、]\\s+.*");
    }

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

    static void put(ZipOutputStream zip, String name, String content) throws Exception {
        zip.putNextEntry(new ZipEntry(name));
        zip.write(content.getBytes(StandardCharsets.UTF_8));
        zip.closeEntry();
    }

    static String xml(String s) {
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
                .replace("\"", "&quot;").replace("'", "&apos;");
    }

    /** 供其它能力复用的空页占位。 */
    static void blank(PDPage page) {
        // 保留接口：后续需要空页时统一在此处理
    }
}
