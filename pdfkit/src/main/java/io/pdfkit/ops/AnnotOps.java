package io.pdfkit.ops;

import io.pdfkit.core.Capability;
import io.pdfkit.core.Ctx;
import io.pdfkit.core.Err;
import io.pdfkit.core.Param;
import io.pdfkit.util.PdfUtil;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.interactive.annotation.PDAnnotation;
import org.apache.pdfbox.pdmodel.interactive.annotation.PDAnnotationText;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * 注释类能力：导出清单、批量清除、添加便签。
 *
 * <p>这里的「注释」指 PDF 批注对象（Annotation）——高亮、便签、图章、手绘那类
 * 附着在页面上的交互元素，与代码注释无关。
 *
 * <p><b>批注存在于页面而不是文档</b>：每个 {@link PDPage} 有独立的注释列表，
 * 所以遍历、清除都得逐页进行。PDFBox 没有「文档级批注集合」这个概念。
 *
 * <p>三种注释子类型在本类里反复出现，记住对应关系：
 * {@code Highlight}=高亮、{@code Text}=便签（页面边上的小图标）、{@code Stamp}=图章。
 */
public final class AnnotOps {

    /** 工具类，禁止实例化。 */
    private AnnotOps() {
    }

    /**
     * 导出全部注释为 CSV：页码、类型、作者、内容、创建时间。
     *
     * <p>没有注释时不抛错，只提示「没有任何注释」——「这份文件没有批注」本身
     * 就是有效结论，不该当成失败。
     *
     * <p>单页 {@code getAnnotations()} 失败时跳过该页继续：批注字典损坏的页面会抛异常，
     * 但其它页的批注仍然有导出价值。
     */
    public static final Capability EXPORT = new Capability(
            "annot.export", "导出注释清单", "内容编辑",
            "把全部批注、便签、高亮导出成 CSV，含页码、类型、作者、内容、创建时间。",
            List.of(Param.bool("exportCsv", "导出 CSV 文件", true, "不勾选则只在窗口展示")),
            ctx -> {
                try (PDDocument doc = PdfUtil.load(ctx.input(), ctx.str("password"))) {
                    ctx.report(10, "扫描注释");
                    StringBuilder csv = new StringBuilder("页码,类型,作者,内容,创建时间\n");
                    int n = 0;
                    int pages = doc.getNumberOfPages();
                    for (int i = 0; i < pages; i++) {
                        ctx.checkCancel();
                        PDPage page = doc.getPage(i);
                        List<PDAnnotation> list;
                        try {
                            list = page.getAnnotations();
                        } catch (Exception e) {
                            // 该页批注字典损坏，跳过但保留其它页的结果
                            continue;
                        }
                        for (PDAnnotation a : list) {
                            n++;
                            csv.append(i + 1).append(',')
                                    // 子类型是 PDF 规范里的英文标识（Highlight/Text/Stamp），原样输出便于对照规范
                                    .append(esc(a.getSubtype())).append(',')
                                    .append(esc(nullSafe(a.getAnnotationName()))).append(',')
                                    .append(esc(nullSafe(a.getContents()))).append(',')
                                    .append(esc(nullSafe(a.getModifiedDate()))).append('\n');
                        }
                        // 每 20 页刷一次进度：逐页刷会让界面回调过于频繁，反而拖慢扫描
                        if (i % 20 == 0) {
                            ctx.report(10 + i * 80 / Math.max(1, pages), "第 " + (i + 1) + " 页");
                        }
                    }
                    if (n == 0) {
                        ctx.setResult("这个 PDF 没有任何注释。");
                        ctx.report(100, "无注释");
                        return;
                    }
                    ctx.setResult("共 " + n + " 条注释，清单已导出。");
                    if (ctx.bool("exportCsv", true)) {
                        Path out = ctx.outDerived("_注释清单", ".csv");
                        Files.writeString(out, csv.toString(), StandardCharsets.UTF_8);
                        ctx.addOutput(out);
                    }
                    ctx.report(100, "共 " + n + " 条注释");
                }
            });

    /**
     * 清除注释，可只清某一类。
     *
     * <p>实现是「重建每页的注释列表」——把要保留的收进新列表，再整体
     * {@code setAnnotations(keep)}。PDFBox 没有单个批注的删除方法，
     * 只能替换整个列表。
     *
     * <p>只在确实删掉了东西时才调用 {@code setAnnotations}：无谓地对每页都重设一次
     * 会让 PDF 里多出不必要的变更，也可能影响原本正常的批注引用。
     *
     * <p>一条都没删到时抛 {@code ERR_NO_MATCH}：用户选了「仅图章」但文件里只有高亮，
     * 应该明确告知没匹配上，而不是默默生成一个内容相同的副本。
     */
    public static final Capability STRIP = new Capability(
            "annot.strip", "清除注释", "内容编辑",
            "删除全部注释，或只删某一类（高亮、便签、图章等）。打印/归档前常用。",
            List.of(Param.choice("kind", "清除范围", "全部注释", "全部注释", "仅高亮标注", "仅便签评论", "仅图章")),
            ctx -> {
                String kind = ctx.str("kind", "全部注释");
                // want 为 null 表示清除全部（不做子类型过滤）
                String want;
                if ("仅高亮标注".equals(kind)) {
                    want = "Highlight";
                } else if ("仅便签评论".equals(kind)) {
                    want = "Text";
                } else if ("仅图章".equals(kind)) {
                    want = "Stamp";
                } else {
                    want = null;
                }
                try (PDDocument doc = PdfUtil.load(ctx.input(), ctx.str("password"))) {
                    int removed = 0;
                    int pages = doc.getNumberOfPages();
                    for (int i = 0; i < pages; i++) {
                        ctx.checkCancel();
                        ctx.report(i * 95 / Math.max(1, pages), "第 " + (i + 1) + " 页");
                        List<PDAnnotation> list;
                        try {
                            list = doc.getPage(i).getAnnotations();
                        } catch (Exception e) {
                            continue;
                        }
                        List<PDAnnotation> keep = new ArrayList<>();
                        for (PDAnnotation a : list) {
                            // 忽略大小写比较：不同生成器写出的子类型大小写不一致
                            boolean hit = want == null || want.equalsIgnoreCase(a.getSubtype());
                            if (hit) {
                                removed++;
                            } else {
                                keep.add(a);
                            }
                        }
                        if (keep.size() != list.size()) {
                            doc.getPage(i).setAnnotations(keep);
                        }
                    }
                    if (removed == 0) {
                        throw new Err("ERR_NO_MATCH", "没有找到符合条件的注释，文件未改动");
                    }
                    Path out = ctx.outDerived("_已清注释", ".pdf");
                    PdfUtil.save(doc, out);
                    ctx.addOutput(out);
                    ctx.report(100, "已删除 " + removed + " 条注释");
                }
            });

    /**
     * 在指定页的指定位置插入便签注释。
     *
     * <p><b>坐标换算的两处坑</b>：
     * <ol>
     *   <li><b>Y 轴方向相反</b>：用户填的「纵向位置 90%」是「从顶边往下 90%」的直觉，
     *       而 PDF 坐标原点在左下角、Y 向上增长。所以要用
     *       {@code box.getUpperRightY() - box.getHeight() * py / 100} 翻转。</li>
     *   <li><b>锚点居中</b>：便签图标有 20×20 的大小，按百分比算出的点是图标中心，
     *       所以分别减去 {@code w/2} 和 {@code h/2}。不居中会让图标偏出用户预期的位置。</li>
     * </ol>
     *
     * <p>{@code setPrinted(true)} 让批注在打印时也出现——PDF 规范里批注默认不一定打印，
     * 用户加便签说「请复核此处」显然是希望打印件上能看到。
     *
     * <p>位置基于 {@code CropBox} 而不是 {@code MediaBox}：用户看到的是裁剪后的区域，
     * 按可见区域定位才符合直觉。
     */
    public static final Capability ADD = new Capability(
            "annot.add", "添加便签", "内容编辑",
            "在指定页的指定位置插入一条便签注释，可填内容与作者。",
            List.of(
                    Param.text("pages", "作用页", "1", "留空=全部页，或 1-3,5"),
                    Param.num("x", "横向位置(%)", "85", "0=左边缘，100=右边缘"),
                    Param.num("y", "纵向位置(%)", "90", "0=顶边，100=底边"),
                    Param.text("text", "注释内容", "请复核此处"),
                    Param.text("author", "作者", "PdfKit")
            ),
            ctx -> {
                String text = ctx.str("text", "请复核此处");
                String author = ctx.str("author", "PdfKit");
                int px = ctx.intVal("x", 85, 0, 100);
                int py = ctx.intVal("y", 90, 0, 100);
                try (PDDocument doc = PdfUtil.load(ctx.input(), ctx.str("password"))) {
                    List<Integer> pages = PdfUtil.parsePageSpec(ctx.str("pages"), doc.getNumberOfPages());
                    int i = 0;
                    for (int p : pages) {
                        ctx.checkCancel();
                        ctx.report(5 + i++ * 90 / pages.size(), "第 " + p + " 页");
                        PDPage page = doc.getPage(p - 1);
                        PDRectangle box = page.getCropBox();
                        PDAnnotationText note = new PDAnnotationText();
                        note.setContents(text);
                        // 标题与名称都设成作者：不同阅读器显示的位置不一样，
                        // 一个显示在弹窗标题栏，一个显示在批注属性里
                        note.setTitlePopup(author);
                        note.setName(author);
                        // 让批注随打印输出
                        note.setPrinted(true);
                        float w = 20f;
                        float h = 20f;
                        float x = box.getLowerLeftX() + box.getWidth() * px / 100f - w / 2;
                        // Y 轴翻转：PDF 原点在左下角，用户直觉是从上往下算
                        float y = box.getUpperRightY() - box.getHeight() * py / 100f - h / 2;
                        note.setRectangle(new PDRectangle(x, y, w, h));
                        // 略微透明，避免完全遮住下面的文字
                        note.setConstantOpacity(0.9f);
                        page.getAnnotations().add(note);
                    }
                    Path out = ctx.outDerived("_已加便签", ".pdf");
                    PdfUtil.save(doc, out);
                    ctx.addOutput(out);
                    ctx.report(100, "已在 " + pages.size() + " 页添加便签");
                }
            });

    // ---------- 辅助 ----------

    /** null 转空串，避免 CSV 里出现字面量 "null"。 */
    static String nullSafe(String s) {
        return s == null ? "" : s;
    }

    /**
     * CSV 转义：<b>仅在必要时加引号</b>（含逗号或双引号时）。
     *
     * <p>与 {@code FormOps.esc} 的策略不同——那个无条件加引号。两种都合法，
     * 但这里的 CSV 主要给人看，全字段带引号会让文件显得很吵。
     *
     * <p>换行必须替换成空格：批注内容常有多行，直接写进 CSV 会把一行拆成两行，
     * 整个文件的列对应关系就全乱了。
     */
    static String esc(String s) {
        if (s == null) {
            return "";
        }
        String v = s.replace("\r", " ").replace("\n", " ").replace("\"", "\"\"");
        return v.contains(",") || v.contains("\"") ? "\"" + v + "\"" : v;
    }
}
