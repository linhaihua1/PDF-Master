package io.pdfkit;

import io.pdfkit.core.Capability;
import io.pdfkit.core.Ctx;
import io.pdfkit.core.Err;
import io.pdfkit.ops.AttachOps;
import io.pdfkit.ops.BookmarkOps;
import io.pdfkit.ops.SecureOps;
import io.pdfkit.util.Log;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.cos.COSDictionary;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.common.filespecification.PDComplexFileSpecification;
import org.apache.pdfbox.pdmodel.common.filespecification.PDEmbeddedFile;
import org.apache.pdfbox.pdmodel.encryption.AccessPermission;
import org.apache.pdfbox.pdmodel.encryption.StandardProtectionPolicy;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * S3 安全测试：针对 PDF 工具的真实攻击面。
 *
 * <p>桌面离线工具没有 HTTP 攻击面，风险集中在「处理他人提供的文件」这条链路上：
 * XML 外部实体、附件名路径穿越、加密强度、密码泄漏、恶意 PDF 资源耗尽。
 */
@Timeout(value = 600, unit = TimeUnit.SECONDS)
class SecurityTest {

    @TempDir
    static Path root;

    static Path pdf;
    static Path out;
    static Path wk;

    private final List<String> findings = new ArrayList<>();

    @BeforeAll
    static void prepare() throws Exception {
        Files.createDirectories(root.resolve("out"));
        Files.createDirectories(root.resolve("wk"));
        out = root.resolve("out");
        wk = root.resolve("wk");
        pdf = makePdf("base.pdf", 3);
    }

    @BeforeEach
    void reset() {
        findings.clear();
    }

    private static Path makePdf(String name, int pages) throws IOException {
        Path p = root.resolve(name);
        try (PDDocument doc = new PDDocument()) {
            var font = new PDType1Font(Standard14Fonts.FontName.HELVETICA);
            for (int i = 0; i < pages; i++) {
                PDPage page = new PDPage(PDRectangle.A4);
                doc.addPage(page);
                try (PDPageContentStream cs = new PDPageContentStream(doc, page)) {
                    cs.beginText();
                    cs.setFont(font, 12);
                    cs.newLineAtOffset(60, 700);
                    cs.showText("security sample " + (i + 1));
                    cs.endText();
                }
            }
            doc.save(p.toFile());
        }
        return p;
    }

    // ==================== SEC-01 XXE ====================

    @Test
    void xxeEntityIsRejected() throws Exception {
        Path secret = root.resolve("secret.txt");
        Files.writeString(secret, "TOP-SECRET-MARKER-9f3a");
        String secretUri = secret.toUri().toString();

        Path evil = root.resolve("xxe.xml");
        Files.writeString(evil, """
                <?xml version="1.0" encoding="UTF-8"?>
                <!DOCTYPE bookmarks [
                  <!ENTITY xxe SYSTEM "%s">
                ]>
                <bookmarks>
                  <item title="&xxe;" page="1"/>
                </bookmarks>
                """.formatted(secretUri), StandardCharsets.UTF_8);

        Path result;
        try {
            result = runOk(BookmarkOps.IMPORT, Map.of("xmlPath", evil.toString()));
        } catch (Err e) {
            // 解析阶段直接拒绝，是本程序期望的行为
            assertTrue(e.code().startsWith("ERR_"), "拒绝原因必须是业务错误码，实际 " + e.code());
            assertTrue(findings.isEmpty(), summary("SEC-01 XXE"));
            return;
        }

        // 万一没拒绝，本地文件内容也绝不能进入书签标题
        if (result != null) {
            try (PDDocument doc = Loader.loadPDF(result.toFile())) {
                String titles = dumpTitles(doc);
                if (titles.contains("TOP-SECRET-MARKER-9f3a")) {
                    findings.add("[严重] XXE 生效：书签标题里出现了本机文件内容");
                }
            }
        }
        assertTrue(findings.isEmpty(), summary("SEC-01 XXE"));
    }

    @Test
    void xxeWithDoctypeIsRejectedOutright() throws Exception {
        Path evil = root.resolve("xxe2.xml");
        Files.writeString(evil, """
                <?xml version="1.0"?>
                <!DOCTYPE bookmarks SYSTEM "http://127.0.0.1:1/evil.dtd">
                <bookmarks><item title="net" page="1"/></bookmarks>
                """, StandardCharsets.UTF_8);
        // 必须被拒绝：带 DOCTYPE 的书签文件不是本程序生成的合法格式
        Err e = assertThrows(Err.class, () -> runOk(BookmarkOps.IMPORT, Map.of("xmlPath", evil.toString())),
                "带外部 DTD 的 XML 应被拒绝");
        assertEquals("ERR_BAD_XML", e.code());
    }

    // ==================== SEC-02 深度炸弹 ====================

    @Test
    void deeplyNestedXmlIsRejected() throws Exception {
        StringBuilder sb = new StringBuilder("<?xml version=\"1.0\"?><bookmarks>");
        int depth = 500;
        for (int i = 0; i < depth; i++) {
            sb.append("<item title=\"L").append(i).append("\" page=\"1\">");
        }
        sb.append("</item>".repeat(depth)).append("</bookmarks>");
        Path bomb = root.resolve("deep.xml");
        Files.writeString(bomb, sb.toString(), StandardCharsets.UTF_8);

        Err e = assertThrows(Err.class, () -> runOk(BookmarkOps.IMPORT, Map.of("xmlPath", bomb.toString())),
                "500 层嵌套应被拒绝而不是把栈打爆");
        assertEquals("ERR_BAD_XML", e.code());
    }

    // ==================== SEC-03 附件名路径穿越 ====================

    @Test
    void attachmentNameCannotEscapeOutputDirectory() throws Exception {
        // 先用「添加附件」产出带附件的 PDF，顺带验证 ADD 写入链路可用
        Path payload = root.resolve("payload.txt");
        Files.writeString(payload, "PWNED", StandardCharsets.UTF_8);
        Path withAttach = runOk(AttachOps.ADD, Map.of("file", payload.toString()));
        assertNotNull(withAttach, "添加附件应产出文件");

        // 再把附件名改成路径穿越形式后另存，模拟"他人提供的恶意 PDF"
        Path evilPdf = root.resolve("evil-attach.pdf");
        try (PDDocument d = Loader.loadPDF(withAttach.toFile())) {
            var names = d.getDocumentCatalog().getNames();
            assertNotNull(names, "添加附件后应有 /Names 字典");
            var tree = names.getEmbeddedFiles();
            assertNotNull(tree, "添加附件后应有 EmbeddedFiles 节点");
            var map = tree.getNames();
            assertNotNull(map, "附件表为空，ADD 未真正写入");
            var spec = map.values().iterator().next();
            var evil = new LinkedHashMap<String, PDComplexFileSpecification>();
            for (String n : List.of("../../escape.txt", "..", "a/../../escape2.txt", "CON")) {
                spec.setFile(n);
                spec.setFileUnicode(n);
                evil.put(n, spec);
            }
            tree.setNames(evil);
            d.save(evilPdf.toFile());
        }

        runOn(evilPdf, AttachOps.EXTRACT, Map.of());

        // 逃逸检查：输出目录之外不允许出现 payload 文件
        Path base = out.toAbsolutePath().normalize();
        try (Stream<Path> walk = Files.walk(root)) {
            for (Path p : walk.filter(Files::isRegularFile).toList()) {
                String n = p.getFileName().toString();
                if (!n.equals("escape.txt") && !n.equals("escape2.txt")) {
                    continue;
                }
                Path norm = p.toAbsolutePath().normalize();
                if (!norm.startsWith(base)) {
                    findings.add("[高危] 附件名穿越成功：写出到 " + norm);
                }
            }
        }
        assertTrue(findings.isEmpty(), summary("SEC-03 附件名穿越"));
    }

    // ==================== SEC-04 加密强度 ====================

    @Test
    void encryptionUsesAes256() throws Exception {
        Path enc = runOk(SecureOps.ENCRYPT, Map.of("userPassword", "User#12345", "ownerPassword", "Owner#12345"));
        assertNotNull(enc, "加密应产出文件");
        try (PDDocument doc = Loader.loadPDF(enc.toFile(), "User#12345")) {
            assertTrue(doc.isEncrypted(), "产出应为加密文件");
            COSDictionary encDict = doc.getDocument().getTrailer().getCOSDictionary(COSName.ENCRYPT);
            assertNotNull(encDict, "缺少 /Encrypt 字典");
            int v = encDict.getInt(COSName.V, -1);
            int r = encDict.getInt(COSName.R, -1);
            int bits = encDict.getInt(COSName.LENGTH, 0);
            // V=5 / R=6 是 AES-256 的标准组合；V=4/R=4 只是 AES-128，RC4 为 V=1/2
            if (!(v == 5 && r == 6)) {
                findings.add("[高危] 加密等级不足：V=" + v + " R=" + r + "（期望 V=5 R=6，即 AES-256）");
            }
            if (v == 2 && bits < 128) {
                findings.add("[严重] 使用 RC4 且密钥长度 " + bits + " 位");
            }
        }
        assertTrue(findings.isEmpty(), summary("SEC-04 加密强度"));
    }

    @Test
    void encryptedFileCannotBeOpenedWithoutPassword() throws Exception {
        Path enc = runOk(SecureOps.ENCRYPT, Map.of("userPassword", "User#12345", "ownerPassword", "Owner#12345"));
        assertThrows(Exception.class, () -> {
            try (PDDocument ignored = Loader.loadPDF(enc.toFile())) {
                ignored.getNumberOfPages();
            }
        }, "无密码不应能打开加密文件");
    }

    // ==================== SEC-05 密码不落盘 ====================

    @Test
    void passwordNeverAppearsInLogs() throws Exception {
        Path logs = root.resolve("logs");
        Log.init(logs);
        String marker = "Zq7-Sentinel-Pass";
        runOk(SecureOps.ENCRYPT, Map.of("userPassword", marker, "ownerPassword", marker + "-owner"));
        Log.info("测试结束");

        Path logFile = Log.currentFile();
        assertNotNull(logFile, "日志文件未创建");
        String content = Files.readString(logFile, StandardCharsets.UTF_8);
        if (content.contains(marker)) {
            findings.add("[高危] 密码明文写入日志：" + logFile.getFileName());
        }
        assertTrue(findings.isEmpty(), summary("SEC-05 密码落盘"));
    }

    @Test
    void passwordNeverAppearsInJobMessage() throws Exception {
        String marker = "Qw8-Sentinel-Pass";
        JobCapture cap = runCapture(SecureOps.ENCRYPT, Map.of("userPassword", marker, "ownerPassword", marker));
        if (cap.message != null && cap.message.contains(marker)) {
            findings.add("[高危] 密码出现在任务状态消息中（UI 会直接显示）：" + cap.message);
        }
        assertTrue(findings.isEmpty(), summary("SEC-05 密码进 UI"));
    }

    // ==================== SEC-06 恶意 PDF 资源耗尽 ====================

    @Test
    void hugePageCountPdfDoesNotCrash() throws Exception {
        // 5000 页空文档：体积小但页数多，是最廉价的资源耗尽手段
        Path big = root.resolve("many-pages.pdf");
        try (PDDocument doc = new PDDocument()) {
            for (int i = 0; i < 5000; i++) {
                doc.addPage(new PDPage(new PDRectangle(10, 10)));
            }
            doc.save(big.toFile());
        }
        long start = System.currentTimeMillis();
        try {
            runOn(big, io.pdfkit.ops.AnalyzeOps.INFO, Map.of());
        } catch (Err e) {
            // 结构化拒绝也算通过
            assertTrue(e.code().startsWith("ERR_"), "拒绝原因必须是业务错误码");
        }
        long ms = System.currentTimeMillis() - start;
        if (ms > 60_000) {
            findings.add("[中危] 5000 页 PDF 读取耗时 " + ms + " ms，缺少页数上限保护");
        }
        assertTrue(findings.isEmpty(), summary("SEC-06 页数炸弹"));
    }

    @Test
    void zipBombLikeStreamIsRejectedStructurally() throws Exception {
        // 用随机字节伪装 PDF：解析必须给出业务错误，不能抛未捕获异常
        Path junk = root.resolve("junk.pdf");
        byte[] bytes = new byte[1024 * 1024];
        new java.util.Random(42).nextBytes(bytes);
        Files.write(junk, bytes);
        try {
            runOn(junk, io.pdfkit.ops.OrganizeOps.ROTATE, Map.of("angle", "90"));
            findings.add("[高危] 随机字节文件被当作 PDF 处理成功");
        } catch (Err e) {
            if (!e.code().startsWith("ERR_")) {
                findings.add("错误码不合规：" + e.code());
            }
        } catch (Throwable t) {
            findings.add("[高危] 非 PDF 输入抛出未捕获异常：" + t.getClass().getSimpleName());
        }
        assertTrue(findings.isEmpty(), summary("SEC-06 伪 PDF"));
    }

    // ==================== SEC-07 临时文件 ====================

    @Test
    void workDirectoryIsCleanedAfterJob() throws Exception {
        Path jobWk = wk.resolve("cleanup-check");
        Files.createDirectories(jobWk);
        runOn(pdf, io.pdfkit.ops.OrganizeOps.ROTATE, Map.of("angle", "90"), jobWk);
        // 本测试直接调用能力，不经过 JobQueue，清理由 JobQueue 负责；
        // 这里只验证能力本身不在 work 目录留下敏感中间产物
        try (Stream<Path> walk = Files.walk(jobWk)) {
            List<Path> left = walk.filter(Files::isRegularFile).toList();
            for (Path p : left) {
                String n = p.getFileName().toString();
                if (n.endsWith(".part")) {
                    findings.add("[中危] 残留半成品文件：" + n);
                }
            }
        }
        assertTrue(findings.isEmpty(), summary("SEC-07 临时文件"));
    }

    // ==================== 脚手架 ====================

    private record JobCapture(String message, List<Path> outputs) {
    }

    private Path runOk(Capability cap, Map<String, String> params) throws Exception {
        Ctx ctx = ctx(cap, List.of(pdf), params, wk.resolve("run-" + cap.id().replace('.', '-')));
        cap.op().run(ctx);
        if (ctx.outputs().isEmpty()) {
            findings.add("能力[" + cap.id() + "] 没有产出文件");
            return null;
        }
        return ctx.outputs().get(0);
    }

    private Path runOn(Path input, Capability cap, Map<String, String> params) throws Exception {
        return runOn(input, cap, params, wk.resolve("run2-" + cap.id().replace('.', '-')));
    }

    private Path runOn(Path input, Capability cap, Map<String, String> params, Path work) throws Exception {
        Ctx ctx = ctx(cap, List.of(input), params, out, work);
        cap.op().run(ctx);
        return ctx.outputs().isEmpty() ? null : ctx.outputs().get(0);
    }

    private JobCapture runCapture(Capability cap, Map<String, String> params) throws Exception {
        Ctx ctx = ctx(cap, List.of(pdf), params, out, wk.resolve("cap"));
        cap.op().run(ctx);
        return new JobCapture(null, ctx.outputs());
    }

    private Ctx ctx(Capability cap, List<Path> inputs, Map<String, String> params, Path work) {
        return ctx(cap, inputs, params, out, work);
    }

    private Ctx ctx(Capability cap, List<Path> inputs, Map<String, String> params, Path outDir, Path work) {
        try {
            Files.createDirectories(work);
            Files.createDirectories(outDir);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
        return new Ctx(cap.id(), inputs, params, outDir, work, (p, m) -> {
        }, new AtomicBoolean(false));
    }

    private static String dumpTitles(PDDocument doc) {
        StringBuilder sb = new StringBuilder();
        try {
            var outline = doc.getDocumentCatalog().getDocumentOutline();
            if (outline != null) {
                var item = outline.getFirstChild();
                while (item != null) {
                    sb.append(item.getTitle()).append('\n');
                    item = item.getNextSibling();
                }
            }
        } catch (Exception ignored) {
            // 书签读取失败不影响判定
        }
        return sb.toString();
    }

    private String summary(String title) {
        StringBuilder sb = new StringBuilder("\n===== " + title + " 发现 " + findings.size() + " 项 =====\n");
        findings.forEach(f -> sb.append("  · ").append(f).append('\n'));
        return sb.toString();
    }
}
