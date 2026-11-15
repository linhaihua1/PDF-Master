package io.pdfkit.ops;

import io.pdfkit.core.Capability;
import io.pdfkit.core.Ctx;
import io.pdfkit.core.Err;
import io.pdfkit.core.Param;
import io.pdfkit.util.PdfUtil;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDDocumentNameDictionary;
import org.apache.pdfbox.pdmodel.PDEmbeddedFilesNameTreeNode;
import org.apache.pdfbox.pdmodel.common.PDNameTreeNode;
import org.apache.pdfbox.pdmodel.common.filespecification.PDComplexFileSpecification;
import org.apache.pdfbox.pdmodel.common.filespecification.PDEmbeddedFile;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 附件类能力：列出、添加、导出。 */
public final class AttachOps {

    private AttachOps() {
    }

    /** 递归收集全部附件（PDF 附件可能分布在多级名称树里）。 */
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
                        Path target = dir.resolve(e.getKey().replaceAll("[\\\\/:*?\"<>|]", "_"));
                        try (InputStream in = ef.createInputStream()) {
                            Files.copy(in, target, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
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
}
