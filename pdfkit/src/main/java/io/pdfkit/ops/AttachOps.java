package io.pdfkit.ops;

import io.pdfkit.core.Capability;
import io.pdfkit.core.Ctx;
import io.pdfkit.core.Err;
import io.pdfkit.core.Param;
import io.pdfkit.util.Log;
import io.pdfkit.util.PdfUtil;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDDocumentNameDictionary;
import org.apache.pdfbox.pdmodel.PDEmbeddedFilesNameTreeNode;
import org.apache.pdfbox.pdmodel.common.PDNameTreeNode;
import org.apache.pdfbox.pdmodel.common.filespecification.PDComplexFileSpecification;
import org.apache.pdfbox.pdmodel.common.filespecification.PDEmbeddedFile;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 附件类能力：列出、添加、导出。
 *
 * <p>附件（EmbeddedFile）是「塞进 PDF 里的任意文件」——合同附带的明细表、
 * 发票附带的凭证扫描件。它不显示在页面上，只在阅读器的附件面板里出现，
 * 但会随 PDF 一起传输。
 *
 * <p><b>附件名的获取路径比想象中绕</b>：PDF 规范把文件名放在三条不同的键上
 * （{@code /F} 平台无关、{@code /UF} Unicode、{@code /DOS} {@code /Mac} {@code /Unix} 平台相关），
 * 不同生成器写的位置不一致。所以这里用 {@code PDComplexFileSpecification} 而不是
 * 简单的字符串映射，由 PDFBox 帮我们处理优先级。
 *
 * <p><b>附件名不可信</b>：它完全由 PDF 作者控制，可以写成 {@code ../../evil.exe}
 * 或 {@code CON}。导出时必须清洗，相关逻辑见 {@link #sanitizeName}，
 * 这是安全测试里抓出来的高危问题，改动这段代码前请先读那段注释。
 */
public final class AttachOps {

    private AttachOps() {
    }

    /**
     * 递归收集全部附件（PDF 附件可能分布在多级名称树里）。
     *
     * <p>名称树是「二叉树 + 名称数组」结构，附件可能挂在任意深度的 {@code Kids} 上，
     * 所以必须递归。两处 {@code catch} 都吞掉异常：一个节点损坏不该让整份附件清单为空，
     * 能列出多少列多少。
     *
     * @param out 累加容器，调用方负责保证非 null（传 {@code LinkedHashMap} 可保序）
     */
    static Map<String, PDComplexFileSpecification> collect(PDNameTreeNode<PDComplexFileSpecification> node,
                                                           Map<String, PDComplexFileSpecification> out) {
        if (node == null) {
            return out;
        }
        try {
            Map<String, PDComplexFileSpecification> names = node.getNames();
            if (names != null) {
                out.putAll(names);
            }
        } catch (Exception ignored) {
            // 损坏节点跳过
        }
        try {
            List<PDNameTreeNode<PDComplexFileSpecification>> kids = node.getKids();
            if (kids != null) {
                for (var k : kids) {
                    collect(k, out);
                }
            }
        } catch (Exception ignored) {
            // 子节点异常跳过
        }
        return out;
    }

    /**
     * 取文档的全部附件（扁平化成「名称 → 文件规格」的 Map）。
     *
     * <p>{@code LinkedHashMap} 而不是 HashMap：保持名称树里的原始顺序，
     * 列表输出与导出时的序号才稳定可比。
     *
     * <p><b>只查了 {@code Names/EmbeddedFiles} 这一条路径</b>。PDF 2.0 还允许用
     * Catalog 的 {@code /AF}（Associated Files）挂附件，本方法查不到。<b>所以「列出 0 个附件」
     * 不等于文档真的没有附件</b>，只是这一路径下没有。要补全覆盖需在这里追加对
     * {@code doc.getDocumentCatalog().getCOSObject().getDictionaryObject("AF")} 的遍历。
     */
    static Map<String, PDComplexFileSpecification> allAttachments(PDDocument doc) {
        var names = doc.getDocumentCatalog().getNames();
        if (names == null) {
            return Map.of();
        }
        return collect(names.getEmbeddedFiles(), new LinkedHashMap<>());
    }

    public static final Capability LIST = new Capability(
            "attachment.list", "列出附件", "内容编辑",
            "查看 PDF 内嵌的全部附件（文件名、大小、修改时间）。",
            List.of(),
            ctx -> {
                try (PDDocument doc = PdfUtil.load(ctx.input(), ctx.str("password"))) {
                    Map<String, PDComplexFileSpecification> map = allAttachments(doc);
                    if (map.isEmpty()) {
                        ctx.setResult("这个 PDF 没有内嵌附件。");
                        ctx.report(100, "无附件");
                        return;
                    }
                    StringBuilder sb = new StringBuilder("附件清单（共 " + map.size() + " 个）：\n\n");
                    int n = 0;
                    for (var e : map.entrySet()) {
                        sb.append(++n).append(". ").append(e.getKey());
                        PDEmbeddedFile ef = e.getValue().getEmbeddedFile();
                        if (ef != null) {
                            sb.append("   ").append(ef.getSize() / 1024).append(" KB");
                            if (ef.getModDate() != null) {
                                sb.append("   修改于 ").append(ef.getModDate().getTime());
                            }
                        } else {
                            sb.append("   （无内容）");
                        }
                        sb.append('\n');
                    }
                    ctx.setResult(sb.toString());
                    ctx.report(100, "共 " + map.size() + " 个附件");
                }
            });

    public static final Capability ADD = new Capability(
            "attachment.add", "添加附件", "内容编辑",
            "把一个外部文件嵌入 PDF。附件随 PDF 一起传输，常见于合同、发票的佐证材料。",
            List.of(Param.text("file", "要附加的文件路径", "", "例如 C:\\资料\\明细.xlsx")),
            ctx -> {
                ctx.input(); // 先确认已选文件
                String p = ctx.str("file");
                if (p.isBlank()) {
                    throw new Err("ERR_BAD_PARAM", "请填写要附加的文件完整路径");
                }
                Path extra = Path.of(p);
                if (!Files.isReadable(extra)) {
                    throw new Err("ERR_NO_FILE", "找不到这个文件：" + extra);
                }
                try (PDDocument doc = PdfUtil.load(ctx.input(), ctx.str("password"))) {
                    ctx.report(30, "读取附件内容");
                    PDComplexFileSpecification spec = new PDComplexFileSpecification();
                    String fileName = extra.getFileName().toString();
                    // 两个名字都要设：/F 存原始名（给老阅读器），/UF 存 Unicode 名。
                    // 只设 /F 时中文文件名在不同 PDF 阅读器里会显示成乱码或被替换。
                    spec.setFile(fileName);
                    spec.setFileUnicode(fileName);
                    try (InputStream in = Files.newInputStream(extra)) {
                        PDEmbeddedFile ef = new PDEmbeddedFile(doc, in);
                        ef.setSize((int) Files.size(extra));
                        ef.setCreationDate(java.util.Calendar.getInstance());
                        ef.setModDate(java.util.Calendar.getInstance());
                        spec.setEmbeddedFile(ef);
                    }

                    var names = doc.getDocumentCatalog().getNames();
                    if (names == null) {
                        names = new PDDocumentNameDictionary(doc.getDocumentCatalog());
                        doc.getDocumentCatalog().setNames(names);
                    }
                    PDNameTreeNode<PDComplexFileSpecification> embedded = names.getEmbeddedFiles();
                    Map<String, PDComplexFileSpecification> map = new LinkedHashMap<>();
                    if (embedded != null) {
                        collect(embedded, map);
                    }
                    map.put(fileName, spec);

                    PDEmbeddedFilesNameTreeNode root = new PDEmbeddedFilesNameTreeNode();
                    root.setNames(map);
                    names.setEmbeddedFiles(root);

                    Path out = ctx.outDerived("_加附件", ".pdf");
                    ctx.report(70, "写入文件");
                    PdfUtil.save(doc, out);
                    ctx.addOutput(out);
                    ctx.report(100, "已嵌入 " + fileName);
                }
            });

    public static final Capability EXTRACT = new Capability(
            "attachment.extract", "导出全部附件", "内容编辑",
            "把 PDF 内嵌的所有附件导出到输出目录下的文件夹。",
            List.of(),
            ctx -> {
                try (PDDocument doc = PdfUtil.load(ctx.input(), ctx.str("password"))) {
                    Map<String, PDComplexFileSpecification> map = allAttachments(doc);
                    if (map.isEmpty()) {
                        throw new Err("ERR_NO_ATTACHMENT", "这个 PDF 没有内嵌附件");
                    }
                    Path dir = ctx.outDir().resolve(PdfUtil.baseName(ctx.input()) + "_附件");
                    Files.createDirectories(dir);
                    int n = 0;
                    for (var e : map.entrySet()) {
                        PDEmbeddedFile ef = e.getValue().getEmbeddedFile();
                        if (ef == null) {
                            continue;
                        }
                        // 附件名来自 PDF 内部，完全由文件作者控制：必须清洗后再落盘
                        Path target = dir.resolve(sanitizeName(e.getKey(), n + 1)).normalize();
                        if (!target.startsWith(dir.normalize())) {
                            target = dir.resolve("attachment_" + (n + 1)).normalize();
                        }
                        try (InputStream in = ef.createInputStream()) {
                            Files.copy(in, target, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                        } catch (IOException ex) {
                            // 单个附件写失败（名字非法、权限不足）不应让整批导出中断
                            Log.warn("附件 %s 导出失败：%s", e.getKey(), ex.getMessage());
                            continue;
                        }
                        ctx.addOutput(target);
                        n++;
                    }
                    if (n == 0) {
                        throw new Err("ERR_NO_ATTACHMENT", "附件存在但内容为空，无法导出");
                    }
                    ctx.report(100, "已导出 " + n + " 个附件到 " + dir.getFileName());
                }
            });

    /**
     * 清洗 PDF 内嵌附件名：去掉路径分隔符、控制字符、Windows 非法字符与尾部点空格。
     * 附件名可被文件作者任意构造（含 {@code ../../x}、纯 {@code ..}、超长名），
     * 不清洗会导致写出目录或整批导出失败。
     */
    private static String sanitizeName(String raw, int seq) {
        String s = raw == null ? "" : raw;
        s = s.replaceAll("[\\\\/:*?\"<>|\\p{Cntrl}]", "_").trim();
        s = s.replaceAll("[. ]+$", "");
        if (s.isEmpty() || s.equals(".") || s.equals("..")) {
            return "attachment_" + seq;
        }
        // Windows 保留设备名（CON/PRN/AUX/NUL/COM1-9/LPT1-9）不能作为文件名，否则写入时报「参数错误」
        String stem = s;
        int firstDot = s.indexOf('.');
        if (firstDot > 0) {
            stem = s.substring(0, firstDot);
        }
        if (WIN_RESERVED.contains(stem.toUpperCase(java.util.Locale.ROOT))) {
            s = "_" + s;
        }
        if (s.length() > 120) {
            String ext = "";
            int dot = s.lastIndexOf('.');
            if (dot > 0 && s.length() - dot <= 12) {
                ext = s.substring(dot);
                s = s.substring(0, dot);
            }
            s = s.substring(0, Math.min(s.length(), 100)) + ext;
        }
        return s;
    }

    /** Windows 上不可用作文件名的设备名。 */
    private static final java.util.Set<String> WIN_RESERVED = java.util.Set.of(
            "CON", "PRN", "AUX", "NUL",
            "COM1", "COM2", "COM3", "COM4", "COM5", "COM6", "COM7", "COM8", "COM9",
            "LPT1", "LPT2", "LPT3", "LPT4", "LPT5", "LPT6", "LPT7", "LPT8", "LPT9");
}
