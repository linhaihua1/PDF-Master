package io.pdfkit.ops;

import io.pdfkit.core.Capability;
import io.pdfkit.core.Param;
import io.pdfkit.util.PdfUtil;
import org.apache.pdfbox.cos.COSBase;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.cos.COSStream;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDFont;

import java.nio.file.Files;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/** 分析类能力：文档信息、字体体检、结构探查。结果以文本返回，不产出文件。 */
public final class AnalyzeOps {

    private AnalyzeOps() {
    }

    public static final Capability INFO = new Capability(
            "analyze.info", "文档基础信息", "读取与分析",
            "页数、纸张尺寸、PDF 版本、加密状态、元数据、书签与表单概况。",
            List.of(),
            ctx -> {
                try (PDDocument doc = PdfUtil.load(ctx.input(), ctx.str("password"))) {
                    StringBuilder sb = new StringBuilder();
                    var in = ctx.input();
                    sb.append("文件：").append(in.getFileName()).append('\n');
                    sb.append("大小：").append(String.format("%.2f MB", Files.size(in) / 1024.0 / 1024.0)).append('\n');
                    sb.append("页数：").append(doc.getNumberOfPages()).append('\n');
                    sb.append("PDF 版本：").append(doc.getVersion()).append('\n');
                    sb.append("加密：").append(doc.isEncrypted() ? "是" : "否").append('\n');
                    sb.append("交叉引用流：").append(doc.getDocument().isXRefStream() ? "是（PDF 1.5+）" : "否（传统 xref 表）").append('\n');

                    Map<String, Integer> sizes = new LinkedHashMap<>();
                    for (PDPage p : doc.getPages()) {
                        PDRectangle b = p.getCropBox();
                        String label = describeSize(b);
                        sizes.merge(label, 1, Integer::sum);
                    }
                    sb.append("纸张尺寸：").append(String.join("；",
                            sizes.entrySet().stream().map(e -> e.getKey() + "×" + e.getValue() + "页").toList())).append('\n');

                    var info = doc.getDocumentInformation();
                    sb.append("\n【元数据】\n");
                    sb.append("标题：").append(nullSafe(info.getTitle())).append('\n');
                    sb.append("作者：").append(nullSafe(info.getAuthor())).append('\n');
                    sb.append("主题：").append(nullSafe(info.getSubject())).append('\n');
                    sb.append("关键词：").append(nullSafe(info.getKeywords())).append('\n');
                    sb.append("创建工具：").append(nullSafe(info.getCreator())).append('\n');
                    sb.append("生成程序：").append(nullSafe(info.getProducer())).append('\n');
                    sb.append("创建时间：").append(nullSafe(info.getCreationDate())).append('\n');
                    sb.append("修改时间：").append(nullSafe(info.getModificationDate())).append('\n');

                    int annots = 0;
                    for (PDPage p : doc.getPages()) {
                        annots += p.getAnnotations().size();
                    }
                    var outline = doc.getDocumentCatalog().getDocumentOutline();
                    int bookmarks = 0;
                    if (outline != null) {
                        bookmarks = countOutline(outline.getFirstChild(), 0);
                    }
                    var form = doc.getDocumentCatalog().getAcroForm();
                    sb.append("\n【结构概况】\n");
                    sb.append("注释：").append(annots).append(" 个\n");
                    sb.append("书签：").append(bookmarks).append(" 条\n");
                    sb.append("表单字段：").append(form == null ? 0 : form.getFields().size()).append(" 个\n");
                    ctx.setResult(sb.toString());
                    ctx.report(100, "分析完成");
                }
            });

    public static final Capability FONTS = new Capability(
            "analyze.fonts", "字体嵌入体检", "读取与分析",
            "列出全部字体并标出未嵌入的项——未嵌入字体在别的电脑上会变形或缺字。",
            List.of(Param.bool("exportCsv", "导出 CSV 报告", true, "保存到输出目录")),
            ctx -> {
                try (PDDocument doc = PdfUtil.load(ctx.input(), ctx.str("password"))) {
                    Map<String, FontInfo> fonts = new TreeMap<>();
                    int pageNo = 0;
                    for (PDPage page : doc.getPages()) {
                        pageNo++;
                        var res = page.getResources();
                        if (res == null) {
                            continue;
                        }
                        for (COSName name : res.getFontNames()) {
                            PDFont f;
                            try {
                                f = res.getFont(name);
                            } catch (Exception e) {
                                continue;
                            }
                            if (f == null) {
                                continue;
                            }
                            String key = f.getName() + "|" + f.getClass().getSimpleName();
                            FontInfo fi = fonts.computeIfAbsent(key, k -> new FontInfo(f.getName(), f.getClass().getSimpleName()));
                            fi.embedded = fi.embedded || f.isEmbedded();
                            if (!fi.pages.contains(pageNo) && fi.pages.size() < 20) {
                                fi.pages.add(pageNo);
                            }
                            fi.count++;
                        }
                        ctx.report(10 + pageNo * 80 / Math.max(1, doc.getNumberOfPages()), "检查第 " + pageNo + " 页字体");
                    }

                    long missing = fonts.values().stream().filter(f -> !f.embedded).count();
                    StringBuilder sb = new StringBuilder();
                    sb.append("共 ").append(fonts.size()).append(" 种字体，其中 ").append(missing).append(" 种未嵌入。\n\n");
                    if (missing > 0) {
                        sb.append("⚠ 未嵌入字体在其他电脑上可能显示为替代字体或方框：\n");
                    }
                    for (FontInfo f : fonts.values()) {
                        sb.append(f.embedded ? "  [已嵌入] " : "  [未嵌入] ")
                                .append(f.name).append("  (").append(f.type).append(")")
                                .append("  使用 ").append(f.count).append(" 次")
                                .append("  首页 ").append(f.pages.isEmpty() ? "-" : f.pages.get(0))
                                .append('\n');
                    }
                    ctx.setResult(sb.toString());

                    if (ctx.bool("exportCsv", true) && !fonts.isEmpty()) {
                        StringBuilder csv = new StringBuilder("字体名,类型,是否嵌入,使用次数,首次出现页\n");
                        for (FontInfo f : fonts.values()) {
                            csv.append('"').append(f.name).append("\",\"").append(f.type).append("\",")
                                    .append(f.embedded ? "是" : "否").append(',')
                                    .append(f.count).append(',')
                                    .append(f.pages.isEmpty() ? "" : f.pages.get(0)).append('\n');
                        }
                        java.nio.file.Path out = ctx.outDerived("_字体体检", ".csv");
                        Files.writeString(out, csv.toString(), java.nio.charset.StandardCharsets.UTF_8);
                        ctx.addOutput(out);
                    }
                    ctx.report(100, "字体体检完成");
                }
            });

    public static final Capability STRUCTURE = new Capability(
            "analyze.structure", "文档结构探查", "读取与分析",
            "检查 PDF 内部对象、流、交叉引用表健康度，定位损坏点。",
            List.of(),
            ctx -> {
                try (PDDocument doc = PdfUtil.load(ctx.input(), ctx.str("password"))) {
                    var cos = doc.getDocument();
                    var xref = cos.getXrefTable();
                    long streams = 0;
                    long dicts = 0;
                    long arrays = 0;
                    for (var key : xref.keySet()) {
                        try {
                            COSBase o = cos.getObjectFromPool(key).getObject();
                            if (o instanceof COSStream) {
                                streams++;
                            } else if (o instanceof org.apache.pdfbox.cos.COSDictionary) {
                                dicts++;
                            } else if (o instanceof org.apache.pdfbox.cos.COSArray) {
                                arrays++;
                            }
                        } catch (Exception ignored) {
                            // 坏对象跳过，不影响整体统计
                        }
                    }
                    int objectCount = xref.size();

                    StringBuilder sb = new StringBuilder();
                    sb.append("【对象统计】\n");
                    sb.append("对象总数：").append(objectCount).append('\n');
                    sb.append("流对象：").append(streams).append('\n');
                    sb.append("字典：").append(dicts).append('\n');
                    sb.append("数组：").append(arrays).append('\n');
                    sb.append("\n【交叉引用】\n");
                    sb.append("xref 类型：").append(cos.isXRefStream() ? "交叉引用流（PDF 1.5+）" : "传统 xref 表").append('\n');
                    sb.append("头部偏移：").append(cos.getStartXref()).append('\n');
                    sb.append("\n【版本与加密】\n");
                    sb.append("版本：").append(doc.getVersion()).append('\n');
                    sb.append("加密：").append(doc.isEncrypted()
                            ? "是（" + (doc.getEncryption() == null ? "未知算法"
                            : doc.getEncryption().getSecurityHandler().getClass().getSimpleName()) + "）"
                            : "否").append('\n');
                    sb.append("\n【判断】\n");
                    if (objectCount == 0) {
                        sb.append("对象数为 0，文件可能严重损坏。\n");
                    } else if (streams == 0) {
                        sb.append("没有任何内容流，可能是空文档或已损坏。\n");
                    } else {
                        sb.append("结构可正常解析，未发现明显损坏。\n");
                    }
                    ctx.setResult(sb.toString());
                    ctx.report(100, "结构探查完成");
                }
            });

    // ---------- 辅助 ----------

    private static final class FontInfo {
        final String name;
        final String type;
        boolean embedded;
        int count;
        final List<Integer> pages = new java.util.ArrayList<>();

        FontInfo(String name, String type) {
            this.name = name;
            this.type = type;
        }
    }

    private static int countOutline(org.apache.pdfbox.pdmodel.interactive.documentnavigation.outline.PDOutlineItem first, int depth) {
        if (first == null || depth > 50) {
            return 0;
        }
        int n = 1;
        var next = first.getNextSibling();
        while (next != null) {
            n += 1 + countOutline(next.getFirstChild(), depth + 1);
            next = next.getNextSibling();
        }
        return n;
    }

    private static String describeSize(PDRectangle b) {
        float w = Math.round(b.getWidth());
        float h = Math.round(b.getHeight());
        String name = "";
        if (Math.abs(b.getWidth() - PDRectangle.A4.getWidth()) < 2 && Math.abs(b.getHeight() - PDRectangle.A4.getHeight()) < 2) {
            name = " (A4)";
        } else if (Math.abs(b.getWidth() - PDRectangle.LETTER.getWidth()) < 2) {
            name = " (Letter)";
        } else if (Math.abs(b.getWidth() - PDRectangle.A3.getWidth()) < 2) {
            name = " (A3)";
        }
        return String.format("%.0f×%.0fpt%s", w, h, name);
    }

    private static String nullSafe(Object o) {
        return o == null || o.toString().isBlank() ? "（空）" : o.toString();
    }
}
