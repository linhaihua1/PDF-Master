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

/** 转换类能力：PDF→图片、图片→PDF、PDF→文本。 */
public final class ConvertOps {

    private ConvertOps() {
    }

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
                    renderer.setSubsamplingAllowed(true);
                    String base = PdfUtil.baseName(ctx.input());
                    boolean single = pages.size() == 1;
                    Path folder = single ? ctx.outDir() : ctx.outDir().resolve(base + "_图片");
                    if (!single) {
                        Files.createDirectories(folder);
                    }
                    int i = 0;
                    for (int p : pages) {
                        ctx.report(5 + i * 90 / pages.size(), "渲染第 " + p + " 页");
                        BufferedImage img = renderer.renderImageWithDPI(p - 1, dpi, ImageType.RGB);
                        String name = single
                                ? base + "." + format.toLowerCase(Locale.ROOT)
                                : String.format("%s_%03d.%s", base, p, format.toLowerCase(Locale.ROOT));
                        Path target = single ? ctx.out(name) : folder.resolve(name);
                        if ("JPG".equals(format)) {
                            writeJpeg(img, target, quality);
                        } else {
                            ImageIO.write(img, "png", target.toFile());
                        }
                        i++;
                    }
                    if (single) {
                        ctx.addOutput(folder);
                    } else {
                        for (Path f : Files.list(folder).toList()) {
                            ctx.addOutput(f);
                        }
                    }
                    ctx.report(100, "导出 " + pages.size() + " 张图片");
                }
            });

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
                        PDImageXObject pdImg = compress
                                ? JPEGFactory.createFromImage(doc, img, 0.85f)
                                : LosslessFactory.createFromImage(doc, img);

                        PDRectangle pageSize;
                        if ("A4 竖版".equals(paper)) {
                            pageSize = PDRectangle.A4;
                        } else if ("A4 横版".equals(paper)) {
                            pageSize = new PDRectangle(PDRectangle.A4.getHeight(), PDRectangle.A4.getWidth());
                        } else {
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
                                BufferedImage bi = img.getImage();
                                if (bi == null || Math.max(bi.getWidth(), bi.getHeight()) < minSize) {
                                    continue;
                                }
                                String fileName = String.format("%s_p%03d_%02d.%s",
                                        base, pageNo, ++n, format.toLowerCase(Locale.ROOT));
                                Path target = folder.resolve(fileName);
                                if ("JPG".equals(format)) {
                                    writeJpeg(bi, target, 92);
                                } else {
                                    ImageIO.write(bi, "png", target.toFile());
                                }
                                ctx.addOutput(target);
                            } catch (Exception e) {
                                // 单张图片解码失败不影响其他图片
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
