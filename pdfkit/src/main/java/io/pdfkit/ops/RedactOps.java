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

    /**
     * 把带涂黑矩形的页面栅格化，填黑后替换页面内容。
     *
     * <p><b>这是「真涂黑」与「假涂黑」的分界线</b>。常见的错误做法是在页面上画个黑色矩形盖住文字——
     * 视觉上遮住了，但文字仍以文本对象形式存在于内容流里，复制、搜索、文本提取都能还原，
     * 甚至用编辑器删掉那个矩形就全暴露了。真正安全的做法是：把整页渲染成像素图 → 在像素图上填黑 →
     * 用这张图<b>替换掉</b>原页面内容。原文字对象因此彻底消失。
     *
     * <p>关键的三步（少任一步都会留下可还原的残迹）：
     * <ol>
     *   <li>{@code page.setContents(new PDStream(doc))} —— 清空原内容流，这一步才是"真删除"；</li>
     *   <li>{@code page.setResources(new PDResources())} —— 清空资源字典，
     *       否则字体、图片对象仍作为资源挂在页面上，虽不可见但能被提取；</li>
     *   <li>再把填好黑块的位图作为新内容画进去。</li>
     * </ol>
     *
     * <p><b>代价不可逆</b>：栅格化后该页不再有文字层，无法选中、搜索、复制，
     * 打印清晰度也受 DPI 限制。所以只对被标记的页做栅格化，未涉及的页原样保留。
     *
     * <p>坐标换算：PDF 坐标原点在左下、Y 向上；位图坐标原点在左上、Y 向下。
     * 所以 Y 方向要用 {@code box.getUpperRightY() - (r.y + r.h)} 翻转，
     * 且要减去 CropBox 的左下角偏移（页面可能被裁剪过，原点不在 0,0）。
     */
    static void rasterizeAndRedact(PDDocument doc, Map<Integer, List<Rect>> byPage, int dpi, Ctx ctx) throws IOException {
        PDFRenderer renderer = new PDFRenderer(doc);
        List<Integer> pageNos = new ArrayList<>(byPage.keySet());
        // 按页号排序只是为了进度条按顺序推进，不影响正确性
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
            // 页面尺寸（pt）到位图尺寸（px）的换算系数，横纵分别算以适配非等比渲染
            double sx = img.getWidth() / (double) box.getWidth();
            double sy = img.getHeight() / (double) box.getHeight();
            for (Rect r : byPage.get(pageNo)) {
                // 四舍五入方向刻意不同：起点 floor、尺寸 ceil，保证填黑区域只会比标记框
                // 略大不会略小——漏出一个像素就可能泄露敏感信息
                int x1 = (int) Math.floor((r.x - box.getLowerLeftX()) * sx);
                int y1 = (int) Math.floor((box.getUpperRightY() - (r.y + r.h)) * sy);
                int w1 = (int) Math.ceil(r.w * sx);
                int h1 = (int) Math.ceil(r.h * sy);
                g.fillRect(x1, y1, Math.max(1, w1), Math.max(1, h1));
            }
            g.dispose();

            // 清空原内容流：这一步才是"真删除"。只画黑框而不清内容流的话，
            // 文字对象仍在文件里，复制/搜索/提取都能拿到原文。
            page.setContents(new PDStream(doc));
            // 资源字典也要清：字体文件、图像对象若不摘除，仍可从资源里提取出原文渲染结果
            page.setResources(new PDResources());
            PDImageXObject pdImg = JPEGFactory.createFromImage(doc, img, 0.85f);
            try (PDPageContentStream cs = new PDPageContentStream(doc, page,
                    PDPageContentStream.AppendMode.APPEND, true, true)) {
                // 位图按原始页面尺寸铺满，保持阅读比例不变
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

        /**
         * 判断当前行是否命中敏感模式，命中则记录<b>整行</b>的包围盒。
         *
         * <p><b>为什么先去掉所有空白再匹配</b>：手机号、身份证常被排版拆成
         * 「138 0013 8000」，按原样匹配会漏。去掉空白后 {@code \d{11}} 才连得上。
         * 因为这里只做「是否命中」的布尔判断，不依赖匹配位置，所以扁平化不影响正确性。
         *
         * <p><b>命中后涂的是整行而不是命中片段</b>：定位到字符级需要把匹配区间映射回
         * TextPosition 下标，实现复杂且容易错位；而涂黑的原则是「宁可多涂，不可漏涂」。
         * 代价是一行里有个手机号，同行的其他文字也会被遮住——这是安全优先的取舍，
         * 用户可以在结果摘要里看到具体涂了哪几页，据此判断是否要改用「区域涂黑」。
         *
         * <p>{@code Math.max(2f, maxY - minY)} 给高度兜底 2pt：纯空格行或字体高度异常时
         * 高度可能算成 0，画不出可见黑块，等于没涂。
         */
        @Override
        protected void writeString(String text, List<TextPosition> positions) throws IOException {
            if (positions == null || positions.isEmpty()) {
                return;
            }
            String flat = text.replaceAll("\\s+", "");
            // 只判断是否命中，不取匹配区间——理由见本方法 javadoc
            boolean matched = patterns.stream().anyMatch(p -> p.matcher(flat).find());
            if (!matched) {
                return;
            }
            PDPage page = getCurrentPage();
            // 死代码：pageHeight 与 baseY 声明后都没有被引用（下方直接调 getCropBox()
            // 再取 UpperRightY），可以安全删除这两个局部变量
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
            // TextPosition 以页面顶部为原点，转成 PDF 底部原点：
            // Y_pdf = 页面顶部 - 包围盒底边(即 maxY)，而不是减 minY
            float pdfY = page.getCropBox().getUpperRightY() - maxY;
            float rectW = maxX - minX;
            // 高度兜底 2pt，理由见本方法 javadoc
            float rectH = Math.max(2f, maxY - minY);
            // 横坐标要补上 CropBox 的左下角偏移：被裁剪过的页面原点不在 0,0
            hits.add(new Rect(getCurrentPageNo(),
                    minX + page.getCropBox().getLowerLeftX(), pdfY, rectW, rectH));
        }
    }
}
