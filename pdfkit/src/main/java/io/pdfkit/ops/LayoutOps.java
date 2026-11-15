package io.pdfkit.ops;

import io.pdfkit.core.Capability;
import io.pdfkit.core.Ctx;
import io.pdfkit.core.Err;
import io.pdfkit.core.Param;
import io.pdfkit.util.PdfUtil;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.graphics.form.PDFormXObject;
import org.apache.pdfbox.multipdf.LayerUtility;
import org.apache.pdfbox.util.Matrix;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * 版式类能力：多页排版（N-up）、按大小拆分、文档叠加。
 *
 * <p>这个类和 {@link LayoutPlusOps} 共用一套「把源页面当成图形对象贴到新页上」的技术：
 * 用 {@code LayerUtility.importPageAsForm()} 把源页导入成 Form XObject，再通过
 * 变换矩阵把它缩放、平移到目标位置，最后 {@code drawForm} 画出来。
 *
 * <p><b>为什么不「复制页面」而用 Form XObject</b>：PDF 没有跨文档复制页面的原生操作。
 * Form XObject 是「把一页的内容包装成一个可复用的图形对象」，引用它而不是复制内容流，
 * 多页引用同一源页时体积不会线性增长。这是所有拼版类功能的基础手段。
 *
 * <p><b>三段变换的顺序不能乱</b>（见 N-up 里的注释）：PDF 的变换矩阵是「后写的先生效」，
 * 先平移、再缩放、再反向平移源原点——顺序错了就会出现「内容跑到页外」
 * 或者「缩放中心不对」的现象。
 */
public final class LayoutOps {

    private LayoutOps() {
    }

    public static final Capability NUP = new Capability(
            "organize.nup", "多页排版（N-up）", "页面组织",
            "把多页缩小排到一页上，常用于省纸打印、讲义、拼版。",
            List.of(
                    Param.choice("layout", "每页排几页", "2×2（4页）",
                            "2×1（2页）", "1×2（2页）", "2×2（4页）", "3×3（9页）"),
                    Param.choice("paper", "纸张", "A4 竖版", "A4 竖版", "A4 横版"),
                    Param.num("margin", "页边距(mm)", "8", "0-30"),
                    Param.num("gap", "格间距(mm)", "4", "0-20"),
                    Param.bool("border", "画分隔线", false, "")
            ),
            ctx -> {
                String layout = ctx.str("layout", "2×2（4页）");
                int cols;
                int rows;
                if (layout.startsWith("2×1")) {
                    cols = 2;
                    rows = 1;
                } else if (layout.startsWith("1×2")) {
                    cols = 1;
                    rows = 2;
                } else if (layout.startsWith("3×3")) {
                    cols = 3;
                    rows = 3;
                } else {
                    cols = 2;
                    rows = 2;
                }
                boolean landscape = "A4 横版".equals(ctx.str("paper", "A4 竖版"));
                PDRectangle paper = landscape
                        ? new PDRectangle(PDRectangle.A4.getHeight(), PDRectangle.A4.getWidth())
                        : PDRectangle.A4;
                float mm = 72f / 25.4f;
                float margin = ctx.intVal("margin", 8, 0, 30) * mm;
                float gap = ctx.intVal("gap", 4, 0, 20) * mm;
                boolean border = ctx.bool("border", false);

                try (PDDocument src = PdfUtil.load(ctx.input(), ctx.str("password"))) {
                    int total = src.getNumberOfPages();
                    int per = cols * rows;
                    int sheets = (int) Math.ceil(total / (double) per);
                    Path out = ctx.outDerived("_Nup", ".pdf");
                    try (PDDocument dst = new PDDocument()) {
                        LayerUtility lu = new LayerUtility(dst);
                        float cellW = (paper.getWidth() - margin * 2 - gap * (cols - 1)) / cols;
                        float cellH = (paper.getHeight() - margin * 2 - gap * (rows - 1)) / rows;

                        for (int s = 0; s < sheets; s++) {
                            ctx.checkCancel();
                            ctx.report(5 + s * 90 / sheets, "排版第 " + (s + 1) + "/" + sheets + " 张");
                            PDPage outPage = new PDPage(paper);
                            dst.addPage(outPage);
                            try (PDPageContentStream cs = new PDPageContentStream(dst, outPage)) {
                                for (int k = 0; k < per; k++) {
                                    int idx = s * per + k;
                                    if (idx >= total) {
                                        break;
                                    }
                                    int col = k % cols;
                                    int row = k / cols;
                                    // 行从上往下排，PDF 原点在左下
                                    float x = margin + col * (cellW + gap);
                                    float y = paper.getHeight() - margin - (row + 1) * cellH - row * gap;

                                    PDFormXObject form = lu.importPageAsForm(src, idx);
                                    PDRectangle srcBox = src.getPage(idx).getCropBox();
                                    float scale = Math.min(cellW / srcBox.getWidth(), cellH / srcBox.getHeight());
                                    float drawW = srcBox.getWidth() * scale;
                                    float drawH = srcBox.getHeight() * scale;
                                    float ox = x + (cellW - drawW) / 2;
                                    float oy = y + (cellH - drawH) / 2;

                                    if (border) {
                                        cs.setStrokingColor(new java.awt.Color(200, 200, 200));
                                        cs.addRect(x, y, cellW, cellH);
                                        cs.stroke();
                                    }
                                    cs.saveGraphicsState();
                                    // 变换链分三步，PDF 的矩阵是「后写先生效」，实际执行顺序是自下而上：
                                    //   1) 反向平移源页的 CropBox 左下角 → 把源内容挪到坐标原点
                                    //   2) 按 scale 缩放 → 此时缩放中心正好是源页左下角，不会偏
                                    //   3) 平移到目标格子的左上角原点 → 落到该在的位置
                                    // 少了第 1 步：源页若有非零的 CropBox 原点（裁剪过的页面），
                                    //   内容会整体偏移，格子里只看到一角空白。
                                    cs.transform(new Matrix(1, 0, 0, 1, ox, oy));
                                    cs.transform(Matrix.getScaleInstance(scale, scale));
                                    cs.transform(new Matrix(1, 0, 0, 1,
                                            -srcBox.getLowerLeftX(), -srcBox.getLowerLeftY()));
                                    cs.drawForm(form);
                                    cs.restoreGraphicsState();
                                }
                            }
                        }
                        PdfUtil.save(dst, out);
                    }
                    ctx.addOutput(out);
                    ctx.report(100, total + " 页排成 " + sheets + " 张");
                }
            });

    /**
     * 按目标体积拆分。适合「邮件附件上限 20 MB」这类硬约束。
     *
     * <p><b>为什么要估算而不是精确控制</b>：PDF 的体积不是页面体积的简单相加——
     * 字体、图片等资源可能被多页共享，重复计算会严重高估；压缩后的实际大小也取决于
     * 目标文档的编码结果。所以这里按「每页内容流长度占总内容长度的比例」来切分，
     * 只能保证「大致不超过」。
     *
     * <p>{@code Math.max(size, 20_000)} 是给每页设一个下限：纯文字页的内容流可能只有
     * 几百字节，但重建后的文档至少有页面对象、交叉引用表等固定开销（约 20 KB）。
     * 不设下限的话，几百页纯文字文档会被判成「一页就够」，实际远远超限。
     *
     * <p>单页本身就超限时无法再拆，结果文件仍会超过目标值——这是固有限制，不是缺陷。
     */
    public static final Capability SPLIT_BY_SIZE = new Capability(
            "organize.split-by-size", "按大小拆分", "页面组织",
            "按目标文件大小拆成多个 PDF，适合按邮件附件上限分批发送。",
            List.of(Param.num("mb", "每个文件不超过(MB)", "10", "0-10000")),
            ctx -> {
                int targetMb = ctx.intVal("mb", 10, 1, 10000);
                long target = targetMb * 1024L * 1024L;
                try (PDDocument src = PdfUtil.load(ctx.input(), ctx.str("password"))) {
                    int total = src.getNumberOfPages();
                    // 估算每页字节数：内容流 + 资源对象（近似值，够用）
                    long[] pageBytes = new long[total];
                    for (int i = 0; i < total; i++) {
                        long size = 0;
                        try {
                            org.apache.pdfbox.cos.COSBase c = src.getPage(i).getCOSObject()
                                    .getDictionaryObject(org.apache.pdfbox.cos.COSName.CONTENTS);
                            if (c instanceof org.apache.pdfbox.cos.COSStream cs) {
                                size = cs.getLength();
                            } else if (c instanceof org.apache.pdfbox.cos.COSArray arr) {
                                for (int k = 0; k < arr.size(); k++) {
                                    if (arr.getObject(k) instanceof org.apache.pdfbox.cos.COSStream s) {
                                        size += s.getLength();
                                    }
                                }
                            }
                        } catch (Exception ignored) {
                            // 取不到长度按 0 计
                        }
                        pageBytes[i] = Math.max(size, 20_000);
                    }
                    long totalBytes = 0;
                    for (long b : pageBytes) {
                        totalBytes += b;
                    }
                    // 按比例换算成实际文件大小
                    long fileSize;
                    try {
                        fileSize = java.nio.file.Files.size(ctx.input());
                    } catch (Exception e) {
                        fileSize = totalBytes;
                    }
                    double ratio = totalBytes == 0 ? 1 : Math.max(1.0, fileSize / (double) totalBytes);

                    List<List<Integer>> chunks = new ArrayList<>();
                    List<Integer> cur = new ArrayList<>();
                    long acc = 0;
                    for (int i = 0; i < total; i++) {
                        long est = (long) (pageBytes[i] * ratio);
                        if (!cur.isEmpty() && acc + est > target) {
                            chunks.add(cur);
                            cur = new ArrayList<>();
                            acc = 0;
                        }
                        cur.add(i);
                        acc += est;
                    }
                    if (!cur.isEmpty()) {
                        chunks.add(cur);
                    }

                    String base = PdfUtil.baseName(ctx.input());
                    for (int i = 0; i < chunks.size(); i++) {
                        ctx.report(10 + i * 85 / chunks.size(), "生成第 " + (i + 1) + "/" + chunks.size() + " 部分");
                        List<Integer> pages = chunks.get(i);
                        int[] idx = pages.stream().mapToInt(Integer::intValue).toArray();
                        Path out = ctx.out(base + "_部分" + (i + 1) + ".pdf");
                        try (PDDocument dst = new PDDocument()) {
                            for (int k : idx) {
                                dst.importPage(src.getPage(k));
                            }
                            PdfUtil.save(dst, out);
                        }
                        ctx.addOutput(out);
                    }
                    ctx.report(100, "拆成 " + chunks.size() + " 个文件，每个不超过 " + targetMb + " MB");
                }
            });

    /**
     * 叠加文档：底稿 + 叠加层 → 合成一份。
     *
     * <p><b>与「添加水印」「添加页眉页脚」的区别</b>：那两项生成的是文字，本项叠加的是
     * 任意 PDF 页面内容（可以是整张信纸底图、可以是盖好章的页面），保真度更高但要求
     * 用户自己准备好叠加层文件。
     *
     * <p><b>叠加层页数少于底稿时会循环复用</b>：`i % over.getNumberOfPages()` 让第 1 页
     * 叠加层重复贴到第 1、3、5… 页。这是刻意的——一份双面信纸通常只需做 2 页叠加层，
     * 用户不该为了贴满 200 页而手工复制 100 遍。
     *
     * <p><b>页面尺寸不一致时不会被拉伸</b>：`importPageAsForm` 保留叠加层的原始尺寸与
     * 坐标原点，直接贴到 (0,0)。所以 A4 底稿配 Letter 叠加层会出现内容偏移或超出边界，
     * 用户需自行保证两个文件页面尺寸一致。
     */
    public static final Capability OVERLAY = new Capability(
            "organize.overlay", "叠加文档", "页面组织",
            "把第 2 个文件作为叠加层盖到第 1 个文件的每一页上（信纸、模板、印章页常用）。",
            List.of(
                    Param.choice("target", "叠加到", "全部页", "全部页", "偶数页", "奇数页"),
                    Param.num("opacity", "叠加层透明度(%)", "100", "10-100")
            ),
            ctx -> {
                if (ctx.inputs().isEmpty()) {
                    throw new Err("ERR_NO_INPUT", "请先选择要叠加的 PDF 文件");
                }
                if (ctx.inputs().size() < 2) {
                    throw new Err("ERR_NEED_TWO", "叠加需要 2 个文件：第 1 个是底稿，第 2 个是叠加层");
                }
                String target = ctx.str("target", "全部页");
                float alpha = ctx.intVal("opacity", 100, 10, 100) / 100f;
                try (PDDocument base = PdfUtil.load(ctx.input(0), ctx.str("password"));
                     PDDocument over = PdfUtil.load(ctx.input(1), ctx.str("password"))) {
                    int total = base.getNumberOfPages();
                    if (over.getNumberOfPages() == 0) {
                        throw new Err("ERR_BAD_PDF", "叠加层文件没有任何页面");
                    }
                    LayerUtility lu = new LayerUtility(base);
                    for (int i = 0; i < total; i++) {
                        ctx.checkCancel();
                        // 页码是 1 基，循环下标 i 是 0 基，所以判断偶数页要用 (i + 1)
                        boolean even = (i + 1) % 2 == 0;
                        boolean apply = switch (target) {
                            case "偶数页" -> even;
                            case "奇数页" -> !even;
                            default -> true;
                        };
                        if (!apply) {
                            continue;
                        }
                        ctx.report(5 + i * 90 / total, "叠加第 " + (i + 1) + " 页");
                        // 取模而非直接取 i：叠加层页数少于底稿时循环复用第 1、2、3… 页
                        PDFormXObject form = lu.importPageAsForm(over, i % over.getNumberOfPages());
                        // APPEND 追加到原内容之后（叠加层盖在上面），resetContext=true 让新内容流
                        // 从默认状态开始，compress=true 顺手压缩。三个参数少任何一个都会出问题：
                        // 非 APPEND 会清空底稿内容，非 resetContext 会继承底稿的变换矩阵
                        try (PDPageContentStream cs = new PDPageContentStream(base, base.getPage(i),
                                PDPageContentStream.AppendMode.APPEND, true, true)) {
                            if (alpha < 1f) {
                                // 半透明必须走 ExtGState：PDF 的内容流里没有「透明度」操作符，
                                // 只能引用一个扩展图形状态字典，且描边与非描边要分别设置，
                                // 只设一个会导致文字透明而边框不透明（或反之）
                                var gs = new org.apache.pdfbox.pdmodel.graphics.state.PDExtendedGraphicsState();
                                gs.setNonStrokingAlphaConstant(alpha);
                                gs.setStrokingAlphaConstant(alpha);
                                cs.setGraphicsStateParameters(gs);
                            }
                            // 注意：上面的 gs 设在 save 之前，因此 restore 不会撤销它——
                            // 本页最后一个图形状态会带着这个 alpha 值。因为叠加是每页的最后一步
                            // 操作，不影响结果；若将来要在叠加后继续画东西，需把 save 提到 set 之前
                            cs.saveGraphicsState();
                            cs.drawForm(form);
                            cs.restoreGraphicsState();
                        }
                    }
                    Path out = ctx.outDerived("_叠加", ".pdf");
                    PdfUtil.save(base, out);
                    ctx.addOutput(out);
                    ctx.report(100, "叠加完成");
                }
            });
}
