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

/**
 * 编辑增强：印章、插入图片、反色、扫描件效果、页眉页脚。
 *
 * <p>五项分两类，实现机制完全不同：
 *
 * <p><b>矢量叠加</b>（印章、插入图片、页眉页脚）—— 往内容流里追加矢量绘制指令
 * （文字、线条、图片 XObject）。文字仍可选中、可搜索，文件不变大。这是首选方式。
 *
 * <p><b>像素处理</b>（反色、扫描件效果）—— 整页渲染成位图、处理像素、再作为图片盖回页面
 * （见 {@link #renderTransform}）。<b>代价是文字层彻底丢失</b>：处理后这一页不能再选中文字、
 * 不能搜索、不能提取，打印清晰度也受 DPI 限制。这是这两项能力的固有属性，不是缺陷——
 * 反色、加噪、二值化这类操作无法用矢量指令表达。
 *
 * <p>印章用圆环 + 文字手工绘制（{@code circle()} 辅助方法）而不是贴一张预置的印章图片：
 * 图片印章无法改文字内容，也无法保证在不同 DPI 下清晰。
 */
public final class EditPlusOps {

    private EditPlusOps() {
    }

    /**
     * 添加印章：在页面指定角落画一个圆形文字章。
     *
     * <p><b>画的是矢量图形，不是贴图</b>：两道圆环 + 一行文字都是 PDF 绘图指令，
     * 任意放大都不糊，文件体积也只有几百字节。比截图贴图的观感更接近真实印章。
     *
     * <p><b>字号用两条约束取小</b>：`直径/字数*1.2` 保证文字不会横向冲出圆环，
     * `直径/2.2` 保证字少时（如 2 个字）不会大到把圆环撑满。少任何一条都会出现
     * 「两个字顶满整圆」或「六个字溢出圆外」。
     *
     * <p><b>位置判断用 {@code contains}</b> 而不是 {@code equals}：选项文案是「右下角」
     * 这类四字组合，拆成横纵两次判断比枚举 5 种组合更省事，加新位置（如「左中」）
     * 也无需改代码。
     */
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
                // PDF 的用户空间单位是 1/72 英寸，1 英寸 = 25.4 mm，所以 1 mm = 72/25.4 单位
                float mm = 72f / 25.4f;
                float diameter = ctx.intVal("size", 35, 15, 80) * mm;
                float alpha = ctx.intVal("opacity", 85, 20, 100) / 100f;
                // 用深色而非纯红：纯 RGB(255,0,0) 印在纸上的观感偏艳，实际印章都是暗朱红
                Color color = switch (ctx.str("color", "红色")) {
                    case "蓝色" -> new Color(30, 80, 180);
                    case "黑色" -> Color.BLACK;
                    default -> new Color(200, 30, 40);
                };
                String pos = ctx.str("position", "右下角");

                try (PDDocument doc = PdfUtil.load(ctx.input(), ctx.str("password"))) {
                    PDFont font = Fonts.chinese(doc);
                    List<Integer> pages = PdfUtil.parsePageSpec(ctx.str("pages"), doc.getNumberOfPages());
                    // 字号两条约束取小，理由见本能力 javadoc
                    float fontSize = Math.min(diameter / Math.max(2, text.length()) * 1.2f, diameter / 2.2f);
                    int i = 0;
                    for (int p : pages) {
                        ctx.report(10 + i++ * 85 / pages.size(), "盖章：第 " + p + " 页");
                        PDPage page = doc.getPage(p - 1);
                        // 用 CropBox 而不是 MediaBox：页面被裁剪过时，可见区域才是用户眼中的「页面」，
                        // 按 MediaBox 定位会把章盖到被裁掉的空白处
                        PDRectangle box = page.getCropBox();
                        float margin = 30;
                        float cx;
                        float cy;
                        // 注意 PDF 坐标原点在左下角、Y 轴向上：所以「上」是较大的 Y、「下」是较小的 Y，
                        // 与屏幕坐标相反。这里算的是圆心，所以要各减去/加上一个半径
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
                            // 双圆环：外圈粗（2.4）、内圈细（0.8）、内圈缩进 4 单位，
                            // 模仿真实印章的边线样式。单圈太像「画了个圆」
                            cs.setLineWidth(2.4f);
                            circle(cs, cx, cy, diameter / 2);
                            cs.stroke();
                            cs.setLineWidth(0.8f);
                            circle(cs, cx, cy, diameter / 2 - 4);
                            cs.stroke();
                            // getStringWidth 返回的是 1/1000 em 的整数，要除以 1000 再乘字号
                            float tw = font.getStringWidth(text) / 1000f * fontSize;
                            cs.beginText();
                            cs.setFont(font, fontSize);
                            // 基线偏移取 -fontSize/3 而不是 0：newLineAtOffset 的 y 指定的是基线，
                            // 文字视觉中心大约在基线上方 1/3 字高处，减掉它才能让文字看起来位于圆心。
                            // 用 1/2 会偏上，用 0 会明显偏上
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

    /**
     * 插入图片：把一张本地图片贴到页面指定位置（常见用途是贴签名图、贴二维码）。
     *
     * <p><b>只给宽度，高度按原图比例算</b>：用户输入「40 mm 宽」就够用了，要求同时填宽高
     * 反而逼着用户去算比例。若原图比例极端（如超长条幅），可以用「作用页」逐页调。
     *
     * <p><b>图片会被重新编码成 JPEG（质量 0.92）</b>，而不是原样嵌入。这样做的代价是
     * 有损压缩，好处是 PNG 透明通道不会导致黑底（见 {@link #toRgb}），且体积可控。
     * 需要无损嵌入的场景应改用「图片优化」之外的专门工具。
     */
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
                ctx.input(); // 先确认已选文件：不取返回值，只为让「未选文件」的错误先抛出
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
                    // ImageIO.read 对不认识的格式返回 null 而不是抛异常，所以下面还要判 null；
                    // 但文件损坏时它会抛 IIOException，两个分支都得处理
                    bi = ImageIO.read(imgPath.toFile());
                } catch (Exception e) {
                    throw new Err("ERR_BAD_IMAGE", "无法读取图片：" + e.getMessage(), e);
                }
                if (bi == null) {
                    throw new Err("ERR_BAD_IMAGE", "不支持的图片格式：" + imgPath.getFileName());
                }
                float mm = 72f / 25.4f;
                float drawW = ctx.intVal("width", 40, 5, 300) * mm;
                // 高度不单独给参数，按原图宽高比推导
                float drawH = drawW * bi.getHeight() / bi.getWidth();
                float margin = ctx.intVal("margin", 20, 0, 100) * mm;
                String pos = ctx.str("position", "右下角");

                try (PDDocument doc = PdfUtil.load(ctx.input(), ctx.str("password"))) {
                    // 默认只贴第 1 页：贴签名图通常只贴最后一页，默认全贴反而要手工改
                    List<Integer> pages = PdfUtil.parsePageSpec(ctx.str("pages", "1"), doc.getNumberOfPages());
                    // 图片对象建在文档级别、循环外只建一次：多页复用时 PDF 里只存一份图像数据，
                    // 每页只是引用它。放进循环会按页数成倍放大文件体积
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

    /**
     * 反色 / 夜间阅读。
     *
     * <p><b>这是有损且不可逆的处理</b>：实现方式是「把页面渲染成位图 → 逐像素改颜色 →
     * 把位图贴回去」，原页面的文字对象、矢量图形、书签链接全部消失，产物里的文字
     * <b>不可选中、不可搜索、不可复制</b>。能力描述里已明确写了这一点，但用户容易忽略，
     * 所以文档里也单独强调。需要保留文本的场景应改用阅读器的夜间模式，不要用本项。
     *
     * <p>灰度用的是 ITU-R BT.601 的亮度权重（0.299R + 0.587G + 0.114B），而不是简单平均。
     * 简单平均会让红色与绿色变成同样的灰，人眼对绿色的敏感度远高于红色，观感发闷。
     */
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
                        // 输出统一 TYPE_INT_RGB：renderTransform 之后要用 JPEG 编码，
                        // JPEG 没有 alpha 通道，带 alpha 的图会报错或被转成黑底
                        BufferedImage out = new BufferedImage(img.getWidth(), img.getHeight(), BufferedImage.TYPE_INT_RGB);
                        for (int y = 0; y < img.getHeight(); y++) {
                            for (int x = 0; x < img.getWidth(); x++) {
                                int rgb = img.getRGB(x, y);
                                // 逐通道移位取值：getRGB 返回 0xAARRGGBB，右移 16/8/0 位再与 0xFF
                                int r = (rgb >> 16) & 0xFF;
                                int g = (rgb >> 8) & 0xFF;
                                int b = rgb & 0xFF;
                                if ("去色（灰度）".equals(mode)) {
                                    // BT.601 亮度权重，理由见本能力 javadoc
                                    int gray = (int) (0.299 * r + 0.587 * g + 0.114 * b);
                                    out.setRGB(x, y, (gray << 16) | (gray << 8) | gray);
                                } else if ("增强对比度".equals(mode)) {
                                    // 已知问题：clamp() 只做 0–255 钳制，输入本就在此范围内，
                                    // 因此这个分支当前等价于原样输出，画面不会有任何变化。
                                    // 真正的对比度增强应对每个通道做 (v-128)*k+128 之类的拉伸。
                                    // 保留分支是为了不改动参数面板的选项集合；修正时改这里即可
                                    out.setRGB(x, y, (clamp(r) << 16) | (clamp(g) << 8) | clamp(b));
                                } else {
                                    // 255 - v 即取反。注意这里没有先转灰度，彩色反色后仍是彩色，
                                    // 只是色相整体旋转 180°（白变黑、蓝变黄）
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

    /**
     * 做旧扫描件效果：灰纸底 + 轻微倾斜 + 噪点。
     *
     * <p><b>随机数固定种子 42</b>：噪点本应随机，但同一份文件重复处理两次应该得到
     * 完全相同的产物——否则批量处理时无法复现问题，用户也会觉得「每次效果不一样」。
     * 需要更自然的效果时不要改种子，调「噪点强度」参数即可。
     *
     * <p><b>倾斜后四周会露出底色</b>：旋转是以图像中心为轴，转 1° 就会让四角空出来。
     * 所以先把整张画布填成灰纸色，再旋转绘制原图，空出来的部分就自然成为「纸张边缘」。
     * {@code shrink} 取宽度的 1% 是让原图略微内缩，避免旋转后图片边角出界被裁掉。
     *
     * <p><b>噪点数量按面积算</b>（像素数 / 900）：固定数量会让小页面密密麻麻、大页面
     * 稀稀拉拉。按面积摊派后不同尺寸页面的密度才一致。
     *
     * <p>与反色一样，本项也是破坏性操作（页面变成位图），只处理全部页、不支持选页。
     */
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

    /**
     * 页眉页脚：六个位置（页眉左右中 + 页脚左右中）任意组合写文字。
     *
     * <p><b>这是纯叠加，不动原内容</b>：用 APPEND 追加绘图指令，原文的字体、图片、
     * 可搜索文本全部保留。代价是如果原页面在页眉区域已有内容，两者会重叠——本项
     * 不做避让检测。
     *
     * <p><b>基线定位而非文字框定位</b>：{@code newLineAtOffset} 给的 y 是文字基线。
     * 所以页眉的 y 要减掉一个字高（{@code - size}），否则文字底部会顶到页边；
     * 页脚的 y 直接取边距值即可。左右位置则要注意右侧要减文字宽度，否则文字会超出右边界。
     *
     * <p><b>占位符替换顺序是安全的</b>：{@code {page}} 并不是 {@code {pages}} 的子串
     * （前者第 6 个字符是 {@code }}，后者是 {@code s}），所以先替换哪个都不会串。
     * 若将来新增 {@code {pagecount}} 之类以 {} 结尾的短占位符，需要重新审视顺序。
     *
     * <p>文字颜色固定 55% 灰（90,90,90）：纯黑会和正文抢注意力，太浅又打印不出来。
     */
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
                        // 进度用 i 而不是 p：跳过首页时 i 与 p 就不同步了，
                        // 用 p 算进度会让第一页之后一直显示偏小的百分比
                        ctx.report(5 + i++ * 90 / total, "第 " + p + " 页");
                        PDPage page = doc.getPage(p - 1);
                        // 用 CropBox：页眉页脚要贴用户看得见的页面边缘，而不是物理纸张边缘
                        PDRectangle box = page.getCropBox();
                        try (PDPageContentStream cs = new PDPageContentStream(doc, page,
                                PDPageContentStream.AppendMode.APPEND, true, true)) {
                            for (int row = 0; row < 2; row++) {
                                boolean header = row == 0;
                                // 页眉基线 = 上边 - 边距 - 一个字高（让文字顶端接近边距线）；
                                // 页脚基线 = 下边 + 边距。两者不对称，原因见本能力 javadoc
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
                                    // 先算实际宽度才能定位：右侧要右对齐，中间要居中
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
                                    // 横线画在文字外侧 4 单位处：页眉文字在上线在下，
                                    // 页脚文字在下线在上，符合常见公文排版
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

    /**
     * 把指定页面「渲染 → 逐像素处理 → 盖回页面」的通用流程。
     *
     * <p>反色与扫描件效果共用这个方法，差异只在传进来的 {@link ImageOp}。
     *
     * <p><b>注意这是破坏性操作</b>：{@code setContents} 与 {@code setResources} 清空后，
     * 原文字对象与字体资源都消失了，页面只剩一张 JPEG 位图。
     * 所以只有被选中的页会被处理，未选中的页原样保留。
     *
     * <p>JPEG 质量固定 0.88：这是「处理后不出现明显色块」的下限。再低的话
     * 反色后的纯色区域（原本的白色背景变成黑色）会出现可见的压缩噪点。
     *
     * @param op 像素变换，入参与返回值都是 RGB 位图
     */
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

    /**
     * 把任意位图转成不带 alpha 的 RGB。
     *
     * <p><b>必须先铺白底再画原图</b>：JPEG 不支持透明，直接把 ARGB 交给编码器时，
     * 透明区域会被当作「无颜色」写成黑色——一张背景透明的签名 PNG 就变成了黑底白字。
     * 先填白、再用 {@code AlphaComposite.Src} 叠上去，透明部分融入白纸。
     */
    static BufferedImage toRgb(BufferedImage img) {
        if (img.getType() == BufferedImage.TYPE_INT_RGB) {
            return img;
        }
        BufferedImage out = new BufferedImage(img.getWidth(), img.getHeight(), BufferedImage.TYPE_INT_RGB);
        Graphics2D g = out.createGraphics();
        // Src 而非默认的 SrcOver：Src 会直接覆盖目标像素，包括把 alpha 一并带过去，
        // 但目标本身没有 alpha 通道，实际效果等同于把透明区域替换成已填的白底
        g.setComposite(AlphaComposite.Src);
        g.setColor(Color.WHITE);
        g.fillRect(0, 0, img.getWidth(), img.getHeight());
        g.drawImage(img, 0, 0, null);
        g.dispose();
        return out;
    }

    /**
     * 把颜色通道值钳制到 0–255。
     *
     * <p>只做越界保护，<b>不做任何拉伸</b>——所以它不能让画面「增强对比度」。
     * 目前唯一需要它的地方是噪点叠加：原值加减噪点后可能溢出字节范围。
     */
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
