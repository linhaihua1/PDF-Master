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
import org.apache.pdfbox.pdmodel.graphics.image.JPEGFactory;
import org.apache.pdfbox.pdmodel.graphics.image.LosslessFactory;
import org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject;
import org.apache.pdfbox.rendering.ImageType;
import org.apache.pdfbox.rendering.PDFRenderer;
import org.apache.pdfbox.text.PDFTextStripper;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;

/**
 * 转换类能力：PDF→图片、图片→PDF、PDF→文本、提取内嵌图片。
 *
 * <p>这一类的共同点是<b>跨出 PDF 的世界</b>：要么把页面渲染成位图（走 Java2D），
 * 要么把外部的图片/文本重新包装成 PDF 对象。所以错误处理的重点与其它类不同——
 * 失败往往发生在「格式不兼容」而不是「PDF 结构异常」。
 *
 * <p><b>渲染质量的核心参数是 DPI</b>：{@code renderImageWithDPI} 按「每英寸像素数」
 * 渲染，A4 页面在 150 DPI 下约 1240×1754 像素，在 300 DPI 下翻倍到 2480×3508。
 * DPI 每翻一倍，像素数变 4 倍、内存变 4 倍、耗时也大致 4 倍。
 * 默认 150 是「屏幕阅读与普通打印都够用」的平衡点。
 */
public final class ConvertOps {

    private ConvertOps() {
    }

    /**
     * PDF 转图片：逐页渲染成 PNG / JPG。
     *
     * <p><b>DPI 与文件体积是平方关系</b>：150 → 300 DPI 时像素数变成 4 倍，PNG 体积
     * 大致也涨 4 倍。批量导出前先把 DPI 调低试一页，比导完 200 张再重来划算。
     *
     * <p><b>单页不出子文件夹</b>：只导一页时直接把图片放在输出目录，多页才建
     * {@code 原名_图片} 文件夹。这样单页导出的体验是「点开输出目录就看到图」，
     * 而不是「点开目录、再点进一层、才看到图」。
     *
     * <p><b>产物登记单页与多页都登记文件、不登记目录</b>，因为界面双击任务会打开
     * 「第一个产物的父目录」。登记目录会让它打开输出目录的上一级。
     */
    public static final Capability TO_IMAGE = new Capability(
            "convert.pdf-to-image", "PDF 转图片", "格式转换",
            "把每页渲染成 PNG 或 JPG，可选清晰度。多页输出到同名文件夹。",
            List.of(
                    Param.choice("format", "格式", "PNG", "PNG", "JPG"),
                    Param.num("dpi", "清晰度(DPI)", "150", "72-600"),
                    Param.choice("pages", "导出范围", "全部", "全部", "自定义"),
                    Param.text("range", "自定义页码", "", "如 1-3,5，仅自定义时生效"),
                    Param.num("quality", "JPG 质量", "85", "50-100，仅 JPG 生效")
            ),
            ctx -> {
                String format = ctx.str("format", "PNG").toUpperCase(Locale.ROOT);
                int dpi = ctx.intVal("dpi", 150, 72, 600);
                int quality = ctx.intVal("quality", 85, 50, 100);
                try (PDDocument doc = PdfUtil.load(ctx.input(), ctx.str("password"))) {
                    List<Integer> pages;
                    if ("自定义".equals(ctx.str("pages", "全部"))) {
                        pages = PdfUtil.parsePageSpec(ctx.str("range"), doc.getNumberOfPages());
                    } else {
                        pages = PdfUtil.parsePageSpec("", doc.getNumberOfPages());
                    }
                    PDFRenderer renderer = new PDFRenderer(doc);
                    // 允许子采样：大图降采样能显著加速渲染，代价是极细微的图案可能失真。
                    // 转图片场景下这个取舍是值得的（性能测试里 27MB 文档导出明显受它影响）
                    renderer.setSubsamplingAllowed(true);
                    String base = PdfUtil.baseName(ctx.input());
                    // 单页直接输出到输出目录，多页才建子文件夹。
                    // 单页还建文件夹的话，用户点「打开输出目录」会看到一层多余的嵌套。
                    boolean single = pages.size() == 1;
                    Path folder = single ? ctx.outDir() : ctx.outDir().resolve(base + "_图片");
                    if (!single) {
                        Files.createDirectories(folder);
                    }
                    int i = 0;
                    // 单页分支需要在循环外登记产物，但 target 是循环内变量，所以先存一份
                    Path singleOut = null;
                    for (int p : pages) {
                        ctx.report(5 + i * 90 / pages.size(), "渲染第 " + p + " 页");
                        // ImageType.RGB 而非 ARGB：PDF 页面本就没有透明背景，
                        // 用 ARGB 会让 PNG 带无用的 alpha 通道、JPG 直接编码失败
                        BufferedImage img = renderer.renderImageWithDPI(p - 1, dpi, ImageType.RGB);
                        // 多页时用三位补零的页号命名，保证文件管理器里按名字排序就是页序
                        String name = single
                                ? base + "." + format.toLowerCase(Locale.ROOT)
                                : String.format("%s_%03d.%s", base, p, format.toLowerCase(Locale.ROOT));
                        Path target = single ? ctx.out(name) : folder.resolve(name);
                        if (single) {
                            singleOut = target;
                        }
                        if ("JPG".equals(format)) {
                            writeJpeg(img, target, quality);
                        } else {
                            ImageIO.write(img, "png", target.toFile());
                        }
                        i++;
                    }
                    if (single) {
                        // 必须登记文件本身，不能登记 folder（single 时它就等于输出目录）。
                        // 界面双击任务会打开「第一个产物的父目录」，登记目录会导致打开输出目录
                        // 的上一级。多页分支登记的是每个文件，两者要保持一致
                        if (singleOut != null) {
                            ctx.addOutput(singleOut);
                        }
                    } else {
                        for (Path f : Files.list(folder).toList()) {
                            ctx.addOutput(f);
                        }
                    }
                    ctx.report(100, "导出 " + pages.size() + " 张图片");
                }
            });

    /**
     * 图片转 PDF：一张图一页。
     *
     * <p><b>「按原图」纸张模式假设图片是 96 DPI</b>。图片文件本身通常不存物理尺寸，
     * 只有像素数，所以必须假定一个 DPI 才能换算出页面大小。取 96 是因为那是 Windows
     * 与浏览器长期使用的屏幕基准值——一张 1920×1080 的截图会被排成 20 英寸宽，
     * 看上去大小正合适。若按 300 DPI 折算，同样一张图只有 6.4 英寸宽，会显得很小。
     *
     * <p><b>等比缩放取 min 而非 max</b>：横图受限于可用宽度、竖图受限于可用高度，
     * 取较小比例才能保证不变形且不超出纸张。取 max 会让某一维溢出被裁切。
     *
     * <p><b>输出文件名带毫秒时间戳</b>，因为本项没有「输入 PDF」可用作命名依据
     * （它是多张图片合成的），不能像其他能力那样走 {@code outDerived}。
     * 时间戳同时起到防重名的作用。
     */
    public static final Capability FROM_IMAGE = new Capability(
            "convert.image-to-pdf", "图片转 PDF", "格式转换",
            "把多张图片按选择顺序合成一个 PDF，可统一纸张或按原图尺寸。",
            List.of(
                    Param.choice("paper", "纸张", "按原图", "按原图", "A4 竖版", "A4 横版"),
                    Param.choice("fit", "图片适配", "等比缩放居中", "等比缩放居中", "拉伸铺满"),
                    Param.num("margin", "页边距(mm)", "0", "0-50"),
                    Param.bool("jpegCompress", "压缩图片", true, "体积更小，画质略降")
            ),
            ctx -> {
                List<Path> images = ctx.inputs();
                if (images.isEmpty()) {
                    throw new Err("ERR_NO_INPUT", "请选择至少一张图片");
                }
                String paper = ctx.str("paper", "按原图");
                String fit = ctx.str("fit", "等比缩放居中");
                float margin = ctx.intVal("margin", 0, 0, 50) * 72f / 25.4f;
                boolean compress = ctx.bool("jpegCompress", true);

                try (PDDocument doc = new PDDocument()) {
                    int i = 0;
                    for (Path imgPath : images) {
                        ctx.report(5 + i++ * 90 / images.size(), "添加：" + imgPath.getFileName());
                        BufferedImage img;
                        try {
                            img = ImageIO.read(imgPath.toFile());
                        } catch (Exception e) {
                            throw new Err("ERR_BAD_IMAGE", "无法读取图片：" + imgPath.getFileName(), e);
                        }
                        if (img == null) {
                            throw new Err("ERR_BAD_IMAGE", "不支持的图片格式：" + imgPath.getFileName());
                        }
                        // 压缩走 JPEG（质量 0.85），不压缩走无损。无损保留 PNG 的透明通道
                        // 与锐利边缘，代价是照片类图片体积可能大十倍
                        PDImageXObject pdImg = compress
                                ? JPEGFactory.createFromImage(doc, img, 0.85f)
                                : LosslessFactory.createFromImage(doc, img);

                        PDRectangle pageSize;
                        if ("A4 竖版".equals(paper)) {
                            pageSize = PDRectangle.A4;
                        } else if ("A4 横版".equals(paper)) {
                            // 横版就是把 A4 的宽高对调，没有现成的 A4_LANDSCAPE 常量
                            pageSize = new PDRectangle(PDRectangle.A4.getHeight(), PDRectangle.A4.getWidth());
                        } else {
                            // 按原图：像素数按 96 DPI 折算成 PDF 单位（1/72 英寸）
                            float w = img.getWidth() * 72f / 96f;
                            float h = img.getHeight() * 72f / 96f;
                            pageSize = new PDRectangle(w + margin * 2, h + margin * 2);
                        }
                        PDPage page = new PDPage(pageSize);
                        doc.addPage(page);

                        float availW = pageSize.getWidth() - margin * 2;
                        float availH = pageSize.getHeight() - margin * 2;
                        float drawW;
                        float drawH;
                        if ("拉伸铺满".equals(fit)) {
                            drawW = availW;
                            drawH = availH;
                        } else {
                            float scale = Math.min(availW / img.getWidth(), availH / img.getHeight());
                            drawW = img.getWidth() * scale;
                            drawH = img.getHeight() * scale;
                        }
                        float x = margin + (availW - drawW) / 2;
                        float y = margin + (availH - drawH) / 2;
                        try (PDPageContentStream cs = new PDPageContentStream(doc, page)) {
                            cs.drawImage(pdImg, x, y, drawW, drawH);
                        }
                    }
                    Path out = ctx.outDir().resolve("图片合并_" + System.currentTimeMillis() + ".pdf");
                    PdfUtil.save(doc, out);
                    ctx.addOutput(out);
                    ctx.report(100, "已生成 PDF：" + images.size() + " 页");
                }
            });

    /**
     * PDF 转文本。
     *
     * <p><b>逐页提取而不是一次提全部</b>：{@code PDFTextStripper} 支持一次处理整个文档，
     * 但那样中间没有可汇报的进度，200 页的文档界面会僵住。改为每页调一次
     * {@code setStartPage/setEndPage}，代价是每页都要重新初始化解析状态，稍微慢一点，
     * 换来的是进度条能真实走动。
     *
     * <p><b>「保留版面位置」对应 {@code setSortByPosition}</b>：开启后按页面坐标排序输出，
     * 接近人眼阅读顺序，但速度明显更慢；关闭则按内容流的原始顺序输出，快但可能
     * 出现「先输出页脚再输出正文」这类错乱。默认开启，因为文本可用性优先于速度。
     *
     * <p>扫描件必然失败并抛 {@code ERR_NO_TEXT}——没有文字层，这不是本项能解决的问题，
     * 需要先 OCR。
     */
    public static final Capability TO_TEXT = new Capability(
            "convert.pdf-to-text", "PDF 转文本", "格式转换",
            "提取全部文字为 .txt，支持保留版面顺序与分页标记。",
            List.of(
                    Param.bool("keepLayout", "保留版面位置", true, "勾选后更接近原文排版"),
                    Param.bool("pageBreak", "分页标记", true, "每页之间插入换行分隔")
            ),
            ctx -> {
                try (PDDocument doc = PdfUtil.load(ctx.input(), ctx.str("password"))) {
                    PDFTextStripper stripper = new PDFTextStripper();
                    stripper.setSortByPosition(ctx.bool("keepLayout", true));
                    stripper.setLineSeparator(System.lineSeparator());
                    StringBuilder sb = new StringBuilder();
                    int total = doc.getNumberOfPages();
                    boolean pageBreak = ctx.bool("pageBreak", true);
                    for (int p = 1; p <= total; p++) {
                        ctx.report(5 + p * 90 / total, "提取第 " + p + " 页");
                        stripper.setStartPage(p);
                        stripper.setEndPage(p);
                        sb.append(stripper.getText(doc));
                        if (pageBreak && p < total) {
                            sb.append(System.lineSeparator()).append("—— 第 ").append(p).append(" 页结束 ——")
                                    .append(System.lineSeparator()).append(System.lineSeparator());
                        }
                    }
                    String text = sb.toString();
                    if (text.isBlank()) {
                        throw new Err("ERR_NO_TEXT", "没有提取到文字。这可能是扫描件（图片型 PDF），需要先做 OCR。");
                    }
                    Path out = ctx.outDerived("", ".txt");
                    Files.writeString(out, text, StandardCharsets.UTF_8);
                    ctx.addOutput(out);
                    ctx.report(100, "提取完成，共 " + text.length() + " 字符");
                }
            });

    /**
     * 提取内嵌图片：把 PDF 里的位图对象原样导出。
     *
     * <p><b>与「PDF 转图片」的区别</b>：本项拿到的是文档里真实嵌入的原图（保持原始像素，
     * 不做二次压缩，也不会被页面裁剪）；后者是「渲染页面」。想取出照片素材用本项，
     * 想要页面截图用前者。
     *
     * <p><b>按 COS 对象去重</b>：同一张图常被多页共享（如每页的信纸底图），
     * 用 {@code getCOSObject().toString()} 作 key 能让它只导出一次。用 toString 而不是
     * 对象引用，是因为每次 {@code getXObject} 返回的包装对象是新建的、引用不相等。
     * 代价是同一图片的两份副本（内容相同但对象不同）会被当成两张。
     *
     * <p><b>两个已知边界</b>：① {@code getImage()} 返回 null 的格式（CCITT 传真、
     * JPX 等）会被静默跳过；② <b>内联图像（inline image）不在 XObject 资源里，提不到</b>。
     * 所以「提取到 0 张」不等于文档真的没有图。
     */
    public static final Capability EXTRACT_IMAGES = new Capability(
            "convert.extract-images", "提取内嵌图片", "格式转换",
            "把 PDF 里嵌入的原图逐张导出（保持原始像素，不做二次压缩）。",
            List.of(
                    Param.choice("format", "格式", "PNG", "PNG", "JPG"),
                    Param.num("minSize", "最小边长(px)", "32", "0-2000，过滤图标类小图")
            ),
            ctx -> {
                String format = ctx.str("format", "PNG").toUpperCase(Locale.ROOT);
                int minSize = ctx.intVal("minSize", 32, 0, 2000);
                try (PDDocument doc = PdfUtil.load(ctx.input(), ctx.str("password"))) {
                    String base = PdfUtil.baseName(ctx.input());
                    Path folder = ctx.outDir().resolve(base + "_图片");
                    Files.createDirectories(folder);
                    int total = doc.getNumberOfPages();
                    int n = 0;
                    java.util.Set<String> seen = new java.util.HashSet<>();
                    int pageNo = 0;
                    for (PDPage page : doc.getPages()) {
                        pageNo++;
                        ctx.report(5 + pageNo * 90 / total, "扫描第 " + pageNo + "/" + total + " 页");
                        var res = page.getResources();
                        if (res == null) {
                            continue;
                        }
                        for (var name : res.getXObjectNames()) {
                            ctx.checkCancel();
                            var xobj = res.getXObject(name);
                            if (!(xobj instanceof PDImageXObject img)) {
                                continue;
                            }
                            String key = img.getCOSObject().toString();
                            if (!seen.add(key)) {
                                continue;
                            }
                            try {
                                // getImage() 会真正解码像素，是整个流程最慢的一步
                                BufferedImage bi = img.getImage();
                                // 传给 getImage() 的结果可能是 null（不支持的编码），
                                // 按长边过滤是为了跳过图标、项目符号这类装饰小图
                                if (bi == null || Math.max(bi.getWidth(), bi.getHeight()) < minSize) {
                                    continue;
                                }
                                // 命名带页号与序号：p003_02 表示第 3 页的第 2 张图，
                                // 按文件名排序即为文档顺序
                                String fileName = String.format("%s_p%03d_%02d.%s",
                                        base, pageNo, ++n, format.toLowerCase(Locale.ROOT));
                                Path target = folder.resolve(fileName);
                                if ("JPG".equals(format)) {
                                    // 提取场景质量给到 92：用户要的是素材，不是压缩品
                                    writeJpeg(bi, target, 92);
                                } else {
                                    ImageIO.write(bi, "png", target.toFile());
                                }
                                ctx.addOutput(target);
                            } catch (Exception e) {
                                // 单张解码失败（损坏的图、罕见的色彩空间）不影响其余图片。
                                // 这里刻意不报错也不记录日志：一个 300 页的文档里坏几张图很常见，
                                // 中断整次提取对用户毫无帮助
                            }
                        }
                    }
                    if (n == 0) {
                        throw new Err("ERR_NO_IMAGE", "这个 PDF 里没有可提取的位图（可能是纯文字或纯矢量文档）");
                    }
                    ctx.report(100, "导出 " + n + " 张图片到 " + folder.getFileName());
                }
            });

    // ---------- 辅助 ----------

    /**
     * 用指定质量写出 JPEG。
     *
     * <p><b>为什么不能直接 {@code ImageIO.write(img, "jpg", file)}</b>：那个默认重载
     * 按质量 0.75 写，无法调整。要控制质量必须自己拿 writer、设
     * {@code MODE_EXPLICIT} 再写——注意 {@code setCompressionMode} 漏掉的话，
     * {@code setCompressionQuality} 会被静默忽略，产物仍是默认质量。
     *
     * <p><b>先转 RGB 并铺白底</b>：JPEG 编码器遇到带 alpha 的图会抛
     * {@code UnsupportedOperationException}（「Bogus input colorspace」之类），
     * 转 RGB 时透明区域必须填白，否则会变成黑块。
     *
     * @param quality 0–100 的整数，不是 0–1 的浮点
     */
    static void writeJpeg(BufferedImage img, Path target, int quality) {
        try {
            BufferedImage rgb = img;
            if (img.getType() != BufferedImage.TYPE_INT_RGB) {
                rgb = new BufferedImage(img.getWidth(), img.getHeight(), BufferedImage.TYPE_INT_RGB);
                var g = rgb.getGraphics();
                g.setColor(java.awt.Color.WHITE);
                g.fillRect(0, 0, img.getWidth(), img.getHeight());
                g.drawImage(img, 0, 0, null);
                g.dispose();
            }
            var writer = ImageIO.getImageWritersByFormatName("jpg").next();
            var param = writer.getDefaultWriteParam();
            param.setCompressionMode(javax.imageio.ImageWriteParam.MODE_EXPLICIT);
            param.setCompressionQuality(quality / 100f);
            try (var ios = ImageIO.createImageOutputStream(target.toFile())) {
                writer.setOutput(ios);
                writer.write(null, new javax.imageio.IIOImage(rgb, null, null), param);
            } finally {
                writer.dispose();
            }
        } catch (Exception e) {
            throw new Err("ERR_WRITE_IMAGE", "写入图片失败：" + target.getFileName(), e);
        }
    }
}
