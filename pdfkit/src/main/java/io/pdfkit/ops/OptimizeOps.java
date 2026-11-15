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

/**
 * 优化类能力：压缩、修复、画质增强。
 *
 * <p>三项都是「在像素层面动手」的能力，这是它们与别的类的根本区别：
 * 其它能力操作 PDF 对象，这里要先把图片解码成 {@link java.awt.image.BufferedImage}，
 * 处理完再编码回去。所以三项都吃内存、吃 CPU，也是性能测试的重点观察对象。
 *
 * <p><b>统一的图片处理套路</b>：
 * <ol>
 *   <li>从 {@code PDImageXObject} 取出 {@code BufferedImage}（可能失败，某些编码取不出来）；</li>
 *   <li>转成 {@code TYPE_INT_RGB} 并先铺白底——JPEG 不支持透明通道，
 *       不铺白底的话透明区域会变成黑块；</li>
 *   <li>处理像素（缩放、去噪、锐化、二值化）；</li>
 *   <li>用 {@code JPEGFactory.createFromImage} 编回 PDF。</li>
 * </ol>
 *
 * <p><b>单张图片失败绝不中断整批</b>：一份扫描件里几百张图，总有个别用了怪异的
 * 色彩空间或加密编码。跳过它、记一条日志、继续处理剩下的，比让整个任务失败有用得多。
 */
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
                                    // 超长边才缩：小的图放大只会变糊，不会变小
                                    float scale = maxDim / (float) Math.max(w, h);
                                    int nw = Math.max(1, Math.round(w * scale));
                                    int nh = Math.max(1, Math.round(h * scale));
                                    target = new BufferedImage(nw, nh, BufferedImage.TYPE_INT_RGB);
                                    Graphics2D g = target.createGraphics();
                                    // 双线性插值：缩放速度与质量的折中。用最近邻会有锯齿，
                                    // 用双三次（BICUBIC）在大图上明显更慢
                                    g.setRenderingHint(java.awt.RenderingHints.KEY_INTERPOLATION,
                                            java.awt.RenderingHints.VALUE_INTERPOLATION_BILINEAR);
                                    // 必须铺白底：JPEG 无透明通道，透明像素会被编码成黑块
                                    g.setColor(java.awt.Color.WHITE);
                                    g.fillRect(0, 0, nw, nh);
                                    g.drawImage(bi, 0, 0, nw, nh, null);
                                    g.dispose();
                                } else if (bi.getType() != BufferedImage.TYPE_INT_RGB) {
                                    // 尺寸不用缩，但类型仍是调色板/灰度/带 alpha，同样要转 RGB 才能编 JPEG
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
                    // 压缩反而变大时必须明说。压缩的主力手段是图片重采样，
                    // 纯文字 PDF 本来就没有多少图片可压，重新编码反而可能略增体积。
                    // 不提示的话用户会以为功能失效。
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

    /**
     * 转 RGB（委托给 {@link EditPlusOps#toRgb}）。
     *
     * <p>保留这个包装而不是直接调用，是为了让本类的意图更清楚：增强流程里每次
     * 变换前后都要保证是 RGB，否则 JPEG 编不出来。真正铺白底的逻辑只有一份，
     * 避免两处实现漂移。
     */
    static BufferedImage toRgbSafe(BufferedImage src) {
        return EditPlusOps.toRgb(src);
    }

    /**
     * 转灰度，但<b>再转回 RGB</b>。
     *
     * <p>为什么转两次：{@code TYPE_BYTE_GRAY} 是索引色模型，它的 {@code getRGB} 与
     * JPEG 编码行为在部分 JDK 版本上不一致（可能按 8 位调色板处理，得到偏色的结果）。
     * 把它再画一遍成 {@code TYPE_INT_RGB}，后续所有逐像素运算都在确定的三通道
     * 模型里进行，灰阶值在三个通道上相同，视觉上仍是灰度。
     */
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

    /**
     * 中值滤波去噪，radius 为半径（1-3），窗口越大越平滑也越慢。
     *
     * <p>中值而不是均值：扫描件的噪点通常是孤立黑点，均值会把它糊成一小片灰斑，
     * 中值则直接丢弃离群值，纸面更干净、文字边缘不糊。
     *
     * <p>边界处的坐标用 clamp 到有效范围（<b>复制边缘像素</b>）而不是跳过该邻域。
     * 跳过会让图像四边的滤波强度弱于中心，出现一圈明显的「未去噪」边。
     *
     * <p>复杂度是 O(w·h·side²·log(side²))，因为每个像素都要排序。radius=3 时
     * 每像素排 49 个值，一张 A4/150DPI 的图约需数秒——这就是上限只开放到 3 的原因。
     */
    static BufferedImage median(BufferedImage src, int radius) {
        int w = src.getWidth();
        int h = src.getHeight();
        BufferedImage out = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        // 遗留的死代码：r/g/b 三个长度 9 的数组后来被 cr/cg/cb 取代（因为窗口可以大于 3×3），
        // 已无任何引用，可以安全删除
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

    /**
     * 反锐化掩模：{@code 原图 + amount × (原图 - 模糊图)}。
     *
     * <p>括号里那一项是「高频细节」（原图减去它的模糊版本），乘以 amount 再加回原图，
     * 等于把边缘对比度放大。amount=0 时输出等于原图，1.0 左右是明显锐化，
     * 超过 2 会出现白边光晕。
     *
     * <p>模糊用的是 3×3 高斯核 {@code {1,2,1, 2,4,2, 1,2,1}}（权重和为 16）。
     * 相比盒式模糊（全是 1），高斯的中心权重更高，模糊后边缘过渡更自然，
     * 相减得到的高频更干净。除数是 16f 而不是别的数——必须等于核的和，否则整体亮度会漂移。
     */
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

    /**
     * 对比度拉伸：{@code (v - 128) × factor + 128}。
     *
     * <p>中点固定取 128（8 位色阶的中值），没有按实际直方图计算。这意味着
     * <b>整体偏暗的扫描件会被拉得更暗</b>——因为它的有效亮度集中在 128 以下，
     * 围绕 128 拉伸会把暗部推得更极端。对这类图应先做亮度/伽马校正再调对比度。
     * 若将来要改进，可改成先统计直方图的 5% 与 95% 分位点，再线性映射到 0–255。
     */
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
