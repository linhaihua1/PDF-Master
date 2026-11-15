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

/** 注释类能力：导出清单、批量清除、添加便签。 */
public final class AnnotOps {

    private AnnotOps() {
    }

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
                            continue;
                        }
                        for (PDAnnotation a : list) {
                            n++;
                            csv.append(i + 1).append(',')
                                    .append(esc(a.getSubtype())).append(',')
                                    .append(esc(nullSafe(a.getAnnotationName()))).append(',')
                                    .append(esc(nullSafe(a.getContents()))).append(',')
                                    .append(esc(nullSafe(a.getModifiedDate()))).append('\n');
                        }
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

    public static final Capability STRIP = new Capability(
            "annot.strip", "清除注释", "内容编辑",
            "删除全部注释，或只删某一类（高亮、便签、图章等）。打印/归档前常用。",
            List.of(Param.choice("kind", "清除范围", "全部注释", "全部注释", "仅高亮标注", "仅便签评论", "仅图章")),
            ctx -> {
                String kind = ctx.str("kind", "全部注释");
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
                        note.setTitlePopup(author);
                        note.setName(author);
                        note.setPrinted(true);
                        float w = 20f;
                        float h = 20f;
                        float x = box.getLowerLeftX() + box.getWidth() * px / 100f - w / 2;
                        float y = box.getUpperRightY() - box.getHeight() * py / 100f - h / 2;
                        note.setRectangle(new PDRectangle(x, y, w, h));
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

    static String nullSafe(String s) {
        return s == null ? "" : s;
    }

    static String esc(String s) {
        if (s == null) {
            return "";
        }
        String v = s.replace("\r", " ").replace("\n", " ").replace("\"", "\"\"");
        return v.contains(",") || v.contains("\"") ? "\"" + v + "\"" : v;
    }
}
