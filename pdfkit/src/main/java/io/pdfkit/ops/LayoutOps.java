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

/** 版式类能力：多页排版（N-up）、按大小拆分、文档叠加。 */
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
                        PDFormXObject form = lu.importPageAsForm(over, i % over.getNumberOfPages());
                        try (PDPageContentStream cs = new PDPageContentStream(base, base.getPage(i),
                                PDPageContentStream.AppendMode.APPEND, true, true)) {
                            if (alpha < 1f) {
                                var gs = new org.apache.pdfbox.pdmodel.graphics.state.PDExtendedGraphicsState();
                                gs.setNonStrokingAlphaConstant(alpha);
                                gs.setStrokingAlphaConstant(alpha);
                                cs.setGraphicsStateParameters(gs);
                            }
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
