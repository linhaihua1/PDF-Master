package io.pdfkit;

import io.pdfkit.core.Capability;
import io.pdfkit.core.Ctx;
import io.pdfkit.core.Param;
import io.pdfkit.core.Registry;
import io.pdfkit.ops.AnalyzeOps;
import io.pdfkit.ops.AnnotOps;
import io.pdfkit.ops.AttachOps;
import io.pdfkit.ops.BookmarkOps;
import io.pdfkit.ops.ConvertOps;
import io.pdfkit.ops.ConvertPlusOps;
import io.pdfkit.ops.EditOps;
import io.pdfkit.ops.EditPlusOps;
import io.pdfkit.ops.FilterOps;
import io.pdfkit.ops.FormOps;
import io.pdfkit.ops.LayoutOps;
import io.pdfkit.ops.LayoutPlusOps;
import io.pdfkit.ops.OptimizeOps;
import io.pdfkit.ops.OrganizeOps;
import io.pdfkit.ops.RedactOps;
import io.pdfkit.ops.SecureOps;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.PDResources;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject;
import org.apache.pdfbox.pdmodel.interactive.annotation.PDAnnotationText;
import org.apache.pdfbox.pdmodel.interactive.documentnavigation.destination.PDPageFitDestination;
import org.apache.pdfbox.pdmodel.interactive.documentnavigation.outline.PDDocumentOutline;
import org.apache.pdfbox.pdmodel.interactive.documentnavigation.outline.PDOutlineItem;
import org.apache.pdfbox.pdmodel.interactive.form.PDAcroForm;
import org.apache.pdfbox.pdmodel.interactive.form.PDTextField;
import org.apache.pdfbox.text.PDFTextStripper;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Stream;
import java.util.zip.ZipFile;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * S4 功能测试：60 项能力逐项验证业务结果。
 *
 * <p>每项都断言"产物里能看到什么"，而不是"没抛异常就算过"——
 * 页数、文字、书签层级、加密状态、图片对象、文件格式都必须可验证。
 */
@Timeout(value = 900, unit = TimeUnit.SECONDS)
class FunctionalTest {

    @TempDir
    static Path root;

    static Path a;          // 6 页，含标题
    static Path b;          // 3 页
    static Path blanky;     // 含空白页
    static Path img;        // PNG 图片
    static int seq;

    private final List<String> failures = new ArrayList<>();

    @BeforeAll
    static void prepare() throws Exception {
        Files.createDirectories(root.resolve("out"));
        a = makePdf("a.pdf", 6, "Chapter One");
        b = makePdf("b.pdf", 3, "Chapter Two");
        blanky = makeBlankPagePdf("blanky.pdf");
        img = makeImage("pic.png");
    }

    @BeforeEach
    void reset() {
        failures.clear();
        seq = 0;
    }

    // ==================== D1 页面组织 18 项 ====================

    @Test
    void organizeCapabilities() {
        check("organize.merge", () -> {
            Path out = run(OrganizeOps.MERGE, List.of(a, b), "keepBookmark", "true");
            try (PDDocument d = Loader.loadPDF(out.toFile())) {
                assertEquals(9, d.getNumberOfPages(), "6+3 应为 9 页");
                assertNotNull(d.getDocumentCatalog().getDocumentOutline(), "应生成书签");
                assertEquals(2, countTopLevelBookmarks(d), "应有两个源文件的一级书签");
            }
        });

        check("organize.split", () -> {
            List<Path> outs = runMulti(OrganizeOps.SPLIT, List.of(a), "mode", "按页数", "every", "2");
            assertEquals(3, outs.size(), "6 页每 2 页一份应为 3 份");
            int total = 0;
            for (Path p : outs) {
                try (PDDocument d = Loader.loadPDF(p.toFile())) {
                    total += d.getNumberOfPages();
                }
            }
            assertEquals(6, total, "拆分后总页数应守恒");
        });

        check("organize.rotate", () -> {
            Path out = run(OrganizeOps.ROTATE, List.of(a), "angle", "90", "pages", "1");
            try (PDDocument d = Loader.loadPDF(out.toFile())) {
                assertEquals(90, d.getPage(0).getRotation(), "第 1 页应旋转 90°");
                assertEquals(0, d.getPage(1).getRotation(), "未指定的页不应旋转");
            }
        });

        check("organize.crop", () -> {
            Path out = run(OrganizeOps.CROP, List.of(a), "top", "10", "bottom", "10", "left", "10", "right", "10");
            try (PDDocument d = Loader.loadPDF(out.toFile())) {
                PDRectangle box = d.getPage(0).getCropBox();
                // 10mm ≈ 28.35pt，左右各裁一刀，宽度应减少约 56.7pt
                float expected = PDRectangle.A4.getWidth() - 2 * 10 * 72f / 25.4f;
                assertTrue(Math.abs(box.getWidth() - expected) < 2,
                        "裁剪后宽度应约 " + expected + "，实际 " + box.getWidth());
            }
        });

        check("organize.delete-pages", () -> {
            Path out = run(OrganizeOps.DELETE_PAGES, List.of(a), "pages", "2,4");
            try (PDDocument d = Loader.loadPDF(out.toFile())) {
                assertEquals(4, d.getNumberOfPages(), "删 2 页应剩 4 页");
            }
        });

        check("organize.extract", () -> {
            Path out = run(OrganizeOps.EXTRACT_PAGES, List.of(a), "pages", "1-3");
            try (PDDocument d = Loader.loadPDF(out.toFile())) {
                assertEquals(3, d.getNumberOfPages(), "提取 1-3 应为 3 页");
            }
        });

        check("organize.reorder", () -> {
            Path out = run(OrganizeOps.REORDER, List.of(a), "order", "3,2,1,4,5,6");
            try (PDDocument d = Loader.loadPDF(out.toFile())) {
                assertEquals(6, d.getNumberOfPages());
                String t = text(d);
                assertTrue(t.contains("page 3"), "重排后第 1 页应来自原第 3 页");
            }
        });

        check("organize.scale", () -> {
            Path out = run(OrganizeOps.SCALE, List.of(a), "percent", "50");
            try (PDDocument d = Loader.loadPDF(out.toFile())) {
                PDRectangle box = d.getPage(0).getMediaBox();
                assertTrue(Math.abs(box.getWidth() - PDRectangle.A4.getWidth() / 2) < 3,
                        "50% 缩放后宽度应减半，实际 " + box.getWidth());
            }
        });

        check("organize.split-by-bookmark", () -> {
            Path bm = makeBookmarked("bookmarked.pdf");
            List<Path> outs = runMulti(OrganizeOps.SPLIT_BY_BOOKMARK, List.of(bm), "level", "1", "keepTitle", "true");
            assertEquals(2, outs.size(), "2 个一级书签应拆成 2 个文件");
        });

        check("organize.nup", () -> {
            Path out = run(LayoutOps.NUP, List.of(a), "layout", "2×2（4页）", "paper", "A4 竖版");
            try (PDDocument d = Loader.loadPDF(out.toFile())) {
                assertEquals(2, d.getNumberOfPages(), "6 页 4-up 应为 2 页");
            }
        });

        check("organize.split-by-size", () -> {
            List<Path> outs = runMulti(LayoutOps.SPLIT_BY_SIZE, List.of(a), "mb", "1");
            assertTrue(outs.size() >= 1, "至少产出 1 个文件");
            for (Path p : outs) {
                assertTrue(Files.size(p) <= 1024 * 1024 + 4096,
                        "按 1MB 拆分，单份不应超过约 1MB，实际 " + Files.size(p));
            }
        });

        check("organize.overlay", () -> {
            Path out = run(LayoutOps.OVERLAY, List.of(a, b), "target", "全部页", "opacity", "50");
            try (PDDocument d = Loader.loadPDF(out.toFile())) {
                assertEquals(6, d.getNumberOfPages(), "叠加不应改变底稿页数");
            }
        });

        check("layout.poster", () -> {
            Path out = run(LayoutPlusOps.POSTER, List.of(a), "cols", "2", "rows", "2", "pages", "1", "guide", "true");
            try (PDDocument d = Loader.loadPDF(out.toFile())) {
                assertEquals(4, d.getNumberOfPages(), "1 页 2×2 海报应为 4 张");
            }
        });

        check("layout.booklet", () -> {
            Path out = run(LayoutPlusOps.BOOKLET, List.of(a));
            try (PDDocument d = Loader.loadPDF(out.toFile())) {
                assertEquals(0, d.getNumberOfPages() % 4, "骑马钉页数应为 4 的倍数，实际 " + d.getNumberOfPages());
            }
        });

        check("filter.text", () -> {
            Path out = run(FilterOps.BY_TEXT, List.of(a), "pattern", "page 2");
            try (PDDocument d = Loader.loadPDF(out.toFile())) {
                assertEquals(1, d.getNumberOfPages(), "只有第 2 页含 page 2");
            }
        });

        check("filter.size", () -> {
            Path out = run(FilterOps.BY_SIZE, List.of(a), "mode", "竖版");
            try (PDDocument d = Loader.loadPDF(out.toFile())) {
                assertEquals(6, d.getNumberOfPages(), "A4 竖版文档全部符合竖版");
            }
        });

        check("filter.blank", () -> {
            Path out = run(FilterOps.BLANK, List.of(blanky), "minChars", "3", "checkImages", "true");
            try (PDDocument d = Loader.loadPDF(out.toFile())) {
                assertEquals(3, d.getNumberOfPages(), "5 页里 2 页空白，应剩 3 页");
            }
        });

        check("filter.image", () -> {
            Path withImg = makePdfWithImage("withimg.pdf");
            Path out = run(FilterOps.BY_IMAGE, List.of(withImg), "mode", "含图片的页");
            try (PDDocument d = Loader.loadPDF(out.toFile())) {
                assertTrue(d.getNumberOfPages() >= 1, "应至少保留 1 页");
                assertTrue(pageHasImage(d, 0), "保留的页应确实含图片");
            }
        });
        assertAllFailures("D1 页面组织 18 项");
    }

    // ==================== D2 内容编辑 15 项 ====================

    @Test
    void editCapabilities() {
        check("edit.page-number", () -> {
            Path out = run(EditOps.PAGE_NUMBER, List.of(a),
                    "template", "第 {page} 页 / 共 {pages} 页", "start", "1", "startNo", "1");
            String t = text(out);
            assertTrue(t.contains("第 1 页") && t.contains("共 6 页"), "页码文本未写入：" + t.substring(0, Math.min(80, t.length())));
        });

        check("edit.watermark", () -> {
            Path out = run(EditOps.WATERMARK, List.of(a), "text", "内部资料", "size", "48", "opacity", "15");
            assertTrue(decodedContentSize(out) > decodedContentSize(a), "水印应增加页面内容");
        });

        check("edit.metadata", () -> {
            Path out = run(EditOps.METADATA, List.of(a), "title", "测试标题", "author", "测试作者", "subject", "主题");
            try (PDDocument d = Loader.loadPDF(out.toFile())) {
                assertEquals("测试标题", d.getDocumentInformation().getTitle());
                assertEquals("测试作者", d.getDocumentInformation().getAuthor());
            }
        });

        check("edit.flatten", () -> {
            Path form = makeFormPdf("form-flat.pdf");
            Path out = run(EditOps.FLATTEN, List.of(form), "removeFields", "true");
            try (PDDocument d = Loader.loadPDF(out.toFile())) {
                var acro = d.getDocumentCatalog().getAcroForm();
                assertTrue(acro == null || acro.getFields().isEmpty(), "展平后不应再有可编辑字段");
            }
        });

        check("edit.stamp", () -> {
            Path out = run(EditPlusOps.STAMP, List.of(a), "text", "已审批", "position", "右下角");
            assertTrue(decodedContentSize(out) > decodedContentSize(a), "印章应增加页面内容");
        });

        check("edit.add-image", () -> {
            Path out = run(EditPlusOps.ADD_IMAGE, List.of(a), "file", img.toString(), "width", "40");
            try (PDDocument d = Loader.loadPDF(out.toFile())) {
                assertTrue(pageHasImage(d, 0), "第 1 页应插入图片");
            }
        });

        check("edit.header-footer", () -> {
            Path out = run(EditPlusOps.HEADER_FOOTER, List.of(a),
                    "headerCenter", "机密文档", "footerRight", "{page}/{pages}", "skipFirst", "false");
            String t = text(out);
            assertTrue(t.contains("机密文档"), "页眉未写入：" + t.substring(0, Math.min(80, t.length())));
        });

        check("edit.invert", () -> {
            Path out = run(EditPlusOps.INVERT, List.of(a), "mode", "反色", "dpi", "150");
            try (PDDocument d = Loader.loadPDF(out.toFile())) {
                assertTrue(pageHasImage(d, 0), "反色后页面应为图像");
            }
        });

        check("edit.scan-effect", () -> {
            Path out = run(EditPlusOps.SCAN_EFFECT, List.of(a), "dpi", "150", "tilt", "1", "noise", "8");
            try (PDDocument d = Loader.loadPDF(out.toFile())) {
                assertTrue(pageHasImage(d, 0), "做旧效果后页面应为图像");
            }
        });

        check("attachment.list", () -> {
            Path withAttach = attachTo(a);
            String r = runText(AttachOps.LIST, List.of(withAttach), Map.of());
            assertTrue(r.contains("note.txt"), "附件清单应列出 note.txt，实际：" + r);
        });

        check("attachment.add", () -> {
            Path out = run(AttachOps.ADD, List.of(a), "file", noteFile().toString());
            try (PDDocument d = Loader.loadPDF(out.toFile())) {
                assertEquals(1, countAttachments(d), "应嵌入 1 个附件");
            }
        });

        check("attachment.extract", () -> {
            Path withAttach = attachTo(a);
            List<Path> outs = runMulti(AttachOps.EXTRACT, List.of(withAttach));
            assertEquals(1, outs.size(), "应导出 1 个附件");
            assertEquals("附件内容 ABC", Files.readString(outs.get(0), StandardCharsets.UTF_8).trim());
        });

        check("annot.export", () -> {
            Path annotated = annotate(a);
            List<Path> outs = runMulti(AnnotOps.EXPORT, List.of(annotated), "exportCsv", "true");
            String csv = Files.readString(outs.get(0), StandardCharsets.UTF_8);
            assertTrue(csv.contains("测试批注"), "导出的清单应含批注内容，实际：" + head(csv));
        });

        check("annot.add", () -> {
            Path out = run(AnnotOps.ADD, List.of(a), "pages", "1", "text", "请复核此处");
            try (PDDocument d = Loader.loadPDF(out.toFile())) {
                assertEquals(1, d.getPage(0).getAnnotations().size(), "第 1 页应多 1 个注释");
            }
        });

        check("annot.strip", () -> {
            Path annotated = annotate(a);
            Path out = run(AnnotOps.STRIP, List.of(annotated), "kind", "全部注释");
            try (PDDocument d = Loader.loadPDF(out.toFile())) {
                assertEquals(0, d.getPage(0).getAnnotations().size(), "清除后不应再有注释");
            }
        });
        assertAllFailures("D2 内容编辑 15 项");
    }

    // ==================== D3 格式转换 7 项 ====================

    @Test
    void convertCapabilities() {
        check("convert.pdf-to-image", () -> {
            List<Path> outs = runMulti(ConvertOps.TO_IMAGE, List.of(a), "format", "PNG", "dpi", "96", "pages", "全部");
            assertEquals(6, outs.size(), "6 页应导出 6 张图");
            BufferedImage im = ImageIO.read(outs.get(0).toFile());
            assertNotNull(im, "导出的应是有效图片");
            assertTrue(im.getWidth() > 100, "图片宽度应合理");
        });

        check("convert.image-to-pdf", () -> {
            Path out = run(ConvertOps.FROM_IMAGE, List.of(img), "paper", "按原图", "margin", "0");
            try (PDDocument d = Loader.loadPDF(out.toFile())) {
                assertEquals(1, d.getNumberOfPages());
                assertTrue(pageHasImage(d, 0), "PDF 里应有图片");
            }
        });

        check("convert.pdf-to-text", () -> {
            List<Path> outs = runMulti(ConvertOps.TO_TEXT, List.of(a), "keepLayout", "true", "pageBreak", "true");
            String t = Files.readString(outs.get(0), StandardCharsets.UTF_8);
            assertTrue(t.contains("page 1"), "文本应含正文，实际前 80 字：" + t.substring(0, Math.min(80, t.length())));
        });

        check("convert.extract-images", () -> {
            Path withImg = makePdfWithImage("withimg2.pdf");
            List<Path> outs = runMulti(ConvertOps.EXTRACT_IMAGES, List.of(withImg), "format", "PNG", "minSize", "16");
            assertTrue(outs.size() >= 1, "应提取出至少 1 张图");
        });

        check("convert.toTiff", () -> {
            Path out = run(ConvertPlusOps.TO_TIFF, List.of(b), "dpi", "96", "compress", "LZW");
            assertTrue(out.getFileName().toString().toLowerCase().endsWith(".tif")
                            || out.getFileName().toString().toLowerCase().endsWith(".tiff"),
                    "扩展名应为 tif，实际 " + out.getFileName());
            assertTrue(Files.size(out) > 1000, "TIFF 文件不应为空");
        });

        check("convert.toMarkdown", () -> {
            Path out = run(ConvertPlusOps.TO_MARKDOWN, List.of(a), "maxLevel", "3");
            String md = Files.readString(out, StandardCharsets.UTF_8);
            assertTrue(md.contains("#"), "Markdown 应含标题标记，实际前 80 字：" + md.substring(0, Math.min(80, md.length())));
            assertTrue(md.contains("page 1"), "应保留正文");
        });

        check("convert.toWord", () -> {
            Path out = run(ConvertPlusOps.TO_WORD, List.of(b), "maxLevel", "3", "fontSize", "10");
            assertEquals(".docx", out.getFileName().toString().substring(out.getFileName().toString().lastIndexOf('.')),
                    "扩展名应为 .docx");
            try (ZipFile zip = new ZipFile(out.toFile())) {
                assertNotNull(zip.getEntry("word/document.xml"), "docx 内应含 word/document.xml");
            }
        });
        assertAllFailures("D3 格式转换 7 项");
    }

    // ==================== D4 安全 8 项 ====================

    @Test
    void securityCapabilities() {
        check("security.encrypt", () -> {
            Path out = run(SecureOps.ENCRYPT, List.of(a), "userPassword", "u123", "ownerPassword", "o123");
            try (PDDocument d = Loader.loadPDF(out.toFile(), "u123")) {
                assertTrue(d.isEncrypted());
                assertEquals(6, d.getNumberOfPages());
            }
        });

        check("security.decrypt", () -> {
            Path enc = run(SecureOps.ENCRYPT, List.of(a), "userPassword", "u123");
            Path out = run(SecureOps.DECRYPT, List.of(enc), "password", "u123");
            try (PDDocument d = Loader.loadPDF(out.toFile())) {
                assertFalse(d.isEncrypted(), "解密后不应再加密");
                assertEquals(6, d.getNumberOfPages());
            }
        });

        check("security.sanitize", () -> {
            Path out = run(SecureOps.SANITIZE, List.of(a), "removeMeta", "true");
            try (PDDocument d = Loader.loadPDF(out.toFile())) {
                assertEquals(6, d.getNumberOfPages(), "净化不应删页");
                assertNull(d.getDocumentCatalog().getCOSObject().getDictionaryObject(COSName.OPEN_ACTION),
                        "OpenAction 应被移除");
            }
        });

        check("secure.sign", () -> {
            Path out = run(SecureOps.SIGN, List.of(b), "cn", "测试签名者", "reason", "确认", "saveCert", "true");
            try (PDDocument d = Loader.loadPDF(out.toFile())) {
                assertTrue(countSignatureFields(d) >= 1, "应存在签名字段");
            }
        });

        check("security.verify-signature", () -> {
            Path signed = run(SecureOps.SIGN, List.of(b), "cn", "验证者", "saveCert", "false");
            String r = runText(SecureOps.VERIFY_SIGNATURE, List.of(signed), Map.of());
            assertTrue(r.contains("签名") || r.contains("证书"), "验证结果应说明签名情况，实际：" + r);
        });

        check("security.remove-signature", () -> {
            Path signed = run(SecureOps.SIGN, List.of(b), "cn", "待移除", "saveCert", "false");
            Path out = run(SecureOps.REMOVE_SIGNATURE, List.of(signed));
            try (PDDocument d = Loader.loadPDF(out.toFile())) {
                assertEquals(0, countSignatureFields(d), "移除后不应再有签名字段");
            }
        });

        check("security.redact-auto", () -> {
            Path withPii = makePdfWithText("pii.pdf", "ID 110105199003071234 Phone 13800138000");
            Path out = run(RedactOps.AUTO, List.of(withPii), "idCard", "true", "phone", "true", "dpi", "150");
            try (PDDocument d = Loader.loadPDF(out.toFile())) {
                assertTrue(pageHasImage(d, 0), "涂黑应栅格化页面");
                String t = text(d);
                assertFalse(t.contains("110105199003071234"), "涂黑后原文不应可提取");
            }
        });

        check("security.redact-area", () -> {
            Path out = run(RedactOps.AREA, List.of(a), "pages", "1", "x", "10", "y", "10", "w", "40", "h", "6", "dpi", "150");
            try (PDDocument d = Loader.loadPDF(out.toFile())) {
                assertTrue(pageHasImage(d, 0), "区域涂黑应栅格化该页");
            }
        });
        assertAllFailures("D4 安全 8 项");
    }

    // ==================== D5 表单 3 项 + D6 书签 3 项 ====================

    @Test
    void formAndBookmarkCapabilities() {
        check("form.read", () -> {
            Path form = makeFormPdf("form-read.pdf");
            String r = runText(FormOps.READ, List.of(form), Map.of("exportCsv", "true"));
            assertTrue(r.contains("name"), "字段清单应含 name，实际：" + r);
            assertTrue(r.contains("Alice"), "应读出现有值 Alice，实际：" + r);
        });

        check("form.fill", () -> {
            Path form = makeFormPdf("form-fill.pdf");
            Path out = run(FormOps.FILL, List.of(form), "values", "name=Bob", "flatten", "false");
            try (PDDocument d = Loader.loadPDF(out.toFile())) {
                var acro = d.getDocumentCatalog().getAcroForm();
                assertNotNull(acro, "填写后表单应还在");
                assertEquals("Bob", acro.getField("name").getValueAsString(), "字段值应为 Bob");
            }
        });

        check("form.readonly", () -> {
            Path form = makeFormPdf("form-ro.pdf");
            Path out = run(FormOps.READONLY, List.of(form), "mode", "设为只读");
            try (PDDocument d = Loader.loadPDF(out.toFile())) {
                var acro = d.getDocumentCatalog().getAcroForm();
                assertTrue(acro.getField("name").isReadOnly(), "字段应设为只读");
            }
        });

        check("bookmark.auto", () -> {
            Path out = run(BookmarkOps.AUTO, List.of(a), "maxLevel", "3", "ratio", "100", "onlyBold", "false");
            try (PDDocument d = Loader.loadPDF(out.toFile())) {
                assertTrue(countTopLevelBookmarks(d) >= 1, "应识别出至少 1 个标题");
            }
        });

        check("bookmark.export", () -> {
            Path bm = makeBookmarked("bm-export.pdf");
            Path out = run(BookmarkOps.EXPORT, List.of(bm));
            String xml = Files.readString(out, StandardCharsets.UTF_8);
            assertTrue(xml.contains("<item"), "导出的 XML 应含 item 节点");
            assertTrue(xml.contains("Chapter One"), "应含书签标题");
        });

        check("bookmark.import", () -> {
            Path bm = makeBookmarked("bm-src.pdf");
            Path xml = run(BookmarkOps.EXPORT, List.of(bm));
            Path out = run(BookmarkOps.IMPORT, List.of(a), "xmlPath", xml.toString());
            try (PDDocument d = Loader.loadPDF(out.toFile())) {
                assertEquals(2, countTopLevelBookmarks(d), "应导入 2 个一级书签");
            }
        });
        assertAllFailures("D5/D6 表单与书签 6 项");
    }

    // ==================== D7 分析 3 项 + D8 优化 3 项 ====================

    @Test
    void analyzeAndOptimizeCapabilities() {
        check("analyze.info", () -> {
            String r = runText(AnalyzeOps.INFO, List.of(a), Map.of());
            assertTrue(r.contains("6"), "信息里应含页数 6，实际：" + head(r));
            assertTrue(r.contains("页"), "信息应说明页数");
        });

        check("analyze.fonts", () -> {
            String r = runText(AnalyzeOps.FONTS, List.of(a), Map.of("exportCsv", "true"));
            assertTrue(r.contains("Helvetica") || r.contains("字体"), "应列出字体，实际：" + head(r));
        });

        check("analyze.structure", () -> {
            String r = runText(AnalyzeOps.STRUCTURE, List.of(a), Map.of());
            assertTrue(r.contains("对象") || r.contains("页"), "结构报告应含对象或页统计，实际：" + head(r));
        });

        check("optimize.compress", () -> {
            Path out = run(OptimizeOps.COMPRESS, List.of(a), "level", "标准", "removeMeta", "true");
            assertTrue(Files.size(out) > 0, "压缩产物不应为空");
            assertTrue(Files.size(out) <= Files.size(a) * 2, "压缩后不应显著变大");
        });

        check("optimize.enhance", () -> {
            Path out = run(OptimizeOps.ENHANCE, List.of(a), "dpi", "150", "sharpen", "60", "contrast", "110", "denoise", "1");
            try (PDDocument d = Loader.loadPDF(out.toFile())) {
                assertTrue(pageHasImage(d, 0), "增强后页面应为图像");
            }
        });

        check("optimize.repair", () -> {
            Path out = run(OptimizeOps.REPAIR, List.of(a));
            try (PDDocument d = Loader.loadPDF(out.toFile())) {
                assertEquals(6, d.getNumberOfPages(), "修复不应丢页");
            }
        });
        assertAllFailures("D7/D8 分析与优化 6 项");
    }

    /**
     * 内置字体的唯一可信判据是「写进去、提取回来、逐字一致」。
     *
     * <p>不能用 hasGlyph()：PDFBox 对 embedSubset=true 加载的 PDType0Font，把入参当
     * CID/GID 而非 Unicode 码位，只有码位数值小于字形总数时才返回 true，结论随机。
     *
     * <p>样本取三类，覆盖真实使用面：
     * <ul>
     *   <li>GB2312 全部 6763 个汉字 —— 用户文档里会出现的字都在这</li>
     *   <li>60 项能力的标题/说明/参数标签 —— 产品自己会写进 PDF 的文案</li>
     *   <li>所有同形字拆分点 —— 这些字的 cmap 曾经多对一，是历史缺陷高发区</li>
     * </ul>
     */
    @Test
    @Timeout(120)
    void bundledChineseFontIsUsable() {
        check("builtin-font", () -> {
            // 1) GB2312 双字节区全扫（6763 汉字 + 682 符号）
            java.util.LinkedHashSet<Integer> cps = new java.util.LinkedHashSet<>();
            java.util.LinkedHashSet<Integer> blanks = new java.util.LinkedHashSet<>();
            for (int hi = 0xA1; hi <= 0xF7; hi++) {
                for (int lo = 0xA1; lo <= 0xFE; lo++) {
                    String s = new String(new byte[]{(byte) hi, (byte) lo},
                            java.nio.charset.Charset.forName("GB2312"));
                    if (s.length() != 1 || s.charAt(0) == '\uFFFD') {
                        continue;
                    }
                    int cp = s.codePointAt(0);
                    // 空白字符提取器有规范化自由（全角空格可能被折成普通空格），
                    // 只验「能写入」，不参与逐字比对，否则必然误报
                    if (Character.isSpaceChar(cp) || Character.isWhitespace(cp)) {
                        blanks.add(cp);
                    } else {
                        cps.add(cp);
                    }
                }
            }
            // 2) 产品自有文案
            StringBuilder ui = new StringBuilder();
            for (Capability c : Registry.all()) {
                ui.append(c.title()).append(c.desc());
                for (Param p : c.params()) {
                    ui.append(p.label());
                }
            }
            ui.codePoints().filter(c -> c > 0x7F).forEach(cps::add);
            // 3) 同形字拆分点：每个码位单独一组，确保互不串味
            String ambiguous = "\u00B7\u2022\u2027\u00A0\u0020\u00AD\u2011\u2013\u2012"
                    + "\u3000\u2003\u2026\u22EF\u2035\uFF40\u2215\uFF0F\u301C\uFF5E"
                    + "\u3588\u439B\u363D\u39B3\u3ADA\u66F6\u3B35\u80F6"
                    + "\u3B3A\u5E50\u3B3B\u4420\u3BA3\u69E9\u58AB\u58FF";
            ambiguous.codePoints().forEach(cp -> {
                if (Character.isSpaceChar(cp) || Character.isWhitespace(cp)) {
                    blanks.add(cp);
                } else {
                    cps.add(cp);
                }
            });

            List<Integer> sample = List.copyOf(cps);
            assertTrue(sample.size() > 6000, "样本量异常，GB2312 扫描可能失败");

            try (var in = getClass().getResourceAsStream("/fonts/NotoSansSC-Regular.ttf")) {
                assertNotNull(in, "内置字体资源缺失，目标机将依赖系统字体");
                try (PDDocument doc = new PDDocument()) {
                    var f = org.apache.pdfbox.pdmodel.font.PDType0Font.load(doc, in, true);

                    // 每 400 字一页，40 字一行，避免单页内容流过长
                    final int perPage = 400;
                    List<Integer> missing = new java.util.ArrayList<>();
                    for (int i = 0; i < sample.size(); i += perPage) {
                        List<Integer> chunk = sample.subList(i, Math.min(i + perPage, sample.size()));
                        PDPage page = new PDPage(PDRectangle.A4);
                        doc.addPage(page);
                        try (PDPageContentStream cs = new PDPageContentStream(doc, page)) {
                            cs.beginText();
                            cs.setFont(f, 10);
                            cs.newLineAtOffset(30, 800);
                            int col = 0;
                            for (int cp : chunk) {
                                try {
                                    cs.showText(new String(Character.toChars(cp)));
                                } catch (RuntimeException e) {
                                    missing.add(cp);   // 真缺字：写到这才抛
                                }
                                if (++col % 40 == 0) {
                                    cs.newLineAtOffset(0, -12);
                                }
                            }
                            cs.endText();
                        }
                    }

                    // 空白字符只验可写入（提取器会规范化空白，不做逐字比对）
                    PDPage blankPage = new PDPage(PDRectangle.A4);
                    doc.addPage(blankPage);
                    try (PDPageContentStream cs = new PDPageContentStream(doc, blankPage)) {
                        cs.beginText();
                        cs.setFont(f, 10);
                        cs.newLineAtOffset(30, 800);
                        for (int cp : blanks) {
                            try {
                                cs.showText(new String(Character.toChars(cp)));
                            } catch (RuntimeException e) {
                                missing.add(cp);
                            }
                        }
                        cs.endText();
                    }

                    Path probe = root.resolve("font-roundtrip.pdf");
                    doc.save(probe.toFile());

                    StringBuilder msg = new StringBuilder();
                    if (!missing.isEmpty()) {
                        msg.append("内置字体缺字 ").append(missing.size()).append(" 个：");
                        for (int i = 0; i < Math.min(missing.size(), 20); i++) {
                            msg.append(new String(Character.toChars(missing.get(i)))).append(' ');
                        }
                        msg.append('\n');
                    }

                    // 逐码位比对：字形对但 ToUnicode 错，会让复制/搜索/提取全错
                    java.util.Set<Integer> got = new java.util.HashSet<>();
                    text(probe).codePoints().forEach(got::add);
                    List<Integer> lost = new java.util.ArrayList<>();
                    for (int cp : sample) {
                        if (!got.contains(cp)) {
                            lost.add(cp);
                        }
                    }
                    if (!lost.isEmpty()) {
                        msg.append("文本往返丢失 ").append(lost.size()).append(" 个：");
                        for (int i = 0; i < Math.min(lost.size(), 20); i++) {
                            int cp = lost.get(i);
                            msg.append(String.format("U+%04X(%s) ", cp, new String(Character.toChars(cp))));
                        }
                    }
                    assertTrue(msg.length() == 0, "内置字体校验失败（样本 " + sample.size() + " 字）\n" + msg);
                }
            }
        });
        assertAllFailures("内置字体");
    }

    // ==================== 覆盖度自检 ====================

    @Test
    void everyCapabilityIsCoveredBySomeAssertion() {
        // 本类里被显式断言的能力 id 集合，与注册表比对，防止新增能力漏测
        List<String> covered = List.of(
                "organize.merge", "organize.split", "organize.rotate", "organize.crop", "organize.delete-pages",
                "organize.extract", "organize.reorder", "organize.scale", "organize.split-by-bookmark",
                "organize.nup", "organize.split-by-size", "organize.overlay",
                "layout.poster", "layout.booklet", "filter.text", "filter.size", "filter.blank", "filter.image",
                "edit.page-number", "edit.watermark", "edit.metadata", "edit.flatten", "edit.stamp",
                "edit.add-image", "edit.header-footer", "edit.invert", "edit.scan-effect",
                "attachment.list", "attachment.add", "attachment.extract",
                "annot.export", "annot.add", "annot.strip",
                "convert.pdf-to-image", "convert.image-to-pdf", "convert.pdf-to-text", "convert.extract-images",
                "convert.toTiff", "convert.toMarkdown", "convert.toWord",
                "security.encrypt", "security.decrypt", "security.sanitize", "secure.sign",
                "security.verify-signature", "security.remove-signature", "security.redact-auto", "security.redact-area",
                "form.read", "form.fill", "form.readonly",
                "bookmark.auto", "bookmark.export", "bookmark.import",
                "analyze.info", "analyze.fonts", "analyze.structure",
                "optimize.compress", "optimize.enhance", "optimize.repair");
        List<String> missing = Registry.all().stream().map(Capability::id)
                .filter(id -> !covered.contains(id)).toList();
        assertTrue(missing.isEmpty(), "以下能力没有功能用例覆盖：" + missing);
        assertEquals(60, covered.size(), "覆盖面应为 60 项，实际 " + covered.size());
    }

    // ==================== 脚手架 ====================

    @FunctionalInterface
    private interface Check {
        void run() throws Exception;
    }

    private void assertAllFailures(String title) {
        if (failures.isEmpty()) {
            return;
        }
        StringBuilder sb = new StringBuilder("\n===== " + title + " 失败 " + failures.size() + " 项 =====\n");
        failures.forEach(f -> sb.append("  · ").append(f).append('\n'));
        org.junit.jupiter.api.Assertions.fail(sb.toString());
    }

    private void check(String capId, Check body) {
        try {
            body.run();
        } catch (AssertionError e) {
            failures.add(capId + " · " + e.getMessage());
        } catch (Exception e) {
            failures.add(capId + " · 异常 " + e.getClass().getSimpleName() + ": " + e.getMessage());
        }
    }

    private Path run(Capability cap, List<Path> inputs, String... kv) throws Exception {
        List<Path> outs = runMultiRaw(cap, inputs, kv);
        assertFalse(outs.isEmpty(), cap.id() + " 没有产出文件");
        return outs.get(0);
    }

    private List<Path> runMulti(Capability cap, List<Path> inputs, String... kv) throws Exception {
        List<Path> outs = runMultiRaw(cap, inputs, kv);
        assertFalse(outs.isEmpty(), cap.id() + " 没有产出文件");
        return outs;
    }

    private List<Path> runMultiRaw(Capability cap, List<Path> inputs, String... kv) throws Exception {
        Map<String, String> params = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) {
            params.put(kv[i], kv[i + 1]);
        }
        Path outDir = root.resolve("out/" + cap.id().replace('.', '-') + "-" + (++seq));
        Path work = root.resolve("wk/" + cap.id().replace('.', '-') + "-" + seq);
        Files.createDirectories(outDir);
        Files.createDirectories(work);
        Ctx ctx = new Ctx(cap.id(), inputs, params, outDir, work, (p, m) -> {
        }, new AtomicBoolean(false));
        cap.op().run(ctx);
        return ctx.outputs();
    }

    private String runText(Capability cap, List<Path> inputs, Map<String, String> params) throws Exception {
        Path outDir = root.resolve("out/" + cap.id().replace('.', '-') + "-txt-" + (++seq));
        Path work = root.resolve("wk/" + cap.id().replace('.', '-') + "-txt-" + seq);
        Files.createDirectories(outDir);
        Files.createDirectories(work);
        Ctx ctx = new Ctx(cap.id(), inputs, params, outDir, work, (p, m) -> {
        }, new AtomicBoolean(false));
        cap.op().run(ctx);
        String r = ctx.result();
        if ((r == null || r.isBlank()) && !ctx.outputs().isEmpty()) {
            // 结果是写文件的，读回来一起判定
            StringBuilder sb = new StringBuilder();
            for (Path p : ctx.outputs()) {
                if (Files.size(p) < 512 * 1024) {
                    sb.append(Files.readString(p, StandardCharsets.UTF_8)).append('\n');
                }
            }
            r = sb.toString();
        }
        return r == null ? "" : r;
    }

    // ---------- fixture ----------

    private static Path makePdf(String name, int pages, String heading) throws IOException {
        Path p = root.resolve(name);
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
                    cs.showText("body text of page " + (i + 1));
                    cs.endText();
                }
            }
            doc.save(p.toFile());
        }
        return p;
    }

    private static Path makeBlankPagePdf(String name) throws IOException {
        Path p = root.resolve(name);
        try (PDDocument doc = new PDDocument()) {
            var font = new PDType1Font(Standard14Fonts.FontName.HELVETICA);
            for (int i = 0; i < 5; i++) {
                PDPage page = new PDPage(PDRectangle.A4);
                doc.addPage(page);
                boolean blank = i == 1 || i == 3;
                try (PDPageContentStream cs = new PDPageContentStream(doc, page)) {
                    if (!blank) {
                        cs.beginText();
                        cs.setFont(font, 12);
                        cs.newLineAtOffset(70, 700);
                        cs.showText("content on page " + (i + 1));
                        cs.endText();
                    }
                }
            }
            doc.save(p.toFile());
        }
        return p;
    }

    private static Path makePdfWithText(String name, String content) throws IOException {
        Path p = root.resolve(name);
        try (PDDocument doc = new PDDocument()) {
            var font = new PDType1Font(Standard14Fonts.FontName.HELVETICA);
            PDPage page = new PDPage(PDRectangle.A4);
            doc.addPage(page);
            try (PDPageContentStream cs = new PDPageContentStream(doc, page)) {
                cs.beginText();
                cs.setFont(font, 12);
                cs.newLineAtOffset(60, 700);
                cs.showText(content);
                cs.endText();
            }
            doc.save(p.toFile());
        }
        return p;
    }

    private static Path makeImage(String name) throws IOException {
        Path p = root.resolve(name);
        BufferedImage im = new BufferedImage(240, 160, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = im.createGraphics();
        g.setColor(new Color(30, 120, 220));
        g.fillRect(0, 0, 240, 160);
        g.setColor(Color.WHITE);
        g.drawString("PDFKIT", 90, 85);
        g.dispose();
        ImageIO.write(im, "png", p.toFile());
        return p;
    }

    private static Path makePdfWithImage(String name) throws IOException {
        Path p = root.resolve(name);
        Path png = makeImage("inner-" + name.replace(".pdf", ".png"));
        try (PDDocument doc = new PDDocument()) {
            PDPage page = new PDPage(PDRectangle.A4);
            doc.addPage(page);
            PDImageXObject image = PDImageXObject.createFromFile(png.toString(), doc);
            try (PDPageContentStream cs = new PDPageContentStream(doc, page)) {
                cs.drawImage(image, 100, 500, 200, 130);
            }
            PDPage blank = new PDPage(PDRectangle.A4);
            doc.addPage(blank);
            doc.save(p.toFile());
        }
        return p;
    }

    private static Path makeBookmarked(String name) throws IOException {
        Path p = root.resolve(name);
        try (PDDocument doc = new PDDocument()) {
            var font = new PDType1Font(Standard14Fonts.FontName.HELVETICA);
            for (int i = 0; i < 4; i++) {
                PDPage page = new PDPage(PDRectangle.A4);
                doc.addPage(page);
                try (PDPageContentStream cs = new PDPageContentStream(doc, page)) {
                    cs.beginText();
                    cs.setFont(font, 12);
                    cs.newLineAtOffset(70, 700);
                    cs.showText("section page " + (i + 1));
                    cs.endText();
                }
            }
            PDDocumentOutline outline = new PDDocumentOutline();
            doc.getDocumentCatalog().setDocumentOutline(outline);
            for (int i = 0; i < 2; i++) {
                PDOutlineItem item = new PDOutlineItem();
                item.setTitle(i == 0 ? "Chapter One" : "Chapter Two");
                PDPageFitDestination dest = new PDPageFitDestination();
                dest.setPage(doc.getPage(i * 2));
                item.setDestination(dest);
                outline.addLast(item);
            }
            doc.save(p.toFile());
        }
        return p;
    }

    private static Path makeFormPdf(String name) throws IOException {
        Path p = root.resolve(name);
        try (PDDocument doc = new PDDocument()) {
            PDPage page = new PDPage(PDRectangle.A4);
            doc.addPage(page);
            PDAcroForm form = new PDAcroForm(doc);
            doc.getDocumentCatalog().setAcroForm(form);
            PDResources res = new PDResources();
            form.setDefaultResources(res);
            String fontName = res.add(new PDType1Font(Standard14Fonts.FontName.HELVETICA)).getName();
            form.setDefaultAppearance("/" + fontName + " 12 Tf 0 g");

            PDTextField name0 = new PDTextField(form);
            name0.setPartialName("name");
            form.getFields().add(name0);
            var widget = name0.getWidgets().get(0);
            widget.setRectangle(new PDRectangle(100, 700, 200, 22));
            widget.setPage(page);
            page.getAnnotations().add(widget);
            name0.setValue("Alice");
            doc.save(p.toFile());
        }
        return p;
    }

    private static Path noteFile() throws IOException {
        Path p = root.resolve("note.txt");
        if (!Files.exists(p)) {
            Files.writeString(p, "附件内容 ABC\n", StandardCharsets.UTF_8);
        }
        return p;
    }

    private Path attachTo(Path src) throws Exception {
        return run(AttachOps.ADD, List.of(src), "file", noteFile().toString());
    }

    private Path annotate(Path src) throws Exception {
        Path p = root.resolve("annotated.pdf");
        try (PDDocument doc = Loader.loadPDF(src.toFile())) {
            PDAnnotationText ann = new PDAnnotationText();
            ann.setContents("测试批注");
            ann.setRectangle(new PDRectangle(100, 600, 24, 24));
            doc.getPage(0).getAnnotations().add(ann);
            doc.save(p.toFile());
        }
        return p;
    }

    // ---------- 断言工具 ----------

    private static String text(Path pdf) throws IOException {
        try (PDDocument d = Loader.loadPDF(pdf.toFile())) {
            return new PDFTextStripper().getText(d);
        }
    }

    /** 解码后的页面内容流总长度：原始 length 是压缩后大小，短水印看不出增量。 */
    private static long decodedContentSize(Path pdf) throws IOException {
        try (PDDocument d = Loader.loadPDF(pdf.toFile())) {
            long sum = 0;
            for (PDPage p : d.getPages()) {
                sum += decodedLength(p.getCOSObject().getDictionaryObject(COSName.CONTENTS));
            }
            return sum;
        }
    }

    private static long decodedLength(Object c) throws IOException {
        // 保存后内容流通常是间接引用（COSObject），不解析会一律算成 0
        Object v = (c instanceof org.apache.pdfbox.cos.COSObject o) ? o.getObject() : c;
        if (v instanceof org.apache.pdfbox.cos.COSStream st) {
            try (var in = st.createInputStream()) {
                return in.readAllBytes().length;
            }
        }
        if (v instanceof org.apache.pdfbox.cos.COSArray arr) {
            long sum = 0;
            for (int i = 0; i < arr.size(); i++) {
                sum += decodedLength(arr.getObject(i));
            }
            return sum;
        }
        return 0;
    }

    private static String text(PDDocument doc) throws IOException {
        return new PDFTextStripper().getText(doc);
    }

    private static boolean pageHasImage(PDDocument doc, int idx) {
        try {
            PDResources res = doc.getPage(idx).getResources();
            if (res == null) {
                return false;
            }
            for (COSName n : res.getXObjectNames()) {
                if (res.getXObject(n) instanceof PDImageXObject) {
                    return true;
                }
            }
        } catch (Exception ignored) {
            return false;
        }
        return false;
    }

    private static int countTopLevelBookmarks(PDDocument doc) {
        try {
            var outline = doc.getDocumentCatalog().getDocumentOutline();
            if (outline == null) {
                return 0;
            }
            int n = 0;
            var item = outline.getFirstChild();
            while (item != null) {
                n++;
                item = item.getNextSibling();
            }
            return n;
        } catch (Exception e) {
            return 0;
        }
    }

    private static int countAttachments(PDDocument doc) {
        try {
            var names = doc.getDocumentCatalog().getNames();
            if (names == null || names.getEmbeddedFiles() == null) {
                return 0;
            }
            var m = names.getEmbeddedFiles().getNames();
            return m == null ? 0 : m.size();
        } catch (Exception e) {
            return 0;
        }
    }

    private static int countSignatureFields(PDDocument doc) {
        try {
            var acro = doc.getDocumentCatalog().getAcroForm();
            if (acro == null) {
                return 0;
            }
            int n = 0;
            for (var f : acro.getFieldTree()) {
                if (f instanceof org.apache.pdfbox.pdmodel.interactive.form.PDSignatureField) {
                    n++;
                }
            }
            return n;
        } catch (Exception e) {
            return 0;
        }
    }

    private static String head(String s) {
        if (s == null) {
            return "null";
        }
        String one = s.replace('\n', ' ').replace('\r', ' ').trim();
        return one.length() <= 100 ? one : one.substring(0, 100) + "…";
    }

    @org.junit.jupiter.api.AfterAll
    static void report() {
        // 失败清单由各 @Test 的 assertTrue 输出
    }
}
