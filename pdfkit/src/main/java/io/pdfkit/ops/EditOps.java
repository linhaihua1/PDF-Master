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

/** 内容编辑类能力：页码、水印、元数据、展平。 */
public final class EditOps {

    private EditOps() {
    }

    // ---------- 页码 ----------

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
                float margin = ctx.intVal("margin", 12, 0, 100) * 72f / 25.4f;
                String pos = ctx.str("position", "底部居中");

                try (PDDocument doc = PdfUtil.load(ctx.input(), ctx.str("password"))) {
                    int total = doc.getNumberOfPages();
                    List<Integer> pages = PdfUtil.parsePageSpec(ctx.str("pages"), total);
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
                            y = box.getUpperRightY() - margin - size;
                        } else {
                            y = box.getLowerLeftY() + margin;
                        }
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
                float alpha = ctx.intVal("opacity", 15, 1, 100) / 100f;
                boolean tile = ctx.bool("tile", false);
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
                            gs.setNonStrokingAlphaConstant(alpha);
                            gs.setStrokingAlphaConstant(alpha);
                            cs.setGraphicsStateParameters(gs);
                            cs.setNonStrokingColor(color);
                            cs.setFont(font, size);
                            if (tile) {
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

    private static void setIfPresent(java.util.function.Consumer<String> setter, String value) {
        if (value != null && !value.isBlank()) {
            setter.accept(value);
        }
    }

    // ---------- 展平 ----------

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
                        doc.getDocumentCatalog().setAcroForm(null);
                    }
                    Path out = ctx.outDerived("_展平", ".pdf");
                    PdfUtil.save(doc, out);
                    ctx.addOutput(out);
                    ctx.report(100, "已展平 " + count + " 个字段");
                }
            });
}
