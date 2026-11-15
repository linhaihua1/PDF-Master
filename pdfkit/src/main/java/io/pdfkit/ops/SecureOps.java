package io.pdfkit.ops;

import io.pdfkit.core.Capability;
import io.pdfkit.core.Ctx;
import io.pdfkit.core.Err;
import io.pdfkit.core.Param;
import io.pdfkit.util.Log;
import io.pdfkit.util.PdfUtil;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.encryption.AccessPermission;
import org.apache.pdfbox.pdmodel.encryption.StandardProtectionPolicy;

import java.nio.file.Path;
import java.util.List;

/** 安全类能力：加密、解密、净化。 */
public final class SecureOps {

    private SecureOps() {
    }

    public static final Capability ENCRYPT = new Capability(
            "security.encrypt", "添加密码", "安全与合规",
            "用 AES-256 给 PDF 加密，可分别设置打开密码与权限密码。",
            List.of(
                    Param.password("userPassword", "打开密码", "打开文件时必须输入，可留空"),
                    Param.password("ownerPassword", "权限密码", "用于修改权限，留空则与打开密码相同"),
                    Param.bool("allowPrint", "允许打印", true, ""),
                    Param.bool("allowCopy", "允许复制内容", false, "")
            ),
            ctx -> {
                String user = ctx.str("userPassword");
                String owner = ctx.str("ownerPassword");
                if (user.isBlank() && owner.isBlank()) {
                    throw new Err("ERR_BAD_PARAM", "打开密码与权限密码至少填一个，否则等于没加密");
                }
                if (owner.isBlank()) {
                    owner = user;
                }
                try (PDDocument doc = PdfUtil.load(ctx.input())) {
                    if (doc.isEncrypted()) {
                        throw new Err("ERR_ALREADY_ENCRYPTED", "文件已加密，请先解密再设置新密码");
                    }
                    AccessPermission ap = new AccessPermission();
                    ap.setCanPrint(ctx.bool("allowPrint", true));
                    ap.setCanExtractContent(ctx.bool("allowCopy", false));
                    ap.setCanModify(false);
                    ap.setCanFillInForm(true);
                    StandardProtectionPolicy policy = new StandardProtectionPolicy(owner, user, ap);
                    policy.setEncryptionKeyLength(256);
                    policy.setPreferAES(true);
                    doc.protect(policy);
                    Path out = ctx.outDerived("_加密", ".pdf");
                    ctx.report(60, "写入加密文件");
                    PdfUtil.save(doc, out);
                    ctx.addOutput(out);
                    ctx.report(100, "加密完成（AES-256）");
                }
            });

    public static final Capability DECRYPT = new Capability(
            "security.decrypt", "移除密码", "安全与合规",
            "输入打开密码，移除 PDF 的加密保护。",
            List.of(Param.password("password", "当前密码", "文件的打开密码")),
            ctx -> {
                String pw = ctx.str("password");
                try (PDDocument doc = PdfUtil.load(ctx.input(), pw)) {
                    if (!doc.isEncrypted()) {
                        throw new Err("ERR_NOT_ENCRYPTED", "这个文件没有加密，无需解密");
                    }
                    doc.setAllSecurityToBeRemoved(true);
                    Path out = ctx.outDerived("_已解密", ".pdf");
                    PdfUtil.save(doc, out);
                    ctx.addOutput(out);
                    ctx.report(100, "解密完成");
                }
            });

    public static final Capability SANITIZE = new Capability(
            "security.sanitize", "净化 PDF", "安全与合规",
            "移除 JavaScript、自动动作、嵌入文件与隐藏信息，阻断 PDF 携带的脚本风险。",
            List.of(Param.bool("removeMeta", "同时清空元数据", false, "作者、创建工具等信息")),
            ctx -> {
                try (PDDocument doc = PdfUtil.load(ctx.input(), ctx.str("password"))) {
                    var catalog = doc.getDocumentCatalog();
                    int removed = 0;

                    if (catalog.getOpenAction() != null) {
                        catalog.setOpenAction(null);
                        removed++;
                    }
                    if (catalog.getNames() != null) {
                        catalog.getNames().getCOSObject().removeItem(org.apache.pdfbox.cos.COSName.JAVA_SCRIPT);
                        catalog.getNames().getCOSObject().removeItem(org.apache.pdfbox.cos.COSName.EMBEDDED_FILES);
                        removed++;
                    }
                    var actions = catalog.getCOSObject().getDictionaryObject(
                            org.apache.pdfbox.cos.COSName.getPDFName("AA"));
                    if (actions != null) {
                        catalog.getCOSObject().removeItem(org.apache.pdfbox.cos.COSName.getPDFName("AA"));
                        removed++;
                    }
                    // 逐页移除页级附加动作与注释里的 JS
                    for (var page : doc.getPages()) {
                        var pageAA = page.getCOSObject().getDictionaryObject(org.apache.pdfbox.cos.COSName.getPDFName("AA"));
                        if (pageAA != null) {
                            page.getCOSObject().removeItem(org.apache.pdfbox.cos.COSName.getPDFName("AA"));
                            removed++;
                        }
                        for (var annot : page.getAnnotations()) {
                            var a = annot.getCOSObject().getDictionaryObject(org.apache.pdfbox.cos.COSName.getPDFName("A"));
                            if (a instanceof org.apache.pdfbox.cos.COSDictionary d
                                    && "JavaScript".equals(d.getNameAsString(org.apache.pdfbox.cos.COSName.S))) {
                                annot.getCOSObject().removeItem(org.apache.pdfbox.cos.COSName.getPDFName("A"));
                                removed++;
                            }
                        }
                    }
                    if (ctx.bool("removeMeta", false)) {
                        var info = doc.getDocumentInformation();
                        info.setAuthor(null);
                        info.setCreator(null);
                        info.setProducer(null);
                        info.setTitle(null);
                        info.setSubject(null);
                        info.setKeywords(null);
                    }
                    Path out = ctx.outDerived("_已净化", ".pdf");
                    PdfUtil.save(doc, out);
                    ctx.addOutput(out);
                    ctx.report(100, "净化完成，移除 " + removed + " 处风险项");
                }
            });

    public static final Capability VERIFY_SIGNATURE = new Capability(
            "security.verify-signature", "验证签名", "安全与合规",
            "列出文档里的数字签名并检查其覆盖范围是否完整。注意：本工具只做结构与覆盖检查，"
                    + "不校验证书链与根证书信任，正式场景请用 Adobe 或专业签名验证工具。",
            List.of(),
            ctx -> {
                try (PDDocument doc = PdfUtil.load(ctx.input(), ctx.str("password"))) {
                    List<org.apache.pdfbox.pdmodel.interactive.digitalsignature.PDSignature> sigs =
                            doc.getSignatureDictionaries();
                    if (sigs.isEmpty()) {
                        ctx.setResult("这个 PDF 没有数字签名。");
                        ctx.report(100, "无签名");
                        return;
                    }
                    long fileSize;
                    try {
                        fileSize = java.nio.file.Files.size(ctx.input());
                    } catch (Exception e) {
                        fileSize = 0;
                    }
                    StringBuilder sb = new StringBuilder("发现 " + sigs.size() + " 个签名：\n\n");
                    int n = 0;
                    for (var sig : sigs) {
                        sb.append(++n).append(". 签名者：").append(sig.getName() == null ? "（未填）" : sig.getName()).append('\n');
                        sb.append("   时间：").append(sig.getSignDate() == null ? "（无）" : sig.getSignDate().getTime()).append('\n');
                        sb.append("   原因：").append(sig.getReason() == null ? "（未填）" : sig.getReason()).append('\n');
                        sb.append("   地点：").append(sig.getLocation() == null ? "（未填）" : sig.getLocation()).append('\n');
                        int[] range = sig.getByteRange();
                        if (range != null && range.length == 4) {
                            long covered = range[1] + range[3];
                            boolean full = fileSize == 0 || covered >= fileSize - 1;
                            sb.append("   覆盖字节：").append(covered).append(" / ").append(fileSize)
                                    .append(full ? "  → 覆盖全文（未被追加内容）" : "  → 未覆盖全文（签名后有内容被追加）").append('\n');
                        } else {
                            sb.append("   ByteRange 缺失，签名结构不完整\n");
                        }
                        sb.append("   数字签名算法：").append(sig.getSubFilter()).append('\n');
                    }
                    sb.append("\n提示：本检查只验证结构完整性，不能证明证书可信。");
                    ctx.setResult(sb.toString());
                    ctx.report(100, "共 " + sigs.size() + " 个签名");
                }
            });

    public static final Capability REMOVE_SIGNATURE = new Capability(
            "security.remove-signature", "移除签名", "安全与合规",
            "删除文档中的数字签名字段。移除后签名失效，原有签名外观仍留在页面上。",
            List.of(),
            ctx -> {
                try (PDDocument doc = PdfUtil.load(ctx.input(), ctx.str("password"))) {
                    var sigs = doc.getSignatureDictionaries();
                    if (sigs.isEmpty()) {
                        throw new Err("ERR_NO_SIGNATURE", "这个 PDF 没有数字签名");
                    }
                    int removed = 0;
                    var acroForm = doc.getDocumentCatalog().getAcroForm();
                    if (acroForm != null) {
                        List<org.apache.pdfbox.pdmodel.interactive.form.PDField> toRemove = new java.util.ArrayList<>();
                        for (var f : acroForm.getFieldTree()) {
                            if (f instanceof org.apache.pdfbox.pdmodel.interactive.form.PDSignatureField) {
                                toRemove.add(f);
                            }
                        }
                        for (var f : toRemove) {
                            try {
                                acroForm.getFields().remove(f);
                                removed++;
                            } catch (Exception e) {
                                Log.warn("移除签名字段失败：%s", e.getMessage());
                            }
                        }
                    }
                    doc.getDocumentCatalog().getCOSObject().removeItem(org.apache.pdfbox.cos.COSName.PERMS);
                    Path out = ctx.outDerived("_已移除签名", ".pdf");
                    PdfUtil.save(doc, out);
                    ctx.addOutput(out);
                    ctx.report(100, "已移除 " + removed + " 个签名字段");
                }
            });
}
