package io.pdfkit.ops;

import io.pdfkit.core.Capability;
import io.pdfkit.core.Ctx;
import io.pdfkit.core.Err;
import io.pdfkit.core.Param;
import io.pdfkit.util.Fonts;
import io.pdfkit.util.PdfUtil;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.PDResources;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.common.PDStream;
import org.apache.pdfbox.pdmodel.font.PDFont;
import org.apache.pdfbox.pdmodel.graphics.image.JPEGFactory;
import org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject;
import org.apache.pdfbox.pdmodel.graphics.state.PDExtendedGraphicsState;
import org.apache.pdfbox.rendering.ImageType;
import org.apache.pdfbox.rendering.PDFRenderer;

import javax.imageio.ImageIO;
import java.awt.AlphaComposite;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Random;

/** 编辑增强：印章、插入图片、反色、扫描件效果。 */
public final class EditPlusOps {

    private EditPlusOps() {
    }

    public static final Capability STAMP = new Capability(
            "edit.stamp", "添加印章", "内容编辑",
            "在指定位置盖文字印章（圆形红章样式），可调大小与颜色。",
            List.of(
                    Param.text("text", "印章文字", "内部审批", "建议 2-6 个字"),
                    Param.choice("position", "位置", "右下角", "右下角", "左下角", "右上角", "左上角", "居中"),
                    Param.num("size", "印章直径(mm)", "35", "15-80"),
                    Param.choice("color", "颜色", "红色", "红色", "蓝色", "黑色"),
                    Param.num("opacity", "透明度(%)", "85", "20-100"),
                    Param.text("pages", "作用页", "", "留空=全部页")
            ),
            ctx -> {
                String text = ctx.str("text", "内部审批");
                float mm = 72f / 25.4f;
                float diameter = ctx.intVal("size", 35, 15, 80) * mm;
                float alpha = ctx.intVal("opacity", 85, 20, 100) / 100f;
                Color color = switch (ctx.str("color", "红色")) {
                    case "蓝色" -> new Color(30, 80, 180);
                    case "黑色" -> Color.BLACK;
                    default -> new Color(200, 30, 40);
                };
                String pos = ctx.str("position", "右下角");

                try (PDDocument doc = PdfUtil.load(ctx.input(), ctx.str("password"))) {
                    PDFont font = Fonts.chinese(doc);
                    List<Integer> pages = PdfUtil.parsePageSpec(ctx.str("pages"), doc.getNumberOfPages());
                    float fontSize = Math.min(diameter / Math.max(2, text.length()) * 1.2f, diameter / 2.2f);
                    int i = 0;
                    for (int p : pages) {
                        ctx.report(10 + i++ * 85 / pages.size(), "盖章：第 " + p + " 页");
                        PDPage page = doc.getPage(p - 1);
                        PDRectangle box = page.getCropBox();
                        float margin = 30;
                        float cx;
                        float cy;
                        if (pos.contains("左")) {
                            cx = box.getLowerLeftX() + margin + diameter / 2;
                        } else if (pos.contains("右")) {
                            cx = box.getUpperRightX() - margin - diameter / 2;
                        } else {
                            cx = box.getLowerLeftX() + box.getWidth() / 2;
                        }
                        if (pos.contains("上")) {
                            cy = box.getUpperRightY() - margin - diameter / 2;
                        } else if (pos.contains("下")) {
                            cy = box.getLowerLeftY() + margin + diameter / 2;
                        } else {
                            cy = box.getLowerLeftY() + box.getHeight() / 2;
                        }
                        try (PDPageContentStream cs = new PDPageContentStream(doc, page,
                                PDPageContentStream.AppendMode.APPEND, true, true)) {
                            PDExtendedGraphicsState gs = new PDExtendedGraphicsState();
                            gs.setNonStrokingAlphaConstant(alpha);
                            gs.setStrokingAlphaConstant(alpha);
                            cs.setGraphicsStateParameters(gs);
                            cs.setStrokingColor(color);
                            cs.setNonStrokingColor(color);
                            cs.setLineWidth(2.4f);
                            circle(cs, cx, cy, diameter / 2);
                            cs.stroke();
                            cs.setLineWidth(0.8f);
                            circle(cs, cx, cy, diameter / 2 - 4);
                            cs.stroke();
                            float tw = font.getStringWidth(text) / 1000f * fontSize;
                            cs.beginText();
                            cs.setFont(font, fontSize);
                            cs.newLineAtOffset(cx - tw / 2, cy - fontSize / 3);
                            cs.showText(text);
                            cs.endText();
                        }
                    }
                    Path out = ctx.outDerived("_盖章", ".pdf");
                    PdfUtil.save(doc, out);
                    ctx.addOutput(out);
                    ctx.report(100, "已盖章 " + pages.size() + " 页");
                }
            });

    public static final Capability ADD_IMAGE = new Capability(
            "edit.add-image", "插入图片", "内容编辑",
            "在页面指定位置插入图片（签名、logo、二维码等），支持等比缩放。",
            List.of(
                    Param.text("file", "图片路径", "", "PNG/JPG，建议透明背景 PNG"),
                    Param.choice("position", "位置", "右下角", "右下角", "左下角", "右上角", "左上角", "居中"),
                    Param.num("width", "宽度(mm)", "40", "5-300"),
                    Param.num("margin", "边距(mm)", "20", "0-100"),
                    Param.text("pages", "作用页", "1", "留空=全部页")
            ),
            ctx -> {
                ctx.input(); // 先确认已选文件
                String f = ctx.str("file");
                if (f.isBlank()) {
                    throw new Err("ERR_BAD_PARAM", "请填写图片路径");
                }
                Path imgPath = Path.of(f);
                if (!Files.isReadable(imgPath)) {
                    throw new Err("ERR_NO_FILE", "找不到图片：" + imgPath);
                }
                BufferedImage bi;
                try {
                    bi = ImageIO.read(imgPath.toFile());
                } catch (Exception e) {
                    throw new Err("ERR_BAD_IMAGE", "无法读取图片：" + e.getMessage(), e);
                }
                if (bi == null) {
                    throw new Err("ERR_BAD_IMAGE", "不支持的图片格式：" + imgPath.getFileName());
                }
                float mm = 72f / 25.4f;
                float drawW = ctx.intVal("width", 40, 5, 300) * mm;
                float drawH = drawW * bi.getHeight() / bi.getWidth();
                float margin = ctx.intVal("margin", 20, 0, 100) * mm;
                String pos = ctx.str("position", "右下角");

                try (PDDocument doc = PdfUtil.load(ctx.input(), ctx.str("password"))) {
                    List<Integer> pages = PdfUtil.parsePageSpec(ctx.str("pages", "1"), doc.getNumberOfPages());
                    PDImageXObject pdImg = JPEGFactory.createFromImage(doc, toRgb(bi), 0.92f);
                    int i = 0;
                    for (int p : pages) {
                        ctx.report(10 + i++ * 85 / pages.size(), "插入图片：第 " + p + " 页");
                        PDPage page = doc.getPage(p - 1);
                        PDRectangle box = page.getCropBox();
                        float x = pos.contains("左") ? box.getLowerLeftX() + margin
                                : pos.contains("右") ? box.getUpperRightX() - margin - drawW
                                : box.getLowerLeftX() + (box.getWidth() - drawW) / 2;
                        float y = pos.contains("上") ? box.getUpperRightY() - margin - drawH
                                : pos.contains("下") ? box.getLowerLeftY() + margin
                                : box.getLowerLeftY() + (box.getHeight() - drawH) / 2;
                        try (PDPageContentStream cs = new PDPageContentStream(doc, page,
                                PDPageContentStream.AppendMode.APPEND, true, true)) {
                            cs.drawImage(pdImg, x, y, drawW, drawH);
                        }
                    }
                    Path out = ctx.outDerived("_加图", ".pdf");
                    PdfUtil.save(doc, out);
                    ctx.addOutput(out);
                    ctx.report(100, "已插入图片 " + pages.size() + " 页");
                }
            });

    public static final Capability INVERT = new Capability(
            "edit.invert", "反色 / 夜间阅读", "内容编辑",
            "把页面转为深底浅色，适合夜间阅读。注意：处理后该页变为图片，文字不可搜索。",
            List.of(
                    Param.choice("mode", "模式", "反色", "反色", "去色（灰度）", "增强对比度"),
                    Param.num("dpi", "清晰度", "150", "100-300"),
                    Param.text("pages", "作用页", "", "留空=全部页")
            ),
            ctx -> {
                String mode = ctx.str("mode", "反色");
                int dpi = ctx.intVal("dpi", 150, 100, 300);
                try (PDDocument doc = PdfUtil.load(ctx.input(), ctx.str("password"))) {
                    List<Integer> pages = PdfUtil.parsePageSpec(ctx.str("pages"), doc.getNumberOfPages());
                    renderTransform(doc, pages, dpi, ctx, img -> {
                        BufferedImage out = new BufferedImage(img.getWidth(), img.getHeight(), BufferedImage.TYPE_INT_RGB);
                        for (int y = 0; y < img.getHeight(); y++) {
                            for (int x = 0; x < img.getWidth(); x++) {
                                int rgb = img.getRGB(x, y);
                                int r = (rgb >> 16) & 0xFF;
                                int g = (rgb >> 8) & 0xFF;
                                int b = rgb & 0xFF;
                                if ("去色（灰度）".equals(mode)) {
                                    int gray = (int) (0.299 * r + 0.587 * g + 0.114 * b);
                                    out.setRGB(x, y, (gray << 16) | (gray << 8) | gray);
                                } else if ("增强对比度".equals(mode)) {
                                    out.setRGB(x, y, (clamp(r) << 16) | (clamp(g) << 8) | clamp(b));
                                } else {
                                    out.setRGB(x, y, ((255 - r) << 16) | ((255 - g) << 8) | (255 - b));
                                }
                            }
                        }
                        return out;
                    });
                    Path out = ctx.outDerived("_" + mode, ".pdf");
                    PdfUtil.save(doc, out);
                    ctx.addOutput(out);
                    ctx.report(100, mode + "完成");
                }
            });

    public static final Capability SCAN_EFFECT = new Capability(
            "edit.scan-effect", "做旧扫描件效果", "内容编辑",
            "给页面加上扫描质感（灰底、噪点、轻微倾斜），用于需要「看起来像扫描件」的场景。",
            List.of(
                    Param.num("dpi", "清晰度", "150", "100-300"),
                    Param.num("tilt", "倾斜角度(度)", "1", "0-5"),
                    Param.num("noise", "噪点强度", "8", "0-30"),
                    Param.bool("grayBackground", "灰色纸底", true, "")
            ),
            ctx -> {
                int dpi = ctx.intVal("dpi", 150, 100, 300);
                float tilt = ctx.intVal("tilt", 1, 0, 5);
                int noise = ctx.intVal("noise", 8, 0, 30);
                boolean gray = ctx.bool("grayBackground", true);
                Random rnd = new Random(42);
                try (PDDocument doc = PdfUtil.load(ctx.input(), ctx.str("password"))) {
                    List<Integer> pages = PdfUtil.parsePageSpec("", doc.getNumberOfPages());
                    renderTransform(doc, pages, dpi, ctx, img -> {
                        BufferedImage out = new BufferedImage(img.getWidth(), img.getHeight(), BufferedImage.TYPE_INT_RGB);
                        Graphics2D g = out.createGraphics();
                        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
                        g.setColor(gray ? new Color(242, 240, 235) : Color.WHITE);
                        g.fillRect(0, 0, out.getWidth(), out.getHeight());
                        double rad = Math.toRadians(tilt);
                        g.rotate(rad, out.getWidth() / 2.0, out.getHeight() / 2.0);
                        int shrink = (int) (out.getWidth() * 0.01);
                        g.drawImage(img, shrink, shrink, out.getWidth() - shrink * 2, out.getHeight() - shrink * 2, null);
                        g.dispose();
                        if (noise > 0) {
                            for (int k = 0; k < out.getWidth() * out.getHeight() / 900; k++) {
                                int x = rnd.nextInt(out.getWidth());
                                int y = rnd.nextInt(out.getHeight());
                                int rgb = out.getRGB(x, y);
                                int delta = rnd.nextInt(noise * 2 + 1) - noise;
                                int r = clamp(((rgb >> 16) & 0xFF) + delta);
                                int gg = clamp(((rgb >> 8) & 0xFF) + delta);
                                int b = clamp((rgb & 0xFF) + delta);
                                out.setRGB(x, y, (r << 16) | (gg << 8) | b);
                            }
                        }
                        return out;
                    });
                    Path out = ctx.outDerived("_扫描件", ".pdf");
                    PdfUtil.save(doc, out);
                    ctx.addOutput(out);
                    ctx.report(100, "扫描件效果完成");
                }
            });

    public static final Capability HEADER_FOOTER = new Capability(
            "edit.header-footer", "页眉页脚", "内容编辑",
            "在页眉/页脚左中右六个位置写文字，可放公司名、文档标题、页码。占位符 {page} {pages} {title}。",
            List.of(
                    Param.text("headerLeft", "页眉左", "", ""),
                    Param.text("headerCenter", "页眉中", "", ""),
                    Param.text("headerRight", "页眉右", "", ""),
                    Param.text("footerLeft", "页脚左", "{title}", "{title}=文件名"),
                    Param.text("footerCenter", "页脚中", "", ""),
                    Param.text("footerRight", "页脚右", "{page} / {pages}", ""),
                    Param.num("size", "字号", "9", "6-24"),
                    Param.num("margin", "边距(mm)", "12", "0-40"),
                    Param.bool("skipFirst", "首页不显示", false, "封面页通常不需要"),
                    Param.bool("rule", "页眉页脚画横线", false, "")
            ),
            ctx -> {
                float size = ctx.intVal("size", 9, 6, 24);
                float margin = ctx.intVal("margin", 12, 0, 40) * 72f / 25.4f;
                boolean skipFirst = ctx.bool("skipFirst", false);
                boolean rule = ctx.bool("rule", false);
                String title = PdfUtil.baseName(ctx.input());
                int total = 0;
                String[][] slots = {
                        {ctx.str("headerLeft"), ctx.str("headerCenter"), ctx.str("headerRight")},
                        {ctx.str("footerLeft"), ctx.str("footerCenter"), ctx.str("footerRight")}
                };
                if (slots[0][0].isBlank() && slots[0][1].isBlank() && slots[0][2].isBlank()
                        && slots[1][0].isBlank() && slots[1][1].isBlank() && slots[1][2].isBlank()) {
                    throw new Err("ERR_BAD_PARAM", "页眉和页脚至少填一个位置");
                }
                try (PDDocument doc = PdfUtil.load(ctx.input(), ctx.str("password"))) {
                    total = doc.getNumberOfPages();
                    PDFont font = Fonts.chinese(doc);
                    int i = 0;
                    for (int p = 1; p <= total; p++) {
                        if (skipFirst && p == 1) {
                            continue;
                        }
                        ctx.report(5 + i++ * 90 / total, "第 " + p + " 页");
                        PDPage page = doc.getPage(p - 1);
                        PDRectangle box = page.getCropBox();
                        try (PDPageContentStream cs = new PDPageContentStream(doc, page,
                                PDPageContentStream.AppendMode.APPEND, true, true)) {
                            for (int row = 0; row < 2; row++) {
                                boolean header = row == 0;
                                float y = header
                                        ? box.getUpperRightY() - margin - size
                                        : box.getLowerLeftY() + margin;
                                boolean any = false;
                                for (int col = 0; col < 3; col++) {
                                    String raw = slots[row][col];
                                    if (raw == null || raw.isBlank()) {
                                        continue;
                                    }
                                    String text = raw.replace("{page}", String.valueOf(p))
                                            .replace("{pages}", String.valueOf(total))
                                            .replace("{title}", title);
                                    float tw = font.getStringWidth(text) / 1000f * size;
                                    float x;
                                    if (col == 0) {
                                        x = box.getLowerLeftX() + margin;
                                    } else if (col == 2) {
                                        x = box.getUpperRightX() - margin - tw;
                                    } else {
                                        x = box.getLowerLeftX() + (box.getWidth() - tw) / 2;
                                    }
                                    cs.beginText();
                                    cs.setFont(font, size);
                                    cs.setNonStrokingColor(new Color(90, 90, 90));
                                    cs.newLineAtOffset(x, y);
                                    cs.showText(text);
                                    cs.endText();
                                    any = true;
                                }
                                if (rule && any) {
                                    float ly = header ? box.getUpperRightY() - margin + 4 : box.getLowerLeftY() + margin + size + 4;
                                    cs.setStrokingColor(new Color(180, 180, 180));
                                    cs.setLineWidth(0.5f);
                                    cs.moveTo(box.getLowerLeftX() + margin, ly);
                                    cs.lineTo(box.getUpperRightX() - margin, ly);
                                    cs.stroke();
                                }
                            }
                        }
                    }
                    Path out = ctx.outDerived("_页眉页脚", ".pdf");
                    PdfUtil.save(doc, out);
                    ctx.addOutput(out);
                    ctx.report(100, "已处理 " + total + " 页");
                }
            });

    // ---------- 共用 ----------

    interface ImageOp {
        BufferedImage apply(BufferedImage src);
    }

    static void renderTransform(PDDocument doc, List<Integer> pages, int dpi, Ctx ctx, ImageOp op) throws Exception {
        PDFRenderer renderer = new PDFRenderer(doc);
        int i = 0;
        for (int p : pages) {
            ctx.checkCancel();
            ctx.report(5 + i++ * 90 / pages.size(), "处理第 " + p + " 页");
            PDPage page = doc.getPage(p - 1);
            PDRectangle box = page.getCropBox();
            BufferedImage img = renderer.renderImageWithDPI(p - 1, dpi, ImageType.RGB);
            BufferedImage out = op.apply(img);
            page.setContents(new PDStream(doc));
            page.setResources(new PDResources());
            PDImageXObject pdImg = JPEGFactory.createFromImage(doc, out, 0.88f);
            try (PDPageContentStream cs = new PDPageContentStream(doc, page,
                    PDPageContentStream.AppendMode.APPEND, true, true)) {
                cs.drawImage(pdImg, box.getLowerLeftX(), box.getLowerLeftY(), box.getWidth(), box.getHeight());
            }
        }
    }

    static BufferedImage toRgb(BufferedImage img) {
        if (img.getType() == BufferedImage.TYPE_INT_RGB) {
            return img;
        }
        BufferedImage out = new BufferedImage(img.getWidth(), img.getHeight(), BufferedImage.TYPE_INT_RGB);
        Graphics2D g = out.createGraphics();
        g.setComposite(AlphaComposite.Src);
        g.setColor(Color.WHITE);
        g.fillRect(0, 0, img.getWidth(), img.getHeight());
        g.drawImage(img, 0, 0, null);
        g.dispose();
        return out;
    }

    static int clamp(int v) {
        return Math.max(0, Math.min(255, v));
    }

    /** PDFBox 3 没有 addCircle，用四段三次贝塞尔逼近圆。 */
    static void circle(PDPageContentStream cs, float cx, float cy, float r) throws java.io.IOException {
        float k = 0.5523f * r;
        cs.moveTo(cx + r, cy);
        cs.curveTo(cx + r, cy + k, cx + k, cy + r, cx, cy + r);
        cs.curveTo(cx - k, cy + r, cx - r, cy + k, cx - r, cy);
        cs.curveTo(cx - r, cy - k, cx - k, cy - r, cx, cy - r);
        cs.curveTo(cx + k, cy - r, cx + r, cy - k, cx + r, cy);
        cs.closePath();
    }
}
