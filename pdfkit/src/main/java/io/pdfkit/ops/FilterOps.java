package io.pdfkit.ops;

import io.pdfkit.core.Capability;
import io.pdfkit.core.Ctx;
import io.pdfkit.core.Err;
import io.pdfkit.core.Param;
import io.pdfkit.util.PdfUtil;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.text.PDFTextStripper;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/** 筛选类能力：按文本、按尺寸、删空白页。 */
public final class FilterOps {

    private FilterOps() {
    }

    public static final Capability BY_TEXT = new Capability(
            "filter.text", "按文本筛选页面", "页面组织",
            "保留（或排除）包含指定关键词/正则的页面，生成新文件。",
            List.of(
                    Param.text("pattern", "关键词或正则", "", "例如 发票|金额\\d+"),
                    Param.bool("regex", "按正则解释", false, "不勾选则按纯文本包含判断"),
                    Param.bool("exclude", "反向：排除匹配页", false, "勾选后只保留不匹配的页"),
                    Param.bool("caseSensitive", "区分大小写", false, "")
            ),
            ctx -> {
                String pat = ctx.str("pattern");
                if (pat.isBlank()) {
                    throw new Err("ERR_BAD_PARAM", "请填写要筛选的关键词");
                }
                Pattern p;
                try {
                    p = ctx.bool("regex", false)
                            ? Pattern.compile(pat, ctx.bool("caseSensitive", false) ? 0 : Pattern.CASE_INSENSITIVE)
                            : Pattern.compile(Pattern.quote(pat),
                            ctx.bool("caseSensitive", false) ? 0 : Pattern.CASE_INSENSITIVE);
                } catch (Exception e) {
                    throw new Err("ERR_BAD_REGEX", "正则无效：" + e.getMessage(), e);
                }
                boolean exclude = ctx.bool("exclude", false);
                try (PDDocument doc = PdfUtil.load(ctx.input(), ctx.str("password"))) {
                    PDFTextStripper stripper = new PDFTextStripper();
                    int total = doc.getNumberOfPages();
                    List<Integer> keep = new ArrayList<>();
                    List<Integer> hitPages = new ArrayList<>();
                    for (int i = 1; i <= total; i++) {
                        ctx.report(5 + i * 85 / total, "扫描第 " + i + "/" + total + " 页");
                        stripper.setStartPage(i);
                        stripper.setEndPage(i);
                        String text;
                        try {
                            text = stripper.getText(doc);
                        } catch (Exception e) {
                            text = "";
                        }
                        boolean hit = p.matcher(text).find();
                        if (hit) {
                            hitPages.add(i);
                        }
                        if (hit != exclude) {
                            keep.add(i);
                        }
                    }
                    if (keep.isEmpty()) {
                        throw new Err("ERR_NO_MATCH",
                                "没有页面符合条件（匹配 " + hitPages.size() + " 页 / 共 " + total + " 页），未生成文件");
                    }
                    Path out = ctx.outDerived("_筛选", ".pdf");
                    OrganizeOps.writeSubset(doc, PdfUtil.toIndices(keep), out);
                    ctx.addOutput(out);
                    ctx.setResult("匹配页：" + (hitPages.isEmpty() ? "无" : hitPages.toString())
                            + "\n保留 " + keep.size() + " 页，输出 " + out.getFileName() + "。");
                    ctx.report(100, "保留 " + keep.size() + " 页");
                }
            });

    public static final Capability BY_SIZE = new Capability(
            "filter.size", "按尺寸筛选页面", "页面组织",
            "只保留指定方向的页面（竖版/横版）或指定纸张尺寸的页面。",
            List.of(Param.choice("mode", "筛选条件", "竖版", "竖版", "横版", "A4", "Letter")),
            ctx -> {
                String mode = ctx.str("mode", "竖版");
                try (PDDocument doc = PdfUtil.load(ctx.input(), ctx.str("password"))) {
                    int total = doc.getNumberOfPages();
                    List<Integer> keep = new ArrayList<>();
                    for (int i = 1; i <= total; i++) {
                        PDPage page = doc.getPage(i - 1);
                        PDRectangle b = page.getCropBox();
                        boolean ok;
                        switch (mode) {
                            case "横版" -> ok = b.getWidth() > b.getHeight();
                            case "A4" -> ok = near(b, PDRectangle.A4) || near(b, new PDRectangle(
                                    PDRectangle.A4.getHeight(), PDRectangle.A4.getWidth()));
                            case "Letter" -> ok = near(b, PDRectangle.LETTER) || near(b, new PDRectangle(
                                    PDRectangle.LETTER.getHeight(), PDRectangle.LETTER.getWidth()));
                            default -> ok = b.getHeight() >= b.getWidth();
                        }
                        if (ok) {
                            keep.add(i);
                        }
                    }
                    if (keep.isEmpty()) {
                        throw new Err("ERR_NO_MATCH", "没有符合「" + mode + "」的页面（共 " + total + " 页）");
                    }
                    Path out = ctx.outDerived("_" + mode, ".pdf");
                    OrganizeOps.writeSubset(doc, PdfUtil.toIndices(keep), out);
                    ctx.addOutput(out);
                    ctx.report(100, "保留 " + keep.size() + " 页");
                }
            });

    public static final Capability BLANK = new Capability(
            "filter.blank", "删除空白页", "页面组织",
            "移除没有文字内容的空白页，扫描件产生的空白页也能识别。",
            List.of(
                    Param.num("minChars", "最少字符数", "3", "低于该值视为空白"),
                    Param.bool("checkImages", "把含图片的页也算非空", true, "扫描件务必勾选")
            ),
            ctx -> {
                int minChars = ctx.intVal("minChars", 3, 0, 200);
                boolean checkImages = ctx.bool("checkImages", true);
                try (PDDocument doc = PdfUtil.load(ctx.input(), ctx.str("password"))) {
                    PDFTextStripper stripper = new PDFTextStripper();
                    int total = doc.getNumberOfPages();
                    List<Integer> keep = new ArrayList<>();
                    List<Integer> blanks = new ArrayList<>();
                    for (int i = 1; i <= total; i++) {
                        ctx.report(5 + i * 85 / total, "检查第 " + i + "/" + total + " 页");
                        stripper.setStartPage(i);
                        stripper.setEndPage(i);
                        String text;
                        try {
                            text = stripper.getText(doc).trim();
                        } catch (Exception e) {
                            text = "";
                        }
                        boolean hasImage = checkImages && hasImage(doc.getPage(i - 1));
                        boolean blank = text.length() < minChars && !hasImage;
                        if (blank) {
                            blanks.add(i);
                        } else {
                            keep.add(i);
                        }
                    }
                    if (blanks.isEmpty()) {
                        ctx.setResult("没有发现空白页，共 " + total + " 页，文件未改动。");
                        ctx.report(100, "无空白页");
                        return;
                    }
                    if (keep.isEmpty()) {
                        throw new Err("ERR_DELETE_ALL", "所有页都被判定为空白，为安全起见不生成文件");
                    }
                    Path out = ctx.outDerived("_去空白", ".pdf");
                    OrganizeOps.writeSubset(doc, PdfUtil.toIndices(keep), out);
                    ctx.addOutput(out);
                    ctx.setResult("删除空白页 " + blanks.size() + " 页：" + blanks + "\n保留 " + keep.size() + " 页。");
                    ctx.report(100, "删除 " + blanks.size() + " 个空白页");
                }
            });

    private static boolean near(PDRectangle a, PDRectangle b) {
        return Math.abs(a.getWidth() - b.getWidth()) < 3 && Math.abs(a.getHeight() - b.getHeight()) < 3;
    }

    private static boolean hasImage(PDPage page) {
        try {
            var res = page.getResources();
            if (res == null) {
                return false;
            }
            for (var name : res.getXObjectNames()) {
                if (res.getXObject(name) instanceof org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject) {
                    return true;
                }
            }
        } catch (Exception ignored) {
            // 资源解析失败按无图处理
        }
        return false;
    }
}
