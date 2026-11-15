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

/**
 * 分析类能力：文档信息、字体体检、结构探查。结果以文本返回，不产出文件。
 *
 * <p>这三项的共同特点是<b>只读不写</b>：不生成新 PDF，结论通过
 * {@code ctx.setResult(...)} 交给界面显示（字体体检另可选导出 CSV）。
 * 这类能力在 {@link io.pdfkit.core.JobQueue} 里有特殊处理——「无产物但有文字结论」
 * 也算成功，不会被判成 {@code ERR_EMPTY_OUTPUT}。
 *
 * <p>分析类能力的价值往往在「发现问题」而非「处理问题」：字体体检能提前发现
 * 拿到别的电脑上会变形的文档，结构探查能判断一份打不开的 PDF 还有没有救。
 */
public final class AnalyzeOps {

    /** 工具类，禁止实例化。 */
    private AnalyzeOps() {
    }

    /**
     * 文档基础信息：页数、纸张尺寸分布、PDF 版本、加密状态、元数据、书签与表单概况。
     *
     * <p>纸张尺寸用「统计分布」而不是逐页列出：几百页的文档逐页列表没法看，
     * 而「A4×120页；Letter×3页」这种汇总能立刻暴露「混入了异尺寸页」的问题——
     * 这正是打印前需要确认的。
     *
     * <p>交叉引用格式（xref 表 vs 交叉引用流）也报出来：PDF 1.5+ 用流式 xref，
     * 老工具读不了，遇到兼容性问题时这条信息很关键。
     */
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

                    // 按尺寸归类计数。用 LinkedHashMap 保持首次出现顺序，报告读起来更符合文档结构
                    Map<String, Integer> sizes = new LinkedHashMap<>();
                    for (PDPage p : doc.getPages()) {
                        // 用 CropBox：它是实际可见区域，比 MediaBox 更贴近用户看到的纸张
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
                        // 从第一个子节点开始递归，深度从 0 算
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

    /**
     * 字体嵌入体检：列出全部字体并标出未嵌入的项。
     *
     * <p><b>为什么这件事重要</b>：PDF 里的字体可以「嵌入」也可以只「引用名字」。
     * 只引用的字体在制作它的电脑上正常，拿到没装该字体的电脑上就会变成替代字体
     * （版面错乱）或者方框（缺字）。这是企业文档流转里最常见的问题之一，而 PDF 本身
     * 不会主动告诉你。
     *
     * <p>同一字体在多页重复出现只统计一次：用「字体名 + 实现类」做 key 聚合，
     * 累计使用次数、记录首次出现页。用 {@link TreeMap} 排序让输出稳定可复现。
     */
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
                                // 字体字典损坏，跳过这个字体但继续检查同页其它字体
                                continue;
                            }
                            if (f == null) {
                                continue;
                            }
                            // 名字 + 实现类做 key：同名但类型不同（如 Type0 vs Type1）是不同字体
                            String key = f.getName() + "|" + f.getClass().getSimpleName();
                            FontInfo fi = fonts.computeIfAbsent(key, k -> new FontInfo(f.getName(), f.getClass().getSimpleName()));
                            // 用 OR 而非覆盖：同一字体可能在某页嵌入、某页没嵌（少见但存在）
                            fi.embedded = fi.embedded || f.isEmbedded();
                            // 只记前 20 个页码，避免长文档里这个列表无限增长
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

    /**
     * 文档结构探查：统计内部对象类型、检查交叉引用健康度。
     *
     * <p>用途是「判断一份打不开或显示异常的 PDF 还有没有救」。它直接翻 COS 层
     * （PDF 的底层对象模型），不属于正常业务逻辑，只在排查时用。
     *
     * <p>判定逻辑很朴素但有实际意义：
     * <ul>
     *   <li>对象数为 0 —— 文件严重损坏，基本没救。</li>
     *   <li>没有任何内容流 —— 空文档，或者内容流全部丢失。</li>
     *   <li>其它 —— 结构能解析，问题大概在具体内容而非文件骨架。</li>
     * </ul>
     *
     * <p>遍历时单个坏对象直接跳过：损坏的 PDF 本来就可能有读不出来的对象，
     * 为了统计完整而中断整个探查没有意义。
     */
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
                            // getObjectFromPool 返回延迟引用，getObject 才真正解析
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
                    // getStartXref 是 "startxref" 关键字指向的字节偏移，文件被截断时这个值常常对不上
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

    /** 单个字体的聚合信息。可变字段多，用普通类而非 record。 */
    private static final class FontInfo {
        final String name;
        final String type;
        /** 是否至少在一处嵌入。只要有一处嵌入就算已嵌入。 */
        boolean embedded;
        /** 出现次数（按页内资源引用计）。 */
        int count;
        /** 出现页（最多记 20 个）。 */
        final List<Integer> pages = new java.util.ArrayList<>();

        FontInfo(String name, String type) {
            this.name = name;
            this.type = type;
        }
    }

    /**
     * 递归统计书签总数（含嵌套）。
     *
     * <p>深度上限 50 是防御性措施：损坏的 PDF 里书签的 {@code /Next} 或 {@code /Parent}
     * 引用可能形成环，不设上限会栈溢出直接崩掉整个能力。
     *
     * <p>计数规则：本节点算 1，然后遍历它的子节点，再走下一个兄弟节点。
     * 循环条件里的 {@code next} 是「兄弟链」，{@code getFirstChild()} 才是「下一层」。
     */
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

    /**
     * 把纸张尺寸描述成「宽×高pt (规格名)」。
     *
     * <p>容差 2pt：PDF 里的「A4」常常是 595×842 而不是精确的 595.276×841.89，
     * 精确比较会认不出来。只比宽度是因为多数情况下宽度就足以区分 A4/Letter/A3
     * （三者宽度分别是 595/612/842），比高度更省事且误判率低。
     */
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

    /** null 或空白显示成「（空）」，避免报告里出现空洞或 "null"。 */
    private static String nullSafe(Object o) {
        return o == null || o.toString().isBlank() ? "（空）" : o.toString();
    }
}
