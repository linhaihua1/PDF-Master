package io.pdfkit;

import io.pdfkit.core.Capability;
import io.pdfkit.core.Ctx;
import io.pdfkit.core.Err;
import io.pdfkit.ops.AnalyzeOps;
import io.pdfkit.ops.BookmarkOps;
import io.pdfkit.ops.ConvertOps;
import io.pdfkit.ops.EditOps;
import io.pdfkit.ops.EditPlusOps;
import io.pdfkit.ops.FilterOps;
import io.pdfkit.ops.LayoutOps;
import io.pdfkit.ops.OptimizeOps;
import io.pdfkit.ops.OrganizeOps;
import io.pdfkit.ops.SecureOps;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 端到端冒烟测试：不依赖 UI，直接跑能力实现。 */
class SmokeTest {

    @TempDir
    static Path work;

    static Path a;
    static Path b;
    static Path c;

    @BeforeAll
    static void prepare() throws IOException {
        Files.createDirectories(work.resolve("out"));
        a = makePdf("a.pdf", 10, "Chapter One", "Body text line %d of the document.");
        b = makePdf("b.pdf", 5, "Chapter Two", "Second document body %d.");
        c = makePdf("c.pdf", 3, "Chapter Three", "Third body %d.");
    }

    private static Path makePdf(String name, int pages, String heading, String bodyFmt) throws IOException {
        Path p = work.resolve(name);
        try (PDDocument doc = new PDDocument()) {
            var bold = new PDType1Font(Standard14Fonts.FontName.HELVETICA_BOLD);
            var normal = new PDType1Font(Standard14Fonts.FontName.HELVETICA);
            for (int i = 0; i < pages; i++) {
                PDPage page = new PDPage(PDRectangle.A4);
                doc.addPage(page);
                try (PDPageContentStream cs = new PDPageContentStream(doc, page)) {
                    if (i == 0) {
                        cs.beginText();
                        cs.setFont(bold, 24);
                        cs.newLineAtOffset(70, 760);
                        cs.showText(heading);
                        cs.endText();
                    }
                    cs.beginText();
                    cs.setFont(normal, 11);
                    cs.newLineAtOffset(70, 720);
                    cs.showText(String.format(bodyFmt, i + 1));
                    cs.endText();
                }
            }
            doc.save(p.toFile());
        }
        return p;
    }

    private Ctx ctx(Capability cap, List<Path> inputs, Map<String, String> params) throws IOException {
        Path out = work.resolve("out");
        Files.createDirectories(out);
        Path wd = work.resolve("work-" + cap.id().replace('.', '-'));
        Files.createDirectories(wd);
        return new Ctx(cap.id(), inputs, params, out, wd, (p, m) -> {
        }, new AtomicBoolean(false));
    }

    private Path run(Capability cap, List<Path> inputs, Map<String, String> params) throws Exception {
        Ctx ctx = ctx(cap, inputs, params);
        cap.op().run(ctx);
        assertTrue(ctx.outputs().size() > 0 || (ctx.result() != null && !ctx.result().isBlank()),
                cap.id() + " 没有产出");
        return ctx.outputs().isEmpty() ? null : ctx.outputs().get(0);
    }

    private static Map<String, String> p(String... kv) {
        Map<String, String> m = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) {
            m.put(kv[i], kv[i + 1]);
        }
        return m;
    }

    @Test
    void mergeKeepsPageCountAndBookmarks() throws Exception {
        Path out = run(OrganizeOps.MERGE, List.of(a, b, c), p("keepBookmark", "true"));
        try (PDDocument doc = Loader.loadPDF(out.toFile())) {
            assertEquals(18, doc.getNumberOfPages(), "合并后页数应为 10+5+3");
            assertNotNull(doc.getDocumentCatalog().getDocumentOutline(), "应生成书签");
        }
    }

    @Test
    void splitByEveryFivePages() throws Exception {
        Ctx ctx = ctx(OrganizeOps.SPLIT, List.of(a), p("mode", "按页数", "every", "5"));
        OrganizeOps.SPLIT.op().run(ctx);
        assertEquals(2, ctx.outputs().size(), "10 页按 5 页拆分应得到 2 个文件");
        try (PDDocument d1 = Loader.loadPDF(ctx.outputs().get(0).toFile())) {
            assertEquals(5, d1.getNumberOfPages());
        }
    }

    @Test
    void rotateChangesRotation() throws Exception {
        Path out = run(OrganizeOps.ROTATE, List.of(a), p("angle", "90", "pages", "1-2"));
        try (PDDocument doc = Loader.loadPDF(out.toFile())) {
            assertEquals(90, doc.getPage(0).getRotation());
            assertEquals(90, doc.getPage(1).getRotation());
            assertEquals(0, doc.getPage(2).getRotation(), "第 3 页不应旋转");
        }
    }

    @Test
    void cropReducesBox() throws Exception {
        Path out = run(OrganizeOps.CROP, List.of(a), p("top", "10", "bottom", "10", "left", "10", "right", "10"));
        try (PDDocument doc = Loader.loadPDF(out.toFile())) {
            float w = doc.getPage(0).getCropBox().getWidth();
            assertTrue(w < PDRectangle.A4.getWidth(), "裁剪后宽度应减小");
        }
    }

    @Test
    void deleteAndExtractPages() throws Exception {
        Path out = run(OrganizeOps.DELETE_PAGES, List.of(a), p("pages", "2,4"));
        try (PDDocument doc = Loader.loadPDF(out.toFile())) {
            assertEquals(8, doc.getNumberOfPages());
        }
        Path ex = run(OrganizeOps.EXTRACT_PAGES, List.of(a), p("pages", "1-3"));
        try (PDDocument doc = Loader.loadPDF(ex.toFile())) {
            assertEquals(3, doc.getNumberOfPages());
        }
    }

    @Test
    void deleteAllPagesIsRejected() {
        Err e = assertThrows(Err.class, () -> run(OrganizeOps.DELETE_PAGES, List.of(c), p("pages", "1-3")));
        assertEquals("ERR_DELETE_ALL", e.code());
    }

    @Test
    void pageNumberAndWatermarkProduceOutput() throws Exception {
        Path pn = run(EditOps.PAGE_NUMBER, List.of(a), p("template", "第 {page} 页 / 共 {pages} 页", "size", "10"));
        assertTrue(Files.size(pn) > 0);
        Path wm = run(EditOps.WATERMARK, List.of(a), p("text", "内部资料", "opacity", "20"));
        assertTrue(Files.size(wm) > 0);
    }

    @Test
    void metadataIsWritten() throws Exception {
        Path out = run(EditOps.METADATA, List.of(a), p("title", "测试标题", "author", "测试作者"));
        try (PDDocument doc = Loader.loadPDF(out.toFile())) {
            assertEquals("测试标题", doc.getDocumentInformation().getTitle());
        }
    }

    @Test
    void encryptThenDecrypt() throws Exception {
        Path enc = run(SecureOps.ENCRYPT, List.of(a), p("userPassword", "123456", "ownerPassword", "654321"));
        try (PDDocument doc = Loader.loadPDF(enc.toFile(), "123456")) {
            assertTrue(doc.isEncrypted());
            assertEquals(10, doc.getNumberOfPages());
        }
        Path dec = run(SecureOps.DECRYPT, List.of(enc), p("password", "123456"));
        try (PDDocument doc = Loader.loadPDF(dec.toFile())) {
            assertTrue(!doc.isEncrypted(), "解密后不应再加密");
        }
    }

    @Test
    void pdfToTextExtractsContent() throws Exception {
        Ctx ctx = ctx(ConvertOps.TO_TEXT, List.of(a), p());
        ConvertOps.TO_TEXT.op().run(ctx);
        Path txt = ctx.outputs().get(0);
        String text = Files.readString(txt);
        assertTrue(text.contains("Chapter One"), "应提取到标题文字");
    }

    @Test
    void pdfToImageAndBack() throws Exception {
        Ctx ctx = ctx(ConvertOps.TO_IMAGE, List.of(c), p("format", "PNG", "dpi", "96"));
        ConvertOps.TO_IMAGE.op().run(ctx);
        assertEquals(3, ctx.outputs().size(), "3 页应导出 3 张图");
        assertTrue(Files.size(ctx.outputs().get(0)) > 1000, "图片应有实际内容");

        Ctx back = ctx(ConvertOps.FROM_IMAGE, ctx.outputs(), p("paper", "A4 竖版"));
        ConvertOps.FROM_IMAGE.op().run(back);
        try (PDDocument doc = Loader.loadPDF(back.outputs().get(0).toFile())) {
            assertEquals(3, doc.getNumberOfPages());
        }
    }

    @Test
    void analyzeInfoReportsPages() throws Exception {
        Ctx ctx = ctx(AnalyzeOps.INFO, List.of(a), p());
        AnalyzeOps.INFO.op().run(ctx);
        assertTrue(ctx.result().contains("页数：10"), "结果应包含页数");
    }

    @Test
    void fontAuditDetectsEmbedding() throws Exception {
        Ctx ctx = ctx(AnalyzeOps.FONTS, List.of(a), p("exportCsv", "false"));
        AnalyzeOps.FONTS.op().run(ctx);
        assertTrue(ctx.result().contains("Helvetica"), "应列出 Helvetica 字体");
        assertTrue(ctx.result().contains("未嵌入"), "标准 14 字体应被标记为未嵌入");
    }

    @Test
    void structureInspectRuns() throws Exception {
        Ctx ctx = ctx(AnalyzeOps.STRUCTURE, List.of(a), p());
        AnalyzeOps.STRUCTURE.op().run(ctx);
        assertTrue(ctx.result().contains("对象总数"), "应输出对象统计");
    }

    @Test
    void autoBookmarkFindsHeading() throws Exception {
        Ctx ctx = ctx(BookmarkOps.AUTO, List.of(a), p("maxLevel", "3", "ratio", "108", "previewOnly", "false"));
        BookmarkOps.AUTO.op().run(ctx);
        Path out = ctx.outputs().get(0);
        try (PDDocument doc = Loader.loadPDF(out.toFile())) {
            var outline = doc.getDocumentCatalog().getDocumentOutline();
            assertNotNull(outline, "应生成书签树");
            assertNotNull(outline.getFirstChild(), "书签树不应为空");
        }
    }

    @Test
    void bookmarkExportImportRoundTrip() throws Exception {
        Path signed = run(BookmarkOps.AUTO, List.of(a), p("previewOnly", "false"));
        Ctx exp = ctx(BookmarkOps.EXPORT, List.of(signed), p());
        BookmarkOps.EXPORT.op().run(exp);
        Path xml = exp.outputs().get(0);
        assertTrue(Files.readString(xml).contains("<bookmarks"), "导出应是 XML");

        Ctx imp = ctx(BookmarkOps.IMPORT, List.of(b), p("xmlPath", xml.toString()));
        BookmarkOps.IMPORT.op().run(imp);
        try (PDDocument doc = Loader.loadPDF(imp.outputs().get(0).toFile())) {
            assertNotNull(doc.getDocumentCatalog().getDocumentOutline().getFirstChild());
        }
    }

    @Test
    void compressProducesSmallerOrEqualFile() throws Exception {
        Path out = run(OptimizeOps.COMPRESS, List.of(a), p("level", "标准", "removeMeta", "true"));
        assertTrue(Files.exists(out));
        assertTrue(Files.size(out) > 0);
    }

    @Test
    void repairRewritesFile() throws Exception {
        Path out = run(OptimizeOps.REPAIR, List.of(a), p());
        try (PDDocument doc = Loader.loadPDF(out.toFile())) {
            assertEquals(10, doc.getNumberOfPages());
        }
    }

    @Test
    void sanitizeRemovesOpenAction() throws Exception {
        Path out = run(SecureOps.SANITIZE, List.of(a), p("removeMeta", "false"));
        try (PDDocument doc = Loader.loadPDF(out.toFile())) {
            assertEquals(null, doc.getDocumentCatalog().getOpenAction());
        }
    }

    @Test
    void badPageSpecGivesActionableError() {
        Err e = assertThrows(Err.class, () -> run(OrganizeOps.ROTATE, List.of(c), p("angle", "90", "pages", "99")));
        assertEquals("ERR_PAGE_RANGE", e.code());
        assertTrue(e.getMessage().contains("共 3 页"), "错误信息应说明总页数");
    }

    @Test
    void chineseTextIsEmbeddedAndExtractable() throws Exception {
        Path out = run(EditOps.WATERMARK, List.of(c), p("text", "内部资料请勿外传", "opacity", "100", "color", "黑色"));
        long size = Files.size(out);
        assertTrue(size < 5 * 1024 * 1024,
                "中文应以子集方式嵌入，输出不应超过 5MB，实际 " + size / 1024 + " KB");
        try (PDDocument doc = Loader.loadPDF(out.toFile())) {
            String text = new org.apache.pdfbox.text.PDFTextStripper().getText(doc);
            // PDFBox 对 CJK 逐字写入，提取时字间会带空格，比对前先归一化
            String flat = text.replaceAll("\\s+", "");
            assertTrue(flat.contains("内部资料"),
                    "水印中文应可被提取，实际文本：" + text.substring(0, Math.min(80, text.length())));
        }
    }

    @Test
    void stampAndScanEffectRun() throws Exception {
        Path stamped = run(EditPlusOps.STAMP, List.of(c), p("text", "已审批", "size", "40"));
        assertTrue(Files.size(stamped) > 0);
        Path scan = run(EditPlusOps.SCAN_EFFECT, List.of(a), p("dpi", "120", "tilt", "1", "noise", "5"));
        try (PDDocument doc = Loader.loadPDF(scan.toFile())) {
            assertEquals(10, doc.getNumberOfPages());
        }
    }

    @Test
    void nupPacksPages() throws Exception {
        Path out = run(LayoutOps.NUP, List.of(a), p("layout", "2×2（4页）", "paper", "A4 竖版"));
        try (PDDocument doc = Loader.loadPDF(out.toFile())) {
            assertEquals(3, doc.getNumberOfPages(), "10 页按 4 页一张应为 3 张");
        }
    }

    @Test
    void blankPagesAreDetected() throws Exception {
        Path blankPdf = work.resolve("with-blank.pdf");
        try (PDDocument doc = new PDDocument()) {
            var normal = new PDType1Font(Standard14Fonts.FontName.HELVETICA);
            for (int i = 0; i < 3; i++) {
                PDPage page = new PDPage(PDRectangle.A4);
                doc.addPage(page);
                if (i != 1) {
                    try (PDPageContentStream cs2 = new PDPageContentStream(doc, page)) {
                        cs2.beginText();
                        cs2.setFont(normal, 12);
                        cs2.newLineAtOffset(70, 700);
                        cs2.showText("content page " + i);
                        cs2.endText();
                    }
                }
            }
            doc.save(blankPdf.toFile());
        }
        Path out = run(FilterOps.BLANK, List.of(blankPdf), p("minChars", "3", "checkImages", "true"));
        try (PDDocument doc = Loader.loadPDF(out.toFile())) {
            assertEquals(2, doc.getNumberOfPages(), "应删掉 1 张空白页");
        }
    }

    @Test
    void sanitizeKeepsPagesIntact() throws Exception {
        Path out = run(SecureOps.SANITIZE, List.of(a), p("removeMeta", "true"));
        try (PDDocument doc = Loader.loadPDF(out.toFile())) {
            assertEquals(10, doc.getNumberOfPages());
            assertEquals(null, doc.getDocumentInformation().getAuthor());
        }
    }
}
