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
import org.apache.pdfbox.pdmodel.interactive.digitalsignature.PDSignature;
import org.apache.pdfbox.pdmodel.interactive.digitalsignature.SignatureInterface;
import org.bouncycastle.asn1.ASN1ObjectIdentifier;
import org.bouncycastle.asn1.cms.CMSObjectIdentifiers;
import org.bouncycastle.asn1.x500.X500Name;
import org.bouncycastle.cert.jcajce.JcaCertStore;
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter;
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder;
import org.bouncycastle.cms.CMSProcessable;
import org.bouncycastle.cms.CMSSignedData;
import org.bouncycastle.cms.CMSSignedDataGenerator;
import org.bouncycastle.cms.jcajce.JcaSignerInfoGeneratorBuilder;
import org.bouncycastle.operator.ContentSigner;
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder;
import org.bouncycastle.operator.jcajce.JcaDigestCalculatorProviderBuilder;

import java.io.InputStream;
import java.io.OutputStream;
import java.math.BigInteger;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.KeyStore;
import java.security.PrivateKey;
import java.security.cert.X509Certificate;
import java.util.Calendar;
import java.util.Date;
import java.util.Enumeration;
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

    // ================= 证书签名 =================

    public static final Capability SIGN = new Capability(
            "secure.sign", "证书签名", "安全与合规",
            "给 PDF 加盖数字签名（PKCS#7）。不提供证书时自动生成自签名证书，可导出后重复使用。",
            List.of(
                    Param.text("keystore", "证书文件(.p12/.pfx)", "", "留空=自动生成自签名证书"),
                    Param.password("storePass", "证书密码", "留空默认 PdfKit123"),
                    Param.text("alias", "证书别名", "", "留空=取文件里第一个"),
                    Param.text("cn", "证书名称", "PdfKit 自签名", "仅自签名时生效"),
                    Param.text("reason", "签名原因", "文档确认"),
                    Param.text("location", "签名地点", "中国"),
                    Param.bool("saveCert", "导出自签名证书", true, "自签名时把 .p12 一并保存到输出目录")
            ),
            ctx -> {
                String ksPath = ctx.str("keystore");
                String pass = ctx.str("storePass", "PdfKit123");
                String alias = ctx.str("alias");
                String cn = ctx.str("cn", "PdfKit 自签名");
                Path selfCert = null;

                PrivateKey key;
                X509Certificate cert;
                try {
                    if (ksPath.isBlank()) {
                        ctx.report(10, "生成自签名证书");
                        KeyPairGenerator kpg = KeyPairGenerator.getInstance("RSA");
                        kpg.initialize(2048);
                        KeyPair kp = kpg.generateKeyPair();
                        X500Name subject = new X500Name("CN=" + cn + ", O=PdfKit, C=CN");
                        long now = System.currentTimeMillis();
                        JcaX509v3CertificateBuilder builder = new JcaX509v3CertificateBuilder(
                                subject, BigInteger.valueOf(now),
                                new Date(now - 86_400_000L),
                                new Date(now + 10L * 365 * 86_400_000L),
                                subject, kp.getPublic());
                        ContentSigner cs = new JcaContentSignerBuilder("SHA256WithRSA").build(kp.getPrivate());
                        cert = new JcaX509CertificateConverter().getCertificate(builder.build(cs));
                        key = kp.getPrivate();

                        if (ctx.bool("saveCert", true)) {
                            KeyStore ks = KeyStore.getInstance("PKCS12");
                            ks.load(null, null);
                            ks.setKeyEntry("pdfkit", key, pass.toCharArray(), new java.security.cert.Certificate[]{cert});
                            selfCert = ctx.outDerived("_签名证书", ".p12");
                            try (OutputStream os = Files.newOutputStream(selfCert)) {
                                ks.store(os, pass.toCharArray());
                            }
                        }
                    } else {
                        Path kp2 = Path.of(ksPath);
                        if (!Files.isReadable(kp2)) {
                            throw new Err("ERR_NO_CERT", "证书文件读不到：" + ksPath);
                        }
                        KeyStore ks = KeyStore.getInstance(
                                ksPath.toLowerCase().endsWith(".p12") || ksPath.toLowerCase().endsWith(".pfx")
                                        ? "PKCS12" : KeyStore.getDefaultType());
                        char[] pw = pass.toCharArray();
                        try (InputStream in = Files.newInputStream(kp2)) {
                            ks.load(in, pw);
                        } catch (Exception e) {
                            throw new Err("ERR_BAD_CERT", "证书加载失败（密码不对或格式不支持）：" + e.getMessage(), e);
                        }
                        String use = alias;
                        if (use == null || use.isBlank()) {
                            Enumeration<String> en = ks.aliases();
                            while (en.hasMoreElements()) {
                                String a = en.nextElement();
                                if (ks.isKeyEntry(a)) {
                                    use = a;
                                    break;
                                }
                            }
                        }
                        if (use == null) {
                            throw new Err("ERR_NO_KEY", "证书里没有可用的私钥条目");
                        }
                        key = (PrivateKey) ks.getKey(use, pw);
                        cert = (X509Certificate) ks.getCertificate(use);
                        if (key == null || cert == null) {
                            throw new Err("ERR_NO_KEY", "别名「" + use + "」没有私钥或证书");
                        }
                    }

                    ctx.report(40, "写入签名");
                    PDSignature sig = new PDSignature();
                    sig.setFilter(PDSignature.FILTER_ADOBE_PPKLITE);
                    sig.setSubFilter(PDSignature.SUBFILTER_ADBE_PKCS7_DETACHED);
                    sig.setName(extractCn(cert.getSubjectX500Principal().getName()));
                    sig.setReason(ctx.str("reason", "文档确认"));
                    sig.setLocation(ctx.str("location", "中国"));
                    sig.setSignDate(Calendar.getInstance());

                    SignatureInterface si = content -> {
                        try {
                            return cmsSign(content, key, cert);
                        } catch (Exception e) {
                            throw new java.io.IOException("CMS 签名失败：" + e.getMessage(), e);
                        }
                    };
                    Path out = ctx.outDerived("_已签名", ".pdf");
                    try (PDDocument doc = PdfUtil.load(ctx.input(), ctx.str("password"))) {
                        doc.addSignature(sig, si);
                        try (OutputStream os = Files.newOutputStream(out)) {
                            doc.saveIncremental(os);
                        }
                    }
                    ctx.addOutput(out);
                    if (selfCert != null) {
                        ctx.addOutput(selfCert);
                    }
                    ctx.setResult("签名完成。自签名证书不会被 Adobe 信任，Adobe 会提示「签名者身份未知」"
                            + "——这是自签名的正常表现，签名本身有效、篡改会失效。商业场景请购买 CA 证书后用「证书文件」参数。");
                    ctx.report(100, "签名完成");
                } catch (Err e) {
                    throw e;
                } catch (Exception e) {
                    throw new Err("ERR_SIGN_FAILED", "签名失败：" + e.getMessage(), e);
                }
            });

    static byte[] cmsSign(InputStream content, PrivateKey key, X509Certificate cert) throws Exception {
        CMSSignedDataGenerator gen = new CMSSignedDataGenerator();
        ContentSigner cs = new JcaContentSignerBuilder("SHA256WithRSA").build(key);
        gen.addSignerInfoGenerator(new JcaSignerInfoGeneratorBuilder(
                new JcaDigestCalculatorProviderBuilder().build()).build(cs, cert));
        gen.addCertificates(new JcaCertStore(List.of(cert)));
        CMSProcessable msg = new CMSProcessable() {
            @Override
            public void write(OutputStream out) throws java.io.IOException {
                content.transferTo(out);
            }

            @Override
            public Object getContent() {
                return content;
            }
        };
        CMSSignedData signed = gen.generate(new TypedData(msg), false);
        return signed.getEncoded();
    }

    /** 给 CMSProcessable 套上 data 类型，BouncyCastle 要求实现 CMSTypedData。 */
    record TypedData(CMSProcessable body) implements org.bouncycastle.cms.CMSTypedData {
        @Override
        public ASN1ObjectIdentifier getContentType() {
            return CMSObjectIdentifiers.data;
        }

        @Override
        public void write(OutputStream out) throws java.io.IOException {
            try {
                body.write(out);
            } catch (org.bouncycastle.cms.CMSException e) {
                throw new java.io.IOException("写入待签名数据失败：" + e.getMessage(), e);
            }
        }

        @Override
        public Object getContent() {
            return body.getContent();
        }
    }

    static String extractCn(String dn) {
        for (String part : dn.split(",")) {
            String p = part.trim();
            if (p.startsWith("CN=")) {
                return p.substring(3);
            }
        }
        return dn;
    }
}
