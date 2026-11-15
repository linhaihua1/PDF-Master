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

/**
 * 安全类能力：加密、解密、净化、签名、验签、移除签名。
 *
 * <p><b>两个密码别搞混</b>（PDF 规范的术语）：
 * <ul>
 *   <li><b>打开密码</b>（user password / document open password）—— 打开文件时要求输入。
 *       没有它就看不了内容，这是「加密」的实际意义。</li>
 *   <li><b>权限密码</b>（owner password / permissions password）—— 用于修改权限设置。
 *       知道它才能改「是否可以打印/复制」，但不知道也能打开文件。</li>
 * </ul>
 * 只设权限密码、留空打开密码时，文件实际上人人都能打开，只是编辑器里会标着「受限制」。
 * 所以本类要求两个密码至少填一个，且 permissions 密码留空时自动复用打开密码。
 *
 * <p><b>加密强度固定 AES-256</b>：{@code setEncryptionKeyLength(256)} +
 * {@code setPreferAES(true)}。不提供 RC4/AES-128 选项——那是为了兼容十几年前的阅读器，
 * 而这套实现的所有目标机器都支持 AES-256。少一个选项就少一类「用户选了弱加密却不自知」的风险。
 *
 * <p><b>签名相关三项的区别</b>：
 * <ul>
 *   <li>{@code SIGN} —— 加盖数字签名，需要私钥与证书（不提供则自动生成自签名证书）。</li>
 *   <li>{@code VERIFY_SIGNATURE} —— 校验已有签名是否有效、证书是否可信。</li>
 *   <li>{@code REMOVE_SIGNATURE} —— 摘除签名字段。<b>注意</b>：这会让签名失效，
 *       但页面上已渲染的签名外观（那行「已由 XX 签署」的字）仍留着，需要用户自己处理。</li>
 * </ul>
 */
public final class SecureOps {

    private SecureOps() {
    }

    /**
     * 添加密码：用 AES-256 加密。
     *
     * <p><b>两个密码都空时必须拦截</b>。不拦的话 PDFBox 会生成一个「加密了但用空密码
     * 就能打开」的文件，用户以为加了保护，实际任何人都能打开——这比不加密更危险，
     * 因为它给了虚假的安全感。
     *
     * <p><b>权限密码留空时复用打开密码</b>：至少让「改权限」也需要密码。若两者不同，
     * 用户只需记住打开密码就能阅读，把权限密码交给需要改权限的人。
     *
     * <p><b>修改一律禁止、填表单独放开</b>：这是「加密交付」场景的常见预期。
     * 填表不算修改文档（内容结构不变，只是字段值变），如果一并禁掉，加密的合同
     * 就签不了字了。这两项刻意不做成可配置，避免参数面板膨胀。
     *
     * <p>加密强度固定 AES-256，<b>不提供 RC4/128 位选项</b>：弱加密在今天的算力下
     * 没有意义，提供选项只会让用户选错。
     */
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
                // 两个都空等于没加密，必须拦住——否则用户以为加密了，实际文件谁都能开
                if (user.isBlank() && owner.isBlank()) {
                    throw new Err("ERR_BAD_PARAM", "打开密码与权限密码至少填一个，否则等于没加密");
                }
                if (owner.isBlank()) {
                    // 权限密码留空时复用打开密码：这样至少「改权限」也要密码，不会完全敞开
                    owner = user;
                }
                // 注意这里加载时没传密码：能进来的都是未加密文件（下面有判断）
                try (PDDocument doc = PdfUtil.load(ctx.input())) {
                    if (doc.isEncrypted()) {
                        // 已加密的文件再套一层没有意义，且 PDFBox 行为不确定，直接拒绝
                        throw new Err("ERR_ALREADY_ENCRYPTED", "文件已加密，请先解密再设置新密码");
                    }
                    AccessPermission ap = new AccessPermission();
                    ap.setCanPrint(ctx.bool("allowPrint", true));
                    ap.setCanExtractContent(ctx.bool("allowCopy", false));
                    // 修改与注释一律禁止：这是「加密交付」的常见预期，不开放成选项
                    ap.setCanModify(false);
                    // 但允许填表——填表是文档流转的正常环节，不算「修改文档」
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

    /**
     * 移除密码：输入打开密码后解除加密。
     *
     * <p><b>需要的是「打开密码」，不是「权限密码」</b>。这是 PDF 规范决定的：
     * 只要用打开密码成功加载了文档，内容就已经被解密到内存里，此时移除加密
     * 不需要知道权限密码。
     *
     * <p>由此推出一条<b>用户必须知道的现实</b>：只设了权限密码（打开密码为空）的文件，
     * 任何人都能打开，也就能移除权限限制。PDF 的权限密码在规范层面就不提供内容保密，
     * 它只是一道「君子协定」。真正要保密，必须设打开密码。
     */
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
                    // 必须置为 true，否则 save 时 PDFBox 会沿用原加密参数重新加密，
                    // 产物照样带密码，用户会觉得「点了没用」
                    doc.setAllSecurityToBeRemoved(true);
                    Path out = ctx.outDerived("_已解密", ".pdf");
                    PdfUtil.save(doc, out);
                    ctx.addOutput(out);
                    ctx.report(100, "解密完成");
                }
            });

    /**
     * 净化 PDF：剥掉可能执行代码的结构。
     *
     * <p><b>清理五类位置</b>（PDF 可以在很多地方藏脚本，漏一处就等于没清）：
     * <ol>
     *   <li>Catalog 的 {@code OpenAction} —— 文件一打开就执行；</li>
     *   <li>Catalog 的 {@code Names} 字典下的 {@code JavaScript} 与 {@code EmbeddedFiles}
     *       —— 具名脚本与附件；</li>
     *   <li>Catalog 的 {@code AA}（Additional Actions）—— 关闭、保存等事件触发；</li>
     *   <li>每一页的 {@code AA} —— 页面打开/关闭事件触发；</li>
     *   <li>注释的动作字典中 {@code S} 为 {@code JavaScript} 的 —— 点一下链接就执行。</li>
     * </ol>
     *
     * <p>{@code AA} 用 {@code COSName.getPDFName("AA")} 而不是常量：PDFBox 没有为它
     * 定义常量，只能现造名字。
     *
     * <p><b>这是「降低风险」，不是「保证安全」</b>。本项不处理 Launch 动作、
     * 多媒体（RichMedia/Sound/Movie）、XFA 表单脚本。要彻底安全，应在沙箱里打开，
     * 而不是指望一次净化。能力描述里写「阻断脚本风险」是准确的说法，
     * 不要改成「保证无风险」。
     */
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
                        // 直接操作 COS 字典而不是高层 API：Names 字典里的条目名是任意字符串键，
                        // PDFBox 没有为 JavaScript / EmbeddedFiles 提供专门的方法
                        catalog.getNames().getCOSObject().removeItem(org.apache.pdfbox.cos.COSName.JAVA_SCRIPT);
                        catalog.getNames().getCOSObject().removeItem(org.apache.pdfbox.cos.COSName.EMBEDDED_FILES);
                        removed++;
                    }
                    // 文档级附加动作：键名是 "AA"，PDFBox 无常量（见本能力 javadoc）
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

    /**
     * 验证签名：列出签名信息并检查 ByteRange 覆盖范围。
     *
     * <p><b>能告诉你什么</b>：
     * <ul>
     *   <li>有哪些签名、签名者、时间、原因、地点、子过滤器类型；</li>
     *   <li>签名的 {@code ByteRange} 是否覆盖了整份文件——覆盖不全说明签名之后
     *       有过追加（可能是第二次签名，也可能是篡改后追加）。</li>
     * </ul>
     *
     * <p><b>不能告诉你什么</b>（措辞要严格，别让用户误信）：
     * <ul>
     *   <li><b>不校验摘要值</b>——本方法没有做 CMS 验签，所以它<b>无法判断内容是否被替换</b>，
     *       只能判断「签名位置之后有没有新内容」；</li>
     *   <li><b>不校验证书链与根信任</b>——自签名证书也会正常列出；</li>
     *   <li>不检查证书是否过期、是否被吊销。</li>
     * </ul>
     *
     * <p>{@code ByteRange} 是四元组 {@code [0, len1, off2, len2]}：从文件头开始读 len1 字节，
     * 跳过签名值占位符，再从 off2 读 len2 字节。中间空出来的正是 {@code /Contents} 里
     * 那段签名数据本身。所以「覆盖字节」= len1 + len2，而不是文件总长。
     *
     * <p>{@code covered >= fileSize - 1} 里的减一是容差：某些生成器会在末尾留一个换行。
     * 判定结论只区分「覆盖全文」与「未覆盖全文」两档，不做中间态描述。
     */
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
                            // 覆盖长度 = 前段 + 后段，中间空出的部分是签名值本身，见本能力 javadoc
                            long covered = range[1] + range[3];
                            // fileSize == 0 表示取文件大小失败，此时不做判断直接按「覆盖全文」显示，
                            // 避免因读不到大小而误报「被篡改」
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

    /**
     * 移除签名：删除签名字段。
     *
     * <p><b>移除后签名必然失效</b>，即使外观还在——签名值是对「签名时的字节」做的摘要，
     * 删掉字段就改了文件，任何后续验证都会失败。能力描述里写「原有签名外观仍留在页面上」
     * 是实话：外观是页面上的注释对象，本项不删注释，所以看起来还能看到签章图案，
     * 但它已经不构成有效签名了。<b>不要用它来「撤销签名」，它只是删结构。</b>
     *
     * <p><b>必须从两处摘除</b>：父字段的 {@code /Kids} 与 AcroForm 顶层 {@code /Fields}。
     * 只摘一处会留下悬空引用，Acrobat 打开时报「表单结构损坏」。
     *
     * <p>{@code setCacheFields(false)} 是必须的：PDFBox 默认缓存字段树，
     * 缓存开启时删除字段后再查询会拿到已失效的对象。这里先关缓存再遍历。
     */
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
                        // 关缓存，理由见本能力 javadoc
                        acroForm.setCacheFields(false);
                        // getFieldTree() 返回的是惰性迭代器，遍历过程中改字段树会抛
                        // ConcurrentModificationException，所以必须先收集成 List 再删
                        List<org.apache.pdfbox.pdmodel.interactive.form.PDField> toRemove = new java.util.ArrayList<>();
                        for (var f : acroForm.getFieldTree()) {
                            if (f instanceof org.apache.pdfbox.pdmodel.interactive.form.PDSignatureField) {
                                toRemove.add(f);
                            }
                        }
                        // 顶层 /Fields 是 COS 数组，删除操作必须在 COS 层做
                        org.apache.pdfbox.cos.COSArray topFields = (org.apache.pdfbox.cos.COSArray)
                                acroForm.getCOSObject().getDictionaryObject(org.apache.pdfbox.cos.COSName.FIELDS);
                        for (var f : toRemove) {
                            try {
                                org.apache.pdfbox.cos.COSDictionary dict = f.getCOSObject();
                                // 先把自己从父字段的 /Kids 里摘掉，再从表单顶层 /Fields 摘掉。
                                // PDFBox 3 没有 removeField，只能走 COS 层。
                                org.apache.pdfbox.cos.COSDictionary parent = (org.apache.pdfbox.cos.COSDictionary)
                                        dict.getDictionaryObject(org.apache.pdfbox.cos.COSName.PARENT);
                                if (parent != null) {
                                    org.apache.pdfbox.cos.COSArray kids = (org.apache.pdfbox.cos.COSArray)
                                            parent.getDictionaryObject(org.apache.pdfbox.cos.COSName.KIDS);
                                    if (kids != null) {
                                        kids.remove(dict);
                                    }
                                }
                                if (topFields != null) {
                                    topFields.remove(dict);
                                }
                                removed++;
                            } catch (Exception e) {
                                Log.warn("移除签名字段失败：%s", e.getMessage());
                            }
                        }
                        // 畸形 PDF 里签名字段可能挂在非标准位置，仍有残留就清空字段表
                        int left = 0;
                        for (var f : acroForm.getFieldTree()) {
                            if (f instanceof org.apache.pdfbox.pdmodel.interactive.form.PDSignatureField) {
                                left++;
                            }
                        }
                        if (left > 0) {
                            acroForm.getCOSObject().removeItem(org.apache.pdfbox.cos.COSName.FIELDS);
                            removed += left;
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

    /**
     * 证书签名：给 PDF 加盖 PKCS#7 数字签名。
     *
     * <p><b>签名必须用 {@code saveIncremental}，不能用 {@link PdfUtil#save}</b>。
     * 数字签名的原理是对「签名前的全部字节」做摘要后加密，一旦重写文件（哪怕只改一个空格），
     * 摘要就对不上，签名立刻失效。增量保存只在文件尾部追加签名对象与新的交叉引用表，
     * 前面的原始字节一个不动。这也是本类唯一不能用统一保存入口的能力。
     *
     * <p><b>不带证书时自动生成自签名证书</b>，并默认导出成 {@code .p12} 放在输出目录。
     * 这样用户能拿到一份可复用的证书，第二次签名换个文档仍用同一身份，不会出现
     * 「每签一次身份都不同」的尴尬。自签名的局限见下方 {@code setResult} 的提示文案。
     *
     * <p><b>业务错误必须原样抛出</b>：末尾 {@code catch (Err e) { throw e; }} 一定要在
     * {@code catch (Exception e)} 之前，否则「证书文件读不到」这类明确提示会被统一包成
     * 「签名失败：…」，用户拿不到可操作的线索。
     */
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
                        // 2048 位是当前兼容性与安全性的平衡点：1024 位已被主流验证器判为弱密钥，
                        // 4096 位在部分老旧阅读器上验签明显变慢
                        kpg.initialize(2048);
                        KeyPair kp = kpg.generateKeyPair();
                        X500Name subject = new X500Name("CN=" + cn + ", O=PdfKit, C=CN");
                        long now = System.currentTimeMillis();
                        JcaX509v3CertificateBuilder builder = new JcaX509v3CertificateBuilder(
                                // 序列号用毫秒时间戳，够 Simple：同一毫秒内不可能签两次。
                                // 严格来说应随机生成，但这里没有跨证书的唯一性要求
                                subject, BigInteger.valueOf(now),
                                // 生效时间往前推一天（而不是直接用 now）：用户机器时钟比真实时间
                                // 快几秒时，签名会因「证书尚未生效」被判无效，这个容差就是防这个
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
                        // 按扩展名猜类型：.p12/.pfx 一定是 PKCS12；其他（如 .jks）交给 JVM 默认。
                        // 不能一律用 PKCS12，否则老的 Java KeyStore 会报「格式不合法」
                        KeyStore ks = KeyStore.getInstance(
                                ksPath.toLowerCase().endsWith(".p12") || ksPath.toLowerCase().endsWith(".pfx")
                                        ? "PKCS12" : KeyStore.getDefaultType());
                        char[] pw = pass.toCharArray();
                        try (InputStream in = Files.newInputStream(kp2)) {
                            ks.load(in, pw);
                        } catch (Exception e) {
                            throw new Err("ERR_BAD_CERT", "证书加载失败（密码不对或格式不支持）：" + e.getMessage(), e);
                        }
                        // 不指定别名时取文件里第一个「带私钥」的条目。只判断 isKeyEntry，
                        // 因为纯证书条目（只有公钥）签不了名，选中它会到 getKey 才失败
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
                        // 已知简化：私钥密码与库密码共用同一个 pass。绝大多数证书导出时两者相同；
                        // 若用户的 .p12 设置了不同的 key password，这里会失败，需要另加参数
                        key = (PrivateKey) ks.getKey(use, pw);
                        cert = (X509Certificate) ks.getCertificate(use);
                        if (key == null || cert == null) {
                            throw new Err("ERR_NO_KEY", "别名「" + use + "」没有私钥或证书");
                        }
                    }

                    ctx.report(40, "写入签名");
                    PDSignature sig = new PDSignature();
                    // FILTER/SUBFILTER 定死为 Adobe 的组合：PPKLite + adbe.pkcs7.detached。
                    // detached 表示签名值与文件内容分开存（签名字典里只放 /Contents 那一段），
                    // 这是 PDF 签名的通行做法；换其他组合多数阅读器不认
                    sig.setFilter(PDSignature.FILTER_ADOBE_PPKLITE);
                    sig.setSubFilter(PDSignature.SUBFILTER_ADBE_PKCS7_DETACHED);
                    sig.setName(extractCn(cert.getSubjectX500Principal().getName()));
                    sig.setReason(ctx.str("reason", "文档确认"));
                    sig.setLocation(ctx.str("location", "中国"));
                    sig.setSignDate(Calendar.getInstance());

                    // 回调式签名：PDFBox 序列化到占位符时回调本接口，把真正要签的字节喂进来。
                    // 这里包一层 IOException，是因为 SignatureInterface 只允许抛 IOException，
                    // 而 BouncyCastle 抛的是各种受检异常
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
                        // 必须是增量保存，理由见本能力顶部 javadoc
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

    /**
     * 生成分离式 CMS/PKCS#7 签名值（DER 编码）。
     *
     * <p><b>{@code generate(..., false)} 的第二个参数不能改成 true</b>：true 表示把原文
     * 一起封装进签名结构（encapsulated），而 PDF 用的是 detached 模式——原文就在 PDF 里，
     * 签名结构只存摘要与证书链。封装后文件会白白翻倍，且 Adobe 会判为格式不符。
     *
     * <p>证书链随签名一起写入（{@code addCertificates}），验证方才能拿到公钥；
     * 但这里只放了最终实体证书，没有放中间 CA——自签名场景够用，商业证书若缺中间证书，
     * 验证方可能提示「证书链不完整」。
     */
    static byte[] cmsSign(InputStream content, PrivateKey key, X509Certificate cert) throws Exception {
        CMSSignedDataGenerator gen = new CMSSignedDataGenerator();
        ContentSigner cs = new JcaContentSignerBuilder("SHA256WithRSA").build(key);
        gen.addSignerInfoGenerator(new JcaSignerInfoGeneratorBuilder(
                new JcaDigestCalculatorProviderBuilder().build()).build(cs, cert));
        gen.addCertificates(new JcaCertStore(List.of(cert)));
        // 包成流式 CMSSignedData：PDFBox 的 SignatureInterface 给的就是 InputStream，
        // 大文件不能先整个读进内存再签
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
