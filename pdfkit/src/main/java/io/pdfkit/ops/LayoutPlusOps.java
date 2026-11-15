package io.pdfkit.ops;

import io.pdfkit.core.Capability;
import io.pdfkit.core.Ctx;
import io.pdfkit.core.Param;
import io.pdfkit.util.PdfUtil;
import org.apache.pdfbox.multipdf.LayerUtility;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.graphics.form.PDFormXObject;
import org.apache.pdfbox.util.Matrix;

import java.awt.Color;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * 拼版类能力：海报分块、小册子拼版。
 *
 * <p>比 {@link LayoutOps} 的 N-up 更专门的两种印刷排版：
 * <ul>
 *   <li><b>海报分块</b> —— 把一页放大后切成 N×M 块，分别打到多张 A4 上再拼起来。
 *       每块带裁切标记与重叠区，方便手工对齐粘贴。</li>
 *   <li><b>小册子拼版</b> —— 骑马钉（saddle stitch）装订：一张纸正反两面共 4 页，
 *       按特定顺序排列，折起来就是一本页码连续的小册子。</li>
 * </ul>
 *
 * <p>两项都用 Form XObject 贴页（原理见 {@link LayoutOps} 的类注释）。
 */
public final class LayoutPlusOps {

    private LayoutPlusOps() {
    }

    public static final Capability POSTER = new Capability(
            "layout.poster", "海报分块打印", "页面组织",
            "把一页放大切成多张纸，打印后拼接成海报。每块可留重叠边便于对齐。",
            List.of(
                    Param.num("cols", "横向切几块", "2", "1-5"),
                    Param.num("rows", "纵向切几块", "2", "1-5"),
                    Param.num("overlap", "重叠(mm)", "5", "0-20，方便粘贴对齐"),
                    Param.bool("guide", "打印对位标记", true, "在四角画裁切线"),
                    Param.text("pages", "作用页", "1", "留空=全部页，或 1-3,5")
            ),
            ctx -> {
                int cols = ctx.intVal("cols", 2, 1, 5);
                int rows = ctx.intVal("rows", 2, 1, 5);
                float overlap = ctx.intVal("overlap", 5, 0, 20) * 72f / 25.4f;
                boolean guide = ctx.bool("guide", true);

                try (PDDocument src = PdfUtil.load(ctx.input(), ctx.str("password"))) {
                    List<Integer> pages = PdfUtil.parsePageSpec(ctx.str("pages"), src.getNumberOfPages());
                    Path out = ctx.outDerived("_海报", ".pdf");
                    try (PDDocument dst = new PDDocument()) {
                        LayerUtility lu = new LayerUtility(dst);
                        int done = 0;
                        for (int p : pages) {
                            ctx.checkCancel();
                            ctx.report(5 + done * 90 / (pages.size() * cols * rows), "第 " + p + " 页");
                            PDPage page = src.getPage(p - 1);
                            PDRectangle box = page.getCropBox();
                            float pw = box.getWidth();
                            float ph = box.getHeight();

                            // 每块的实际内容尺寸（含重叠边）
                            float cw = pw / cols;
                            float ch = ph / rows;

                            PDFormXObject form = lu.importPageAsForm(src, p - 1);
                            for (int r = 0; r < rows; r++) {
                                for (int c = 0; c < cols; c++) {
                                    done++;
                                    // 块在源页中的左下角
                                    float bx = c * cw - (c > 0 ? overlap : 0);
                                    float by = ph - (r + 1) * ch - (r < rows - 1 ? overlap : 0);
                                    float bw = cw + (c > 0 ? overlap : 0) + (c < cols - 1 ? overlap : 0);
                                    float bh = ch + (r < rows - 1 ? overlap : 0) + (r > 0 ? overlap : 0);

                                    PDPage np = new PDPage(new PDRectangle(bw, bh));
                                    dst.addPage(np);
                                    try (PDPageContentStream cs = new PDPageContentStream(dst, np)) {
                                        cs.saveGraphicsState();
                                        cs.addRect(0, 0, bw, bh);
                                        cs.clip();
                                        // 源页坐标 → 块局部坐标
                                        cs.transform(new Matrix(1, 0, 0, 1, -bx, -by));
                                        // 源页可能自带 cropBox 偏移
                                        cs.transform(new Matrix(1, 0, 0, 1,
                                                -box.getLowerLeftX(), -box.getLowerLeftY()));
                                        cs.drawForm(form);
                                        cs.restoreGraphicsState();

                                        if (guide) {
                                            drawGuides(cs, bw, bh, overlap);
                                        }
                                    }
                                }
                            }
                        }
                        PdfUtil.save(dst, out);
                    }
                    ctx.addOutput(out);
                    ctx.report(100, "切成 " + (pages.size() * cols * rows) + " 块");
                }
            });

    public static final Capability BOOKLET = new Capability(
            "layout.booklet", "小册子拼版", "页面组织",
            "按骑马钉顺序把页面排成双页，双面打印后对折即成本子。页数会自动补空白到 4 的倍数。",
            List.of(
                    Param.num("gap", "中缝宽度(mm)", "0", "装订留白，0-20"),
                    Param.bool("border", "画中缝参考线", true, "")
            ),
            ctx -> {
                float gap = ctx.intVal("gap", 0, 0, 20) * 72f / 25.4f;
                boolean border = ctx.bool("border", true);
                try (PDDocument src = PdfUtil.load(ctx.input(), ctx.str("password"))) {
                    int total = src.getNumberOfPages();
                    if (total < 2) {
                        throw new io.pdfkit.core.Err("ERR_TOO_FEW", "小册子至少需要 2 页");
                    }
                    // 补齐到 4 的倍数
                    int padded = ((total + 3) / 4) * 4;
                    PDRectangle first = src.getPage(0).getCropBox();
                    float pw = first.getWidth();
                    float ph = first.getHeight();

                    List<int[]> sheets = bookletOrder(padded);
                    Path out = ctx.outDerived("_小册子", ".pdf");
                    try (PDDocument dst = new PDDocument()) {
                        LayerUtility lu = new LayerUtility(dst);
                        int i = 0;
                        for (int[] pair : sheets) {
                            ctx.checkCancel();
                            ctx.report(5 + i++ * 90 / sheets.size(), "拼第 " + i + " 张");
                            PDPage np = new PDPage(new PDRectangle(pw * 2 + gap, ph));
                            dst.addPage(np);
                            try (PDPageContentStream cs = new PDPageContentStream(dst, np)) {
                                place(dst, lu, cs, src, pair[0], 0, ph, pw);
                                place(dst, lu, cs, src, pair[1], pw + gap, ph, pw);
                                if (border && gap > 0) {
                                    cs.setStrokingColor(new Color(190, 190, 190));
                                    cs.setLineDashPattern(new float[]{3f, 3f}, 0f);
                                    cs.moveTo(pw + gap / 2, 0);
                                    cs.lineTo(pw + gap / 2, ph);
                                    cs.stroke();
                                }
                            }
                        }
                        PdfUtil.save(dst, out);
                    }
                    ctx.addOutput(out);
                    ctx.report(100, "共 " + sheets.size() + " 张纸（双面打印）");
                }
            });

    // ---------- 共用 ----------

    /**
     * 骑马钉顺序：每张纸正反两面共 4 个页码，页码从 1 开始，0 表示空白。
     *
     * <p><b>为什么要这个顺序</b>：骑马钉是把一叠纸对折、沿折线装订。折起来之后，
     * 纸的「左半边」和「右半边」会分别成为前后两页，所以一张纸上要放「最前」和「最后」两页。
     *
     * <p>以 8 页为例，正确顺序是：
     * <pre>
     * 第 1 张 正面：8 | 1     反面：2 | 7
     * 第 2 张 正面：6 | 3     反面：4 | 5
     * </pre>
     * 双指针从两端向中间夹：left 从小到大、right 从大到小，一次填满正反两面。
     * 每轮消耗 4 个页码（正面 2 个 + 反面 2 个），所以循环条件是 {@code left < right}。
     *
     * <p>页数必须是 4 的倍数，不足的部分由调用方补空白页（{@code padded} 是补齐后的页数），
     * 否则会出现「空白页被排到中间」的错位。
     *
     * @param padded 补齐到 4 的倍数之后的页数
     * @return 每张纸一组页码，{@code {左, 右}}，按 正面/反面 交替排列
     */
    static List<int[]> bookletOrder(int padded) {
        List<int[]> out = new ArrayList<>();
        int left = 1;
        int right = padded;
        while (left < right) {
            // 正面：外侧 → 内侧
            out.add(new int[]{right, left});
            left++;
            right--;
            // 反面
            out.add(new int[]{left, right});
            left++;
            right--;
        }
        return out;
    }

    static void place(PDDocument dst, LayerUtility lu, PDPageContentStream cs, PDDocument src,
                      int pageNo, float x, float pageH, float cellW) throws Exception {
        if (pageNo < 1 || pageNo > src.getNumberOfPages()) {
            return;
        }
        PDPage sp = src.getPage(pageNo - 1);
        PDRectangle box = sp.getCropBox();
        PDFormXObject form = lu.importPageAsForm(src, pageNo - 1);
        float scale = Math.min(cellW / box.getWidth(), pageH / box.getHeight());
        float dw = box.getWidth() * scale;
        float dh = box.getHeight() * scale;
        float ox = x + (cellW - dw) / 2;
        float oy = (pageH - dh) / 2;
        cs.saveGraphicsState();
        cs.transform(new Matrix(1, 0, 0, 1, ox, oy));
        cs.transform(Matrix.getScaleInstance(scale, scale));
        cs.transform(new Matrix(1, 0, 0, 1, -box.getLowerLeftX(), -box.getLowerLeftY()));
        cs.drawForm(form);
        cs.restoreGraphicsState();
    }

    static void drawGuides(PDPageContentStream cs, float w, float h, float overlap) throws Exception {
        cs.setStrokingColor(new Color(120, 120, 120));
        cs.setLineWidth(0.4f);
        float t = 8f;
        // 四角裁切标记
        float[][] corners = {{0, 0}, {w, 0}, {0, h}, {w, h}};
        for (float[] c : corners) {
            float sx = c[0] == 0 ? 1 : -1;
            float sy = c[1] == 0 ? 1 : -1;
            cs.moveTo(c[0] + sx * 4, c[1]);
            cs.lineTo(c[0] + sx * t, c[1]);
            cs.moveTo(c[0], c[1] + sy * 4);
            cs.lineTo(c[0], c[1] + sy * t);
        }
        cs.stroke();
        if (overlap > 0) {
            cs.setStrokingColor(new Color(220, 180, 180));
            cs.setLineDashPattern(new float[]{2f, 2f}, 0f);
            cs.addRect(overlap, overlap, w - overlap * 2, h - overlap * 2);
            cs.stroke();
        }
    }
}
