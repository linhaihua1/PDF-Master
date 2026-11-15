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
import org.apache.pdfbox.pdmodel.font.PDFont;
import org.apache.pdfbox.pdmodel.graphics.state.PDExtendedGraphicsState;
import org.apache.pdfbox.util.Matrix;

import java.awt.Color;
import java.nio.file.Path;
import java.util.List;

/**
 * 内容编辑类能力：页码、水印、元数据、展平。
 *
 * <p>前三项都是「在已有页面上加东西」，共用同一套机制：
 * 用 {@link PDPageContentStream} 以<b>追加模式</b>打开页面内容流，画完关闭。
 *
 * <p><b>追加模式的三个参数必须都传对</b>（{@code AppendMode.APPEND, true, true}）：
 * <ol>
 *   <li>{@code APPEND} —— 新内容追加到原内容之后，不覆盖。用 {@code OVERWRITE}
 *       会把页面原有内容整个清掉，只剩你新加的水印。</li>
 *   <li>{@code resetContext = true} —— 重置图形状态（颜色、线宽、变换矩阵）。
 *       传 false 的话，页面原有内容流末尾若残留一个旋转矩阵或裁剪区域，
 *       你画的页码会跟着被旋转、被裁掉。</li>
 *   <li>{@code compress = true} —— 压缩新增内容流。水印平铺时内容量不小，不压缩会让
 *       文件明显变大。</li>
 * </ol>
 *
 * <p><b>字宽换算是本类的高频陷阱</b>：{@code font.getStringWidth(text)} 返回的是
 * 「千分之一 em」为单位的整数，实际宽度要 {@code / 1000f * 字号}。少除 1000 会让
 * 居中计算偏出页面，这是新加文字功能时最容易犯的错。
 */
public final class EditOps {

    /** 工具类，禁止实例化。 */
    private EditOps() {
    }

    // ---------- 页码 ----------

    /**
     * 添加页码，支持中文模板、起始序号、六种位置。
     *
     * <p><b>两个「起始」参数别混淆</b>：
     * <ul>
     *   <li>{@code start} —— 从文档的第几页开始加。用它实现「封面和目录不加页码」。</li>
     *   <li>{@code startNo} —— 第一页上显示的数字是几。用它实现「正文从 1 开始编号，
     *       但前面已经有 5 页封面」的场景。</li>
     * </ul>
     *
     * <p>模板里 {page} 的算法是 {@code startNo + p - startPage}：p 是文档页号，
     * 减去 startPage 得到「这是第几个被编号的页」，再加 startNo 得到显示值。
     * {pages} 同理算总页数，这样「共 N 页」的 N 也是从编号序列里算出的，
     * 不会出现「第 3 页 / 共 20 页」但实际只编了 15 页的错位。
     *
     * <p>{@code margin} 参数单位是毫米（用户直觉），换算成 PDF 的 pt：
     * {@code * 72 / 25.4}（1 英寸 = 72pt = 25.4mm）。
     */
    public static final Capability PAGE_NUMBER = new Capability(
            "edit.page-number", "添加页码", "内容编辑",
            "在页面指定位置插入页码，支持中文模板与起始序号。",
            List.of(
                    Param.choice("position", "位置", "底部居中",
                            "左下角", "底部居中", "右下角", "左上角", "顶部居中", "右上角"),
                    Param.text("template", "页码格式", "第 {page} 页 / 共 {pages} 页",
                            "可用占位符 {page} {pages}"),
                    Param.num("start", "起始页", "1", "从第几页开始加"),
                    Param.num("startNo", "起始序号", "1", "第一页显示为几"),
                    Param.num("size", "字号", "10", "6-36"),
                    Param.num("margin", "边距(mm)", "12", "距离纸张边缘"),
                    Param.text("pages", "作用页", "", "留空=全部页")
            ),
            ctx -> {
                String template = ctx.str("template", "第 {page} 页 / 共 {pages} 页");
                int startPage = ctx.intVal("start", 1, 1, 100000);
                int startNo = ctx.intVal("startNo", 1, 0, 1000000);
                float size = ctx.intVal("size", 10, 6, 36);
                // 毫米转 pt：用户填 12 表示 12mm，PDF 内部用 pt（1/72 英寸）
                float margin = ctx.intVal("margin", 12, 0, 100) * 72f / 25.4f;
                String pos = ctx.str("position", "底部居中");

                try (PDDocument doc = PdfUtil.load(ctx.input(), ctx.str("password"))) {
                    int total = doc.getNumberOfPages();
                    List<Integer> pages = PdfUtil.parsePageSpec(ctx.str("pages"), total);
                    // 页码几乎必然含中文（「第 X 页」），用中文字体。纯数字模板也一样用，
                    // 不必为「用户可能填纯数字」这个边缘情况增加一条分支
                    PDFont font = Fonts.chinese(doc);
                    int i = 0;
                    for (int p : pages) {
                        if (p < startPage) {
                            continue;
                        }
                        ctx.report(10 + i++ * 80 / pages.size(), "添加页码：第 " + p + " 页");
                        String text = template
                                .replace("{page}", String.valueOf(startNo + p - startPage))
                                .replace("{pages}", String.valueOf(startNo + total - startPage));
                        PDPage page = doc.getPage(p - 1);
                        PDRectangle box = page.getCropBox();
                        // 文本宽度：getStringWidth 单位是 1/1000 em，必须除 1000 再乘字号
                        float textWidth = font.getStringWidth(text) / 1000f * size;
                        float x;
                        float y;
                        if (pos.contains("左")) {
                            x = box.getLowerLeftX() + margin;
                        } else if (pos.contains("右")) {
                            x = box.getUpperRightX() - margin - textWidth;
                        } else {
                            x = box.getLowerLeftX() + (box.getWidth() - textWidth) / 2;
                        }
                        if (pos.contains("顶部")) {
                            // 顶部时基线要再往下让一个字高，否则文字会贴出页边
                            y = box.getUpperRightY() - margin - size;
                        } else {
                            y = box.getLowerLeftY() + margin;
                        }
                        // 追加模式 + 重置图形状态 + 压缩，三个参数的含义见类注释
                        try (PDPageContentStream cs = new PDPageContentStream(doc, page,
                                PDPageContentStream.AppendMode.APPEND, true, true)) {
                            cs.beginText();
                            cs.setFont(font, size);
                            cs.newLineAtOffset(x, y);
                            cs.showText(text);
                            cs.endText();
                        }
                    }
                    Path out = ctx.outDerived("_页码", ".pdf");
                    PdfUtil.save(doc, out);
                    ctx.addOutput(out);
                    ctx.report(100, "页码添加完成");
                }
            });

    // ---------- 水印 ----------

    /**
     * 添加文字水印，可调角度、透明度、字号、颜色，支持平铺。
     *
     * <p><b>透明度靠 PDF 的 ExtGState 实现</b>，不是调颜色的 alpha 通道：
     * {@code PDExtendedGraphicsState.setNonStrokingAlphaConstant(alpha)} 对应页面内容流里的
     * {@code /CA} 与 {@code /ca} 参数。必须同时设 non-stroking 和 stroking 两个，
     * 否则文字描边部分仍是不透明的。
     *
     * <p><b>旋转用 {@code Matrix.getRotateInstance}</b> 而不是给每个字单独算坐标：
     * 把旋转矩阵塞进文本状态（{@code setTextMatrix}），旋转中心就是当前文本位置，
     * 后续 {@code showText} 直接生效。比自己算三角函数简单且不会累积误差。
     *
     * <p>平铺的步长（{@code stepX / stepY}）是按字号推算的：横向留 2 个字的间隙，
     * 纵向留 4 个字高。固定像素步长在不同字号下会显得过密或过疏。
     *
     * <p>默认透明度 15%——水印是背景标记，太深会盖住正文。
     */
    public static final Capability WATERMARK = new Capability(
            "edit.watermark", "添加水印", "内容编辑",
            "在页面上叠加文字水印，可调角度、透明度、字号与颜色深浅。",
            List.of(
                    Param.text("text", "水印文字", "内部资料", "支持中文"),
                    Param.num("size", "字号", "48", "8-200"),
                    Param.choice("angle", "旋转角度", "45", "0", "30", "45", "90"),
                    Param.num("opacity", "透明度(%)", "15", "1-100"),
                    Param.choice("color", "颜色", "灰色", "灰色", "红色", "蓝色", "黑色", "浅灰"),
                    Param.bool("tile", "平铺", false, "在整页重复平铺")
            ),
            ctx -> {
                String text = ctx.str("text", "内部资料");
                if (text.isBlank()) {
                    throw new Err("ERR_BAD_PARAM", "水印文字不能为空");
                }
                float size = ctx.intVal("size", 48, 8, 200);
                int angle = ctx.intVal("angle", 45);
                // 百分比转 0–1
                float alpha = ctx.intVal("opacity", 15, 1, 100) / 100f;
                boolean tile = ctx.bool("tile", false);
                // 颜色都调过饱和度：纯红纯蓝印在水印上会抢视线
                Color color = switch (ctx.str("color", "灰色")) {
                    case "红色" -> new Color(200, 30, 30);
                    case "蓝色" -> new Color(30, 80, 180);
                    case "黑色" -> Color.BLACK;
                    case "浅灰" -> new Color(190, 190, 190);
                    default -> new Color(120, 120, 120);
                };

                try (PDDocument doc = PdfUtil.load(ctx.input(), ctx.str("password"))) {
                    PDFont font = Fonts.chinese(doc);
                    int total = doc.getNumberOfPages();
                    for (int i = 0; i < total; i++) {
                        ctx.report(5 + i * 90 / total, "水印：" + (i + 1) + "/" + total);
                        PDPage page = doc.getPage(i);
                        PDRectangle box = page.getCropBox();
                        float textWidth = font.getStringWidth(text) / 1000f * size;
                        try (PDPageContentStream cs = new PDPageContentStream(doc, page,
                                PDPageContentStream.AppendMode.APPEND, true, true)) {
                            PDExtendedGraphicsState gs = new PDExtendedGraphicsState();
                            // 两个都要设：non-stroking 管填充，stroking 管描边
                            gs.setNonStrokingAlphaConstant(alpha);
                            gs.setStrokingAlphaConstant(alpha);
                            cs.setGraphicsStateParameters(gs);
                            cs.setNonStrokingColor(color);
                            cs.setFont(font, size);
                            if (tile) {
                                // 步长按字号推算：横向留 2 字间隙，纵向留 4 字高
                                float stepX = textWidth + size * 2;
                                float stepY = size * 4;
                                for (float y = box.getLowerLeftY(); y < box.getUpperRightY(); y += stepY) {
                                    for (float x = box.getLowerLeftX(); x < box.getUpperRightX(); x += stepX) {
                                        cs.beginText();
                                        cs.setTextMatrix(Matrix.getRotateInstance(Math.toRadians(angle), x, y));
                                        cs.showText(text);
                                        cs.endText();
                                    }
                                }
                            } else {
                                // 居中：页面中心减去文字宽高的一半
                                float cx = box.getLowerLeftX() + box.getWidth() / 2 - textWidth / 2;
                                float cy = box.getLowerLeftY() + box.getHeight() / 2 - size / 2;
                                cs.beginText();
                                cs.setTextMatrix(Matrix.getRotateInstance(Math.toRadians(angle), cx, cy));
                                cs.showText(text);
                                cs.endText();
                            }
                        }
                    }
                    Path out = ctx.outDerived("_水印", ".pdf");
                    PdfUtil.save(doc, out);
                    ctx.addOutput(out);
                    ctx.report(100, "水印添加完成");
                }
            });

    // ---------- 元数据 ----------

    /**
     * 修改文档属性（标题、作者、主题、关键词）。
     *
     * <p>「留空 = 不修改」而不是「留空 = 清空」：用户只想改作者时，不该因为
     * 其它输入框空着就把标题也抹掉。想清空全部有专门的 {@code clear} 开关。
     *
     * <p>清空时连 {@code Creator} 和 {@code Producer} 一起置空——这两项会暴露
     * 「用什么软件做的」，交付敏感文档时用户可能希望一并清掉。
     */
    public static final Capability METADATA = new Capability(
            "edit.metadata", "修改元数据", "内容编辑",
            "修改标题、作者、主题、关键词等文档属性。",
            List.of(
                    Param.text("title", "标题", "", "留空=不修改"),
                    Param.text("author", "作者", "", "留空=不修改"),
                    Param.text("subject", "主题", "", ""),
                    Param.text("keywords", "关键词", "", ""),
                    Param.bool("clear", "清空全部元数据", false, "勾选后忽略上面的值，全部置空")
            ),
            ctx -> {
                try (PDDocument doc = PdfUtil.load(ctx.input(), ctx.str("password"))) {
                    var info = doc.getDocumentInformation();
                    if (ctx.bool("clear", false)) {
                        info.setTitle(null);
                        info.setAuthor(null);
                        info.setSubject(null);
                        info.setKeywords(null);
                        info.setCreator(null);
                        info.setProducer(null);
                    } else {
                        setIfPresent(info::setTitle, ctx.str("title"));
                        setIfPresent(info::setAuthor, ctx.str("author"));
                        setIfPresent(info::setSubject, ctx.str("subject"));
                        setIfPresent(info::setKeywords, ctx.str("keywords"));
                    }
                    Path out = ctx.outDerived("_元数据", ".pdf");
                    PdfUtil.save(doc, out);
                    ctx.addOutput(out);
                    ctx.report(100, "元数据已更新");
                }
            });

    /** 只在值非空白时调用 setter，实现「留空=不修改」的语义。 */
    private static void setIfPresent(java.util.function.Consumer<String> setter, String value) {
        if (value != null && !value.isBlank()) {
            setter.accept(value);
        }
    }

    // ---------- 展平 ----------

    /**
     * 展平表单：把可填字段固化成页面内容。
     *
     * <p>展平后字段值变成普通页面内容——不能再编辑，但<b>文字仍可选中和搜索</b>
     * （区别于把整页转成图片的「栅格化」）。这是交付填好的表单时的标准做法：
     * 收件人能正常阅读和检索，但改不了内容。
     *
     * <p>{@code removeFields} 控制要不要顺手把表单结构定义（AcroForm 字典）也删掉：
     * <ul>
     *   <li>勾选（默认）—— 彻底不留痕迹，文件更干净，某些阅读器不会提示「此表单可填写」。</li>
     *   <li>不勾选 —— 保留空壳字段定义，只是值被渲染进页面。极少数工具链依赖它。</li>
     * </ul>
     */
    public static final Capability FLATTEN = new Capability(
            "edit.flatten", "展平表单", "内容编辑",
            "把可填写的表单字段固化进页面内容，之后不可再编辑，文字仍可选中。",
            List.of(Param.bool("removeFields", "同时移除字段定义", true, "取消勾选则只渲染不删除")),
            ctx -> {
                try (PDDocument doc = PdfUtil.load(ctx.input(), ctx.str("password"))) {
                    var form = doc.getDocumentCatalog().getAcroForm();
                    if (form == null) {
                        throw new Err("ERR_NO_FORM", "这个 PDF 没有可填写的表单字段");
                    }
                    int count = form.getFields().size();
                    ctx.report(40, "展平 " + count + " 个字段");
                    form.flatten();
                    if (ctx.bool("removeFields", true)) {
                        // 置 null 即从 catalog 里摘掉 /AcroForm，表单结构彻底消失
                        doc.getDocumentCatalog().setAcroForm(null);
                    }
                    Path out = ctx.outDerived("_展平", ".pdf");
                    PdfUtil.save(doc, out);
                    ctx.addOutput(out);
                    ctx.report(100, "已展平 " + count + " 个字段");
                }
            });
}
