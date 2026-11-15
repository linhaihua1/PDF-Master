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

/**
 * 表单类能力：读取、填充、锁定/解锁。
 *
 * <p>操作对象是 PDF 的 AcroForm（可填写表单），不是「页面上的文字」。
 * 三步走的标准流程是：先用「读取表单字段」拿到准确的字段名，再用「填充表单」按名填值。
 * 字段名必须完全匹配（含层级分隔点），所以读取功能必须把 {@code getFullyQualifiedName()}
 * 原样输出——只显示最后一段名字会导致填充时找不到字段。
 *
 * <p><b>字段树是 Iterable 不是集合</b>：{@code getFieldTree()} 返回迭代器，
 * 遍历两次会拿到空结果。所以本类统一用 {@link #iterable} 先转成 List 再操作。
 */
public final class FormOps {

    /** 工具类，禁止实例化。 */
    private FormOps() {
    }

    /**
     * 读取全部表单字段：名称、类型、当前值、是否必填。
     *
     * <p>类型名是从类名剥出来的（{@code PDTextField} → {@code Text}），
     * 比直接显示类名友好，也够用——用户只需要知道这是文本框还是勾选框。
     *
     * <p>默认同时导出 CSV，因为字段多的时候界面里的文字列表不方便复制到 Excel 里
     * 对照着填。CSV 用 UTF-8 无 BOM 写入——Excel 直接双击打开会中文乱码，
     * 但从「数据 → 从文本导入」并指定 UTF-8 就正常。不加 BOM 是因为加了以后
     * 其它工具（Python pandas、文本编辑器）会把 BOM 当成字段名的一部分。
     */
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
                    // getFieldTree() 只能遍历一次，必须先收集
                    for (PDField f : form.getFieldTree()) {
                        fields.add(f);
                    }
                    sb.append("共 ").append(fields.size()).append(" 个字段\n\n");
                    StringBuilder csv = new StringBuilder("字段名,类型,当前值,必填\n");
                    int n = 0;
                    for (PDField f : fields) {
                        // 必须用全限定名：嵌套字段的真实名字形如 "person.name"，
                        // 只取末段 "name" 在填充时匹配不上
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

    /**
     * 按「字段名=值」逐行填表。
     *
     * <p><b>解析用 {@code indexOf('=')} 而不是 {@code split("=")}</b>：字段值本身可能包含
     * 等号（比如填一个公式 "x=y+1"）。用 split 会把值截断成 "y+1"。
     * 这里只按<b>第一个</b>等号切分，等号后面的内容整体作为值。
     *
     * <p>{@code eq <= 0} 时跳过该行：等于 0 说明字段名为空，等于 -1 说明这行没有等号。
     * 空行、注释性质的文字都靠这个判断被忽略，用户不用管格式是否严格。
     *
     * <p>一个字段都没匹配上时报 {@code ERR_NO_FIELD_MATCH}，并提示用户先去读字段名。
     * 这是最常见的失败原因：用户凭印象写字段名，实际名字带层级前缀。
     */
    public static final Capability FILL = new Capability(
            "form.fill", "填充表单", "表单处理",
            "按「字段名=值」逐行填写表单，支持中文字段值。",
            List.of(
                    Param.text("values", "字段值", "", "格式：字段名=值，每行一个"),
                    Param.bool("flatten", "填写后展平", false, "展平后不可再修改")
            ),
            ctx -> {
                ctx.input(); // 先确认已选文件
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
                                // 值类型不对（比如往勾选框里填文字）单独报，指出是哪个字段
                                throw new Err("ERR_FIELD_TYPE",
                                        "字段「" + name + "」填值失败：" + e.getMessage(), e);
                            }
                        }
                    }
                    if (filled == 0) {
                        throw new Err("ERR_NO_FIELD_MATCH", "没有匹配到任何字段，请对照「读取表单字段」结果填写名称");
                    }
                    if (ctx.bool("flatten", false)) {
                        // flatten 不可逆：字段被渲染成页面静态内容，表单结构消失。
                        // 用户勾了这项就是想固化，界面上有明确警示
                        form.flatten();
                    }
                    Path out = ctx.outDerived("_已填表", ".pdf");
                    PdfUtil.save(doc, out);
                    ctx.addOutput(out);
                    ctx.report(100, "已填写 " + filled + " 个字段");
                }
            });

    /**
     * 批量设置/解除字段只读。
     *
     * <p>用途：填完后锁定，防止转手时被改动；或者拿到一份被锁定的表单，解锁后自己填。
     *
     * <p>个别字段类型不支持只读属性时会抛异常，这里跳过继续处理其它字段——
     * 一个字段的怪癖不该让整批操作失败。但如果一个都没设成功（{@code n == 0}），
     * 说明这份 PDF 的表单结构有问题，得报错而不是假装成功。
     */
    public static final Capability READONLY = new Capability(
            "form.readonly", "锁定/解锁表单", "表单处理",
            "把全部字段设为只读（防止填完被改），或解除只读恢复可编辑。",
            List.of(Param.choice("mode", "操作", "设为只读", "设为只读", "解除只读")),
            ctx -> {
                boolean lock = !"解除只读".equals(ctx.str("mode", "设为只读"));
                try (PDDocument doc = PdfUtil.load(ctx.input(), ctx.str("password"))) {
                    var form = doc.getDocumentCatalog().getAcroForm();
                    if (form == null) {
                        throw new Err("ERR_NO_FORM", "这个 PDF 没有表单字段");
                    }
                    int n = 0;
                    for (PDField f : iterable(form.getFieldTree())) {
                        try {
                            f.setReadOnly(lock);
                            n++;
                        } catch (Exception e) {
                            // 个别字段类型不支持只读时跳过，不影响整体
                        }
                    }
                    if (n == 0) {
                        throw new Err("ERR_NO_FORM", "没有可操作的字段");
                    }
                    Path out = ctx.outDerived(lock ? "_表单已锁定" : "_表单已解锁", ".pdf");
                    PdfUtil.save(doc, out);
                    ctx.addOutput(out);
                    ctx.report(100, (lock ? "已锁定 " : "已解锁 ") + n + " 个字段");
                }
            });

    /**
     * 把字段树迭代器转成 List。
     *
     * <p>必须转：{@code getFieldTree()} 返回的是 {@code Iterable}，每次 {@code iterator()}
     * 都从头遍历一遍字段树，多次遍历既慢又容易出问题。转成 List 后可以反复遍历、
     * 也可以 {@code size()}。
     */
    private static List<PDField> iterable(Iterable<PDField> tree) {
        List<PDField> list = new java.util.ArrayList<>();
        for (PDField f : tree) {
            list.add(f);
        }
        return list;
    }

    /**
     * 安全读字段值，读不到返回空串。
     *
     * <p>{@code getValueAsString()} 对某些字段类型（按钮、签名）会抛异常，
     * 或者返回 null。读取功能是为了「列出所有字段」，不该因为个别字段读不到值就失败。
     */
    private static String safeValue(PDField f) {
        try {
            String v = f.getValueAsString();
            return v == null ? "" : v;
        } catch (Exception e) {
            return "";
        }
    }

    /**
     * CSV 字段转义：整段加双引号，内部双引号翻倍。
     *
     * <p>这是 RFC 4180 的标准做法。字段名里常有逗号、括号、双引号，
     * 不加引号会把一列拆成多列，导出的 CSV 直接错位。
     */
    private static String esc(String s) {
        return '"' + (s == null ? "" : s.replace("\"", "\"\"")) + '"';
    }
}
