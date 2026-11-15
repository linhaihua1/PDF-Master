package io.pdfkit.ops;

import io.pdfkit.core.Capability;
import io.pdfkit.core.Ctx;
import io.pdfkit.core.Err;
import io.pdfkit.core.Param;
import io.pdfkit.util.PdfUtil;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.PDResources;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.graphics.image.JPEGFactory;
import org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject;
import org.apache.pdfbox.pdmodel.common.PDStream;
import org.apache.pdfbox.rendering.ImageType;
import org.apache.pdfbox.rendering.PDFRenderer;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.pdfbox.text.TextPosition;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * 涂黑（保密打码）。安全模式把目标页栅格化后填黑，原文字被彻底丢弃，
 * 复制/搜索都无法还原 —— 这是与"画个黑框盖住"的本质区别。
 */
public final class RedactOps {

    private RedactOps() {
    }

    private record Rect(int page, float x, float y, float w, float h) {
    }

    public static final Capability AUTO = new Capability(
            "security.redact-auto", "自动涂黑（敏感信息）", "安全与合规",
            "按正则识别身份证、手机号、银行卡等敏感文本并整段涂黑。处理过的页会转为图片，原文字不可恢复。",
            List.of(
                    Param.bool("idCard", "身份证号", true, "18 位身份证"),
                    Param.bool("phone", "手机号", true, "11 位手机号"),
                    Param.bool("bankCard", "银行卡号", false, "16-19 位数字"),
                    Param.bool("email", "邮箱", false, ""),
                    Param.text("custom", "自定义正则", "", "留空则不启用"),
                    Param.num("dpi", "栅格化清晰度", "200", "150-300")
            ),
            ctx -> {
                List<Pattern> patterns = new ArrayList<>();
                if (ctx.bool("idCard", true)) {
                    patterns.add(Pattern.compile("\\d{6}(19|20)\\d{2}(0[1-9]|1[0-2])(0[1-9]|[12]\\d|3[01])\\d{3}[\\dXx]"));
                }
                if (ctx.bool("phone", true)) {
                    patterns.add(Pattern.compile("1[3-9]\\d{9}"));
                }
                if (ctx.bool("bankCard", false)) {
                    patterns.add(Pattern.compile("\\b\\d{16,19}\\b"));
                }
                if (ctx.bool("email", false)) {
                    patterns.add(Pattern.compile("[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}"));
                }
                String custom = ctx.str("custom");
                if (!custom.isBlank()) {
                    try {
                        patterns.add(Pattern.compile(custom));
                    } catch (Exception e) {
                        throw new Err("ERR_BAD_REGEX", "自定义正则无效：" + e.getMessage(), e);
                    }
                }
                if (patterns.isEmpty()) {
                    throw new Err("ERR_BAD_PARAM", "至少启用一种识别规则");
                }
                int dpi = ctx.intVal("dpi", 200, 150, 300);

                try (PDDocument doc = PdfUtil.load(ctx.input(), ctx.str("password"))) {
                    ctx.report(10, "扫描文本");
                    RedactStripper stripper = new RedactStripper(patterns);
                    stripper.setSortByPosition(true);
                    stripper.getText(doc);

                    if (stripper.hits.isEmpty()) {
                        ctx.setResult("没有识别到匹配的敏感信息（共扫描 " + doc.getNumberOfPages() + " 页）。");
                        ctx.report(100, "未发现敏感信息");
                        return;
                    }
                    Map<Integer, List<Rect>> byPage = new HashMap<>();
                    for (Rect r : stripper.hits) {
                        byPage.computeIfAbsent(r.page, k -> new ArrayList<>()).add(r);
                    }
                    ctx.report(35, "命中 " + stripper.hits.size() + " 处，开始涂黑 " + byPage.size() + " 页");
                    rasterizeAndRedact(doc, byPage, dpi, ctx);

                    Path out = ctx.outDerived("_已涂黑", ".pdf");
                    PdfUtil.save(doc, out);
                    ctx.addOutput(out);
                    ctx.setResult("已涂黑 " + stripper.hits.size() + " 处，涉及 " + byPage.size()
                            + " 页。这些页已转为图片，原文字不可复制、不可搜索。");
                    ctx.report(100, "涂黑完成：" + stripper.hits.size() + " 处");
                }
            });

    public static final Capability AREA = new Capability(
            "security.redact-area", "手动涂黑（区域）", "安全与合规",
            "按百分比坐标涂黑指定区域。坐标以左上角为原点，x/y/宽/高均为 0-100 的百分比。",
            List.of(
                    Param.text("pages", "作用页", "1", "如 1,3-5"),
                    Param.num("x", "起点 X (%)", "10", "0-100"),
                    Param.num("y", "起点 Y (%)", "10", "0-100"),
                    Param.num("w", "宽度 (%)", "40", "1-100"),
                    Param.num("h", "高度 (%)", "6", "1-100"),
                    Param.num("dpi", "栅格化清晰度", "200", "150-300")
            ),
            ctx -> {
                int dpi = ctx.intVal("dpi", 200, 150, 300);
                float xPct = ctx.intVal("x", 10, 0, 100) / 100f;
                float yPct = ctx.intVal("y", 10, 0, 100) / 100f;
                float wPct = ctx.intVal("w", 40, 1, 100) / 100f;
                float hPct = ctx.intVal("h", 6, 1, 100) / 100f;

                try (PDDocument doc = PdfUtil.load(ctx.input(), ctx.str("password"))) {
                    List<Integer> pages = PdfUtil.parsePageSpec(ctx.str("pages", "1"), doc.getNumberOfPages());
                    Map<Integer, List<Rect>> byPage = new HashMap<>();
                    for (int p : pages) {
                        PDPage page = doc.getPage(p - 1);
                        PDRectangle box = page.getCropBox();
                        float x = box.getLowerLeftX() + box.getWidth() * xPct;
                        float w = box.getWidth() * wPct;
                        float h = box.getHeight() * hPct;
                        // 输入 y 以顶部为原点，转成 PDF 底部原点
                        float y = box.getUpperRightY() - box.getHeight() * yPct - h;
                        byPage.computeIfAbsent(p, k -> new ArrayList<>()).add(new Rect(p, x, y, w, h));
                    }
                    rasterizeAndRedact(doc, byPage, dpi, ctx);
                    Path out = ctx.outDerived("_已涂黑", ".pdf");
                    PdfUtil.save(doc, out);
                    ctx.addOutput(out);
                    ctx.setResult("已在 " + pages.size() + " 页涂黑指定区域，原文字已丢弃。");
                    ctx.report(100, "涂黑完成");
                }
            });

    /** 把带涂黑矩形的页面栅格化，填黑后替换页面内容。 */
    static void rasterizeAndRedact(PDDocument doc, Map<Integer, List<Rect>> byPage, int dpi, Ctx ctx) throws IOException {
        PDFRenderer renderer = new PDFRenderer(doc);
        List<Integer> pageNos = new ArrayList<>(byPage.keySet());
        pageNos.sort(Integer::compareTo);
        int i = 0;
        for (int pageNo : pageNos) {
            ctx.checkCancel();
            ctx.report(40 + i++ * 55 / pageNos.size(), "涂黑第 " + pageNo + " 页");
            PDPage page = doc.getPage(pageNo - 1);
            PDRectangle box = page.getCropBox();
            BufferedImage img = renderer.renderImageWithDPI(pageNo - 1, dpi, ImageType.RGB);
            Graphics2D g = img.createGraphics();
            g.setColor(Color.BLACK);
            double sx = img.getWidth() / (double) box.getWidth();
            double sy = img.getHeight() / (double) box.getHeight();
            for (Rect r : byPage.get(pageNo)) {
                int x1 = (int) Math.floor((r.x - box.getLowerLeftX()) * sx);
                int y1 = (int) Math.floor((box.getUpperRightY() - (r.y + r.h)) * sy);
                int w1 = (int) Math.ceil(r.w * sx);
                int h1 = (int) Math.ceil(r.h * sy);
                g.fillRect(x1, y1, Math.max(1, w1), Math.max(1, h1));
            }
            g.dispose();

            // 清空原内容流：这一步才是"真删除"
            page.setContents(new PDStream(doc));
            page.setResources(new PDResources());
            PDImageXObject pdImg = JPEGFactory.createFromImage(doc, img, 0.85f);
            try (PDPageContentStream cs = new PDPageContentStream(doc, page,
                    PDPageContentStream.AppendMode.APPEND, true, true)) {
                cs.drawImage(pdImg, box.getLowerLeftX(), box.getLowerLeftY(), box.getWidth(), box.getHeight());
            }
        }
    }

    /** 收集敏感文本的矩形位置（PDF 坐标）。 */
    static final class RedactStripper extends PDFTextStripper {

        final List<Rect> hits = new ArrayList<>();
        private final List<Pattern> patterns;

        RedactStripper(List<Pattern> patterns) throws IOException {
            super();
            this.patterns = patterns;
        }

        @Override
        protected void writeString(String text, List<TextPosition> positions) throws IOException {
            if (positions == null || positions.isEmpty()) {
                return;
            }
            String flat = text.replaceAll("\\s+", "");
            boolean matched = patterns.stream().anyMatch(p -> p.matcher(flat).find());
            if (!matched) {
                return;
            }
            PDPage page = getCurrentPage();
            float pageHeight = page.getCropBox().getHeight();
            float baseY = page.getCropBox().getUpperRightY();
            float minX = Float.MAX_VALUE;
            float maxX = -Float.MAX_VALUE;
            float minY = Float.MAX_VALUE;
            float maxY = -Float.MAX_VALUE;
            for (TextPosition tp : positions) {
                float x = tp.getXDirAdj();
                float y = tp.getYDirAdj();
                float w = tp.getWidthDirAdj();
                float h = tp.getHeightDir();
                minX = Math.min(minX, x);
                maxX = Math.max(maxX, x + w);
                minY = Math.min(minY, y);
                maxY = Math.max(maxY, y + h);
            }
            if (minX > maxX) {
                return;
            }
            // TextPosition 以页面顶部为原点，转成 PDF 底部原点
            float pdfY = page.getCropBox().getUpperRightY() - maxY;
            float rectW = maxX - minX;
            float rectH = Math.max(2f, maxY - minY);
            hits.add(new Rect(getCurrentPageNo(),
                    minX + page.getCropBox().getLowerLeftX(), pdfY, rectW, rectH));
        }
    }
}
