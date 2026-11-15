package io.pdfkit.ops;

import io.pdfkit.core.Capability;
import io.pdfkit.core.Ctx;
import io.pdfkit.core.Err;
import io.pdfkit.core.Param;
import io.pdfkit.util.PdfUtil;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.interactive.form.PDField;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 表单类能力：读取、填充、导出 CSV。 */
public final class FormOps {

    private FormOps() {
    }

    public static final Capability READ = new Capability(
            "form.read", "读取表单字段", "表单处理",
            "列出全部 AcroForm 字段的名称、类型、当前值、是否必填。",
            List.of(Param.bool("exportCsv", "同时导出 CSV", true, "保存到输出目录")),
            ctx -> {
                try (PDDocument doc = PdfUtil.load(ctx.input(), ctx.str("password"))) {
                    var form = doc.getDocumentCatalog().getAcroForm();
                    if (form == null) {
                        throw new Err("ERR_NO_FORM", "这个 PDF 没有表单字段");
                    }
                    ctx.report(30, "读取字段");
                    StringBuilder sb = new StringBuilder();
                    List<PDField> fields = new java.util.ArrayList<>();
                    for (PDField f : form.getFieldTree()) {
                        fields.add(f);
                    }
                    sb.append("共 ").append(fields.size()).append(" 个字段\n\n");
                    StringBuilder csv = new StringBuilder("字段名,类型,当前值,必填\n");
                    int n = 0;
                    for (PDField f : fields) {
                        String name = f.getFullyQualifiedName();
                        String type = f.getClass().getSimpleName().replace("PD", "").replace("Field", "");
                        String value = safeValue(f);
                        boolean required = f.isRequired();
                        sb.append(++n).append(". ").append(name)
                                .append("  [").append(type).append("]")
                                .append("  值=").append(value.isBlank() ? "（空）" : value)
                                .append(required ? "  *必填" : "").append('\n');
                        csv.append(esc(name)).append(',').append(esc(type)).append(',')
                                .append(esc(value)).append(',').append(required ? "是" : "否").append('\n');
                    }
                    ctx.setResult(sb.toString());
                    if (ctx.bool("exportCsv", true)) {
                        Path out = ctx.outDerived("_表单字段", ".csv");
                        Files.writeString(out, csv.toString(), StandardCharsets.UTF_8);
                        ctx.addOutput(out);
                    }
                    ctx.report(100, "读取完成");
                }
            });

    public static final Capability FILL = new Capability(
            "form.fill", "填充表单", "表单处理",
            "按「字段名=值」逐行填写表单，支持中文字段值。",
            List.of(
                    Param.text("values", "字段值", "", "格式：字段名=值，每行一个"),
                    Param.bool("flatten", "填写后展平", false, "展平后不可再修改")
            ),
            ctx -> {
                String raw = ctx.str("values");
                if (raw.isBlank()) {
                    throw new Err("ERR_BAD_PARAM", "请填写「字段名=值」，每行一个");
                }
                Map<String, String> map = new LinkedHashMap<>();
                for (String line : raw.split("\\r?\\n")) {
                    int eq = line.indexOf('=');
                    if (eq <= 0) {
                        continue;
                    }
                    map.put(line.substring(0, eq).trim(), line.substring(eq + 1).trim());
                }
                if (map.isEmpty()) {
                    throw new Err("ERR_BAD_PARAM", "没有解析到有效的「字段名=值」");
                }
                try (PDDocument doc = PdfUtil.load(ctx.input(), ctx.str("password"))) {
                    var form = doc.getDocumentCatalog().getAcroForm();
                    if (form == null) {
                        throw new Err("ERR_NO_FORM", "这个 PDF 没有表单字段");
                    }
                    int filled = 0;
                    for (PDField f : iterable(form.getFieldTree())) {
                        String name = f.getFullyQualifiedName();
                        if (map.containsKey(name)) {
                            try {
                                f.setValue(map.get(name));
                                filled++;
                            } catch (Exception e) {
                                throw new Err("ERR_FIELD_TYPE",
                                        "字段「" + name + "」填值失败：" + e.getMessage(), e);
                            }
                        }
                    }
                    if (filled == 0) {
                        throw new Err("ERR_NO_FIELD_MATCH", "没有匹配到任何字段，请对照「读取表单字段」结果填写名称");
                    }
                    if (ctx.bool("flatten", false)) {
                        form.flatten();
                    }
                    Path out = ctx.outDerived("_已填表", ".pdf");
                    PdfUtil.save(doc, out);
                    ctx.addOutput(out);
                    ctx.report(100, "已填写 " + filled + " 个字段");
                }
            });

    private static List<PDField> iterable(Iterable<PDField> tree) {
        List<PDField> list = new java.util.ArrayList<>();
        for (PDField f : tree) {
            list.add(f);
        }
        return list;
    }

    private static String safeValue(PDField f) {
        try {
            String v = f.getValueAsString();
            return v == null ? "" : v;
        } catch (Exception e) {
            return "";
        }
    }

    private static String esc(String s) {
        return '"' + (s == null ? "" : s.replace("\"", "\"\"")) + '"';
    }
}
