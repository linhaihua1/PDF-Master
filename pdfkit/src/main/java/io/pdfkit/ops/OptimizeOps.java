package io.pdfkit.ops;

import io.pdfkit.core.Capability;
import io.pdfkit.core.Ctx;
import io.pdfkit.core.Param;
import io.pdfkit.util.Log;
import io.pdfkit.util.PdfUtil;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDResources;
import org.apache.pdfbox.pdmodel.graphics.image.JPEGFactory;
import org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject;
import org.apache.pdfbox.pdmodel.graphics.PDXObject;

import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.util.List;

/** 优化类能力：压缩、修复。 */
public final class OptimizeOps {

    private OptimizeOps() {
    }

    public static final Capability COMPRESS = new Capability(
            "optimize.compress", "压缩 PDF", "优化与修复",
            "重采样图片并降低 JPG 质量来减小体积，适合扫描件与图片型 PDF。",
            List.of(
                    Param.choice("level", "压缩强度", "标准", "轻度", "标准", "激进"),
                    Param.num("maxDim", "图片最长边(px)", "0", "0=按强度自动；手动填 800-4000"),
                    Param.bool("removeMeta", "清空元数据", true, "减小少量体积")
            ),
            ctx -> {
                String level = ctx.str("level", "标准");
                int maxDim = ctx.intVal("maxDim", 0, 0, 20000);
                if (maxDim == 0) {
                    maxDim = switch (level) {
                        case "轻度" -> 2400;
                        case "激进" -> 1200;
                        default -> 1800;
                    };
                }
                float quality = switch (level) {
                    case "轻度" -> 0.88f;
                    case "激进" -> 0.62f;
                    default -> 0.75f;
                };
                long before = 0;
                try {
                    before = java.nio.file.Files.size(ctx.input());
                } catch (Exception ignored) {
                    // 取不到原始大小不影响压缩
                }
                try (PDDocument doc = PdfUtil.load(ctx.input(), ctx.str("password"))) {
                    int total = doc.getNumberOfPages();
                    int replaced = 0;
                    int skipped = 0;
                    int pageNo = 0;
                    for (PDPage page : doc.getPages()) {
                        pageNo++;
                        ctx.report(5 + pageNo * 80 / total, "处理第 " + pageNo + "/" + total + " 页");
                        PDResources res = page.getResources();
                        if (res == null) {
                            continue;
                        }
                        java.util.List<COSName> xobjNames = new java.util.ArrayList<>();
                        for (COSName n : res.getXObjectNames()) {
                            xobjNames.add(n);
                        }
                        for (COSName name : xobjNames) {
                            ctx.checkCancel();
                            PDXObject xobj;
                            try {
                                xobj = res.getXObject(name);
                            } catch (Exception e) {
                                skipped++;
                                continue;
                            }
                            if (!(xobj instanceof PDImageXObject img)) {
                                continue;
                            }
                            try {
                                BufferedImage bi = img.getImage();
                                if (bi == null) {
                                    skipped++;
                                    continue;
                                }
                                int w = bi.getWidth();
                                int h = bi.getHeight();
                                BufferedImage target = bi;
                                if (Math.max(w, h) > maxDim) {
                                    float scale = maxDim / (float) Math.max(w, h);
                                    int nw = Math.max(1, Math.round(w * scale));
                                    int nh = Math.max(1, Math.round(h * scale));
                                    target = new BufferedImage(nw, nh, BufferedImage.TYPE_INT_RGB);
                                    Graphics2D g = target.createGraphics();
                                    g.setRenderingHint(java.awt.RenderingHints.KEY_INTERPOLATION,
                                            java.awt.RenderingHints.VALUE_INTERPOLATION_BILINEAR);
                                    g.setColor(java.awt.Color.WHITE);
                                    g.fillRect(0, 0, nw, nh);
                                    g.drawImage(bi, 0, 0, nw, nh, null);
                                    g.dispose();
                                } else if (bi.getType() != BufferedImage.TYPE_INT_RGB) {
                                    target = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
                                    Graphics2D g = target.createGraphics();
                                    g.setColor(java.awt.Color.WHITE);
                                    g.fillRect(0, 0, w, h);
                                    g.drawImage(bi, 0, 0, null);
                                    g.dispose();
                                }
                                PDImageXObject newImg = JPEGFactory.createFromImage(doc, target, quality);
                                res.put(name, newImg);
                                replaced++;
                            } catch (Exception e) {
                                skipped++;
                                Log.warn("跳过一张图片（%s）：%s", name.getName(), e.getMessage());
                            }
                        }
                    }
                    if (ctx.bool("removeMeta", true)) {
                        var info = doc.getDocumentInformation();
                        info.setCreator(null);
                        info.setProducer(null);
                    }
                    ctx.report(90, "写入文件");
                    java.nio.file.Path out = ctx.outDerived("_压缩", ".pdf");
                    PdfUtil.save(doc, out);
                    ctx.addOutput(out);

                    long after = 0;
                    try {
                        after = java.nio.file.Files.size(out);
                    } catch (Exception ignored) {
                        // 大小读取失败只影响提示文案
                    }
                    String ratio = before > 0 ? String.format("（%.1f MB → %.1f MB，%d%%）",
                            before / 1048576.0, after / 1048576.0, Math.round(after * 100.0 / before)) : "";
                    ctx.report(100, "压缩完成：处理 " + replaced + " 张图片，跳过 " + skipped + " 张 " + ratio);
                    if (before > 0 && after >= before) {
                        ctx.setResult("注意：本次压缩没有减小体积（原文件 " + String.format("%.2f", before / 1048576.0)
                                + " MB，新文件 " + String.format("%.2f", after / 1048576.0)
                                + " MB）。该文件可能以文字为主，压缩收益有限；可尝试「激进」强度。");
                    }
                }
            });

    public static final Capability REPAIR = new Capability(
            "optimize.repair", "修复 PDF", "优化与修复",
            "重新解析并重写文档结构，修复交叉引用表损坏、结尾缺失等常见问题。",
            List.of(),
            ctx -> {
                try (PDDocument doc = PdfUtil.load(ctx.input(), ctx.str("password"))) {
                    ctx.report(40, "解析到 " + doc.getNumberOfPages() + " 页，正在重写结构");
                    // 触发完整对象解析，尽早暴露坏对象
                    doc.getDocument().getXrefTable().size();
                    java.nio.file.Path out = ctx.outDerived("_修复", ".pdf");
                    PdfUtil.save(doc, out);
                    ctx.addOutput(out);
                    ctx.report(100, "已重写结构，共 " + doc.getNumberOfPages() + " 页");
                }
            });

    public static final Capability ENHANCE = new Capability(
            "optimize.enhance", "画质增强", "优化与修复",
            "对模糊/发灰的扫描页做锐化、去噪、对比度提升。逐页重绘，文字仍可提取前提是原页已有文字层。",
            List.of(
                    Param.num("dpi", "输出分辨率", "200", "96-400"),
                    Param.num("sharpen", "锐化强度(%)", "60", "0-200"),
                    Param.num("contrast", "对比度(%)", "110", "50-200"),
                    Param.num("denoise", "去噪强度", "1", "0-3"),
                    Param.bool("gray", "转灰度", false, "合同/书籍扫描件推荐")
            ),
            ctx -> {
                int dpi = ctx.intVal("dpi", 200, 96, 400);
                int sharpen = ctx.intVal("sharpen", 60, 0, 200);
                int contrast = ctx.intVal("contrast", 110, 50, 200);
                int denoise = ctx.intVal("denoise", 1, 0, 3);
                boolean gray = ctx.bool("gray", false);
                try (PDDocument doc = PdfUtil.load(ctx.input(), ctx.str("password"))) {
                    List<Integer> pages = PdfUtil.parsePageSpec("", doc.getNumberOfPages());
                    EditPlusOps.renderTransform(doc, pages, dpi, ctx, img -> {
                        BufferedImage work = denoise > 0 ? median(img, denoise) : img;
                        work = sharpen > 0 ? unsharp(work, sharpen / 100f) : work;
                        work = contrast(work, contrast / 100f);
                        return gray ? toGray(work) : toRgbSafe(work);
                    });
                    java.nio.file.Path out = ctx.outDerived("_增强", ".pdf");
                    PdfUtil.save(doc, out);
                    ctx.addOutput(out);
                    ctx.report(100, "已增强 " + pages.size() + " 页");
                }
            });

    // ---------- 图像算法 ----------

    static BufferedImage toRgbSafe(BufferedImage src) {
        return EditPlusOps.toRgb(src);
    }

    static BufferedImage toGray(BufferedImage src) {
        BufferedImage out = new BufferedImage(src.getWidth(), src.getHeight(), BufferedImage.TYPE_BYTE_GRAY);
        java.awt.Graphics2D g = out.createGraphics();
        g.drawImage(src, 0, 0, null);
        g.dispose();
        BufferedImage rgb = new BufferedImage(src.getWidth(), src.getHeight(), BufferedImage.TYPE_INT_RGB);
        java.awt.Graphics2D g2 = rgb.createGraphics();
        g2.drawImage(out, 0, 0, null);
        g2.dispose();
        return rgb;
    }

    /** 中值滤波去噪，radius 为半径（1-3），窗口越大越平滑也越慢。 */
    static BufferedImage median(BufferedImage src, int radius) {
        int w = src.getWidth();
        int h = src.getHeight();
        BufferedImage out = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        int[] r = new int[9];
        int[] g = new int[9];
        int[] b = new int[9];
        int side = radius * 2 + 1;
        int[] cr = new int[side * side];
        int[] cg = new int[side * side];
        int[] cb = new int[side * side];
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                int n = 0;
                for (int dy = -radius; dy <= radius; dy++) {
                    int yy = Math.min(h - 1, Math.max(0, y + dy));
                    for (int dx = -radius; dx <= radius; dx++) {
                        int xx = Math.min(w - 1, Math.max(0, x + dx));
                        int rgb = src.getRGB(xx, yy);
                        cr[n] = (rgb >> 16) & 0xFF;
                        cg[n] = (rgb >> 8) & 0xFF;
                        cb[n] = rgb & 0xFF;
                        n++;
                    }
                }
                java.util.Arrays.sort(cr, 0, n);
                java.util.Arrays.sort(cg, 0, n);
                java.util.Arrays.sort(cb, 0, n);
                int m = n / 2;
                out.setRGB(x, y, (cr[m] << 16) | (cg[m] << 8) | cb[m]);
            }
        }
        return out;
    }

    /** 反锐化掩模：原图 + amount × (原图 - 模糊图)。 */
    static BufferedImage unsharp(BufferedImage src, float amount) {
        int w = src.getWidth();
        int h = src.getHeight();
        BufferedImage out = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        int[] kernel = {1, 2, 1, 2, 4, 2, 1, 2, 1};
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                int rr = 0;
                int gg = 0;
                int bb = 0;
                int k = 0;
                for (int dy = -1; dy <= 1; dy++) {
                    int yy = Math.min(h - 1, Math.max(0, y + dy));
                    for (int dx = -1; dx <= 1; dx++) {
                        int xx = Math.min(w - 1, Math.max(0, x + dx));
                        int rgb = src.getRGB(xx, yy);
                        int wgt = kernel[k++];
                        rr += ((rgb >> 16) & 0xFF) * wgt;
                        gg += ((rgb >> 8) & 0xFF) * wgt;
                        bb += (rgb & 0xFF) * wgt;
                    }
                }
                float br = rr / 16f;
                float bg = gg / 16f;
                float bbl = bb / 16f;
                int rgb = src.getRGB(x, y);
                int cr = clamp(Math.round(((rgb >> 16) & 0xFF) + amount * (((rgb >> 16) & 0xFF) - br)));
                int cg = clamp(Math.round(((rgb >> 8) & 0xFF) + amount * (((rgb >> 8) & 0xFF) - bg)));
                int cb = clamp(Math.round((rgb & 0xFF) + amount * ((rgb & 0xFF) - bbl)));
                out.setRGB(x, y, (cr << 16) | (cg << 8) | cb);
            }
        }
        return out;
    }

    static BufferedImage contrast(BufferedImage src, float factor) {
        int w = src.getWidth();
        int h = src.getHeight();
        BufferedImage out = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                int rgb = src.getRGB(x, y);
                int cr = clamp(Math.round((((rgb >> 16) & 0xFF) - 128) * factor + 128));
                int cg = clamp(Math.round((((rgb >> 8) & 0xFF) - 128) * factor + 128));
                int cb = clamp(Math.round(((rgb & 0xFF) - 128) * factor + 128));
                out.setRGB(x, y, (cr << 16) | (cg << 8) | cb);
            }
        }
        return out;
    }

    static int clamp(int v) {
        return Math.max(0, Math.min(255, v));
    }
}
