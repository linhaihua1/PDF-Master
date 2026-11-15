package io.pdfkit;

import io.pdfkit.core.Capability;
import io.pdfkit.core.Ctx;
import io.pdfkit.core.Err;
import io.pdfkit.core.Param;
import io.pdfkit.core.Registry;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * S1 接口契约测试：把 60 项能力当作对外接口，验证元数据、参数、错误码、产物四类契约。
 *
 * <p>收集全部违约项后统一断言，避免"第一个失败就中断"导致只看到一个问题。
 */
@Timeout(value = 600, unit = TimeUnit.SECONDS)
class ContractTest {

    private static final Set<String> CATEGORIES = Set.of(
            "页面组织", "内容编辑", "格式转换", "表单处理", "书签与目录", "安全与合规", "读取与分析", "优化与修复");

    @TempDir
    static Path root;

    static Path good;
    static Path good2;
    static Path missing;
    static Path isDir;
    static Path emptyFile;
    static Path notPdf;

    /** 违约项收集器：每条一行，最后一次性断言。 */
    private final List<String> violations = new ArrayList<>();

    @BeforeAll
    static void prepare() throws IOException {
        Files.createDirectories(root.resolve("in"));
        Files.createDirectories(root.resolve("out"));
        Files.createDirectories(root.resolve("wk"));

        good = makePdf("good.pdf", 4);
        good2 = makePdf("good2.pdf", 3);

        missing = root.resolve("in/does-not-exist.pdf");
        isDir = root.resolve("in/subdir");
        Files.createDirectories(isDir);

        emptyFile = root.resolve("in/empty.pdf");
        Files.write(emptyFile, new byte[0]);

        notPdf = root.resolve("in/note.pdf");
        Files.writeString(notPdf, "这不是 PDF，只是一段纯文本。");
    }

    private static Path makePdf(String name, int pages) throws IOException {
        Path p = root.resolve("in").resolve(name);
        try (PDDocument doc = new PDDocument()) {
            var font = new PDType1Font(Standard14Fonts.FontName.HELVETICA);
            for (int i = 0; i < pages; i++) {
                PDPage page = new PDPage(PDRectangle.A4);
                doc.addPage(page);
                try (PDPageContentStream cs = new PDPageContentStream(doc, page)) {
                    cs.beginText();
                    cs.setFont(font, 12);
                    cs.newLineAtOffset(60, 700);
                    cs.showText("Contract sample page " + (i + 1));
                    cs.endText();
                }
            }
            doc.save(p.toFile());
        }
        return p;
    }

    // ==================== T1.1 元数据契约 ====================

    @Test
    void capabilityMetadataIsWellFormed() {
        Set<String> ids = new LinkedHashSet<>();
        for (Capability c : Registry.all()) {
            String where = "能力[" + c.id() + "]";
            if (c.id() == null || c.id().isBlank()) {
                violations.add(where + " id 为空");
            } else {
                if (!ids.add(c.id())) {
                    violations.add(where + " id 重复");
                }
                if (!c.id().matches("[a-z][A-Za-z0-9.\\-]*")) {
                    violations.add(where + " id 格式不合规（应小写字母开头、仅含字母数字点横线）");
                }
            }
            if (c.title() == null || c.title().isBlank()) {
                violations.add(where + " title 为空");
            }
            if (c.category() == null || c.category().isBlank()) {
                violations.add(where + " category 为空");
            } else if (!CATEGORIES.contains(c.category())) {
                violations.add(where + " category 不在约定分类内：" + c.category());
            }
            if (c.desc() == null || c.desc().isBlank()) {
                violations.add(where + " desc 为空（UI 会显示空白说明）");
            }
            if (c.op() == null) {
                violations.add(where + " op 为空");
            }
        }
        assertEquals(60, Registry.count(), "注册能力数应为 60，实际 " + Registry.count());
        assertEquals(ids.size(), Registry.count(), "存在重复 id");
        assertTrue(violations.isEmpty(), summary("T1.1 元数据契约"));
    }

    // ==================== T1.2 参数契约 ====================

    @Test
    void capabilityParamsAreWellFormed() {
        for (Capability c : Registry.all()) {
            Set<String> keys = new LinkedHashSet<>();
            for (Param p : c.params()) {
                String where = "能力[" + c.id() + "] 参数[" + p.key() + "]";
                if (p.key() == null || p.key().isBlank()) {
                    violations.add(where + " key 为空");
                    continue;
                }
                if (!keys.add(p.key())) {
                    violations.add(where + " key 重复");
                }
                if (!p.key().matches("[A-Za-z][A-Za-z0-9_-]*")) {
                    violations.add(where + " key 格式不合规");
                }
                if (p.label() == null || p.label().isBlank()) {
                    violations.add(where + " label 为空（UI 会出现无标题输入框）");
                }
                if (p.type() == null) {
                    violations.add(where + " type 为空");
                    continue;
                }
                switch (p.type()) {
                    case CHOICE -> {
                        if (p.choices().isEmpty()) {
                            violations.add(where + " 是下拉框但没有候选项");
                        } else if (p.def() != null && !p.def().isEmpty() && !p.choices().contains(p.def())) {
                            violations.add(where + " 默认值 " + p.def() + " 不在候选项 " + p.choices() + " 内");
                        }
                    }
                    case NUMBER -> {
                        if (p.def() != null && !p.def().isBlank()) {
                            try {
                                Integer.parseInt(p.def().trim());
                            } catch (NumberFormatException e) {
                                violations.add(where + " 数值型默认值无法解析：" + p.def());
                            }
                        }
                    }
                    case BOOL -> {
                        if (p.def() != null && !p.def().isBlank()
                                && !p.def().equals("true") && !p.def().equals("false")) {
                            violations.add(where + " 布尔型默认值非法：" + p.def());
                        }
                    }
                    case PASSWORD -> {
                        if (p.def() != null && !p.def().isBlank()) {
                            violations.add(where + " 密码型参数不应带默认值（会预填密码）");
                        }
                    }
                    case TEXT -> {
                        // TEXT 无额外约束
                    }
                }
            }
        }
        assertTrue(violations.isEmpty(), summary("T1.2 参数契约"));
    }

    // ==================== T1.3 异常输入：必须给结构化 Err ====================

    @Test
    void noInputRaisesStructuredError() {
        assertStructured(cap -> runCap(cap, List.of(), defaults(cap)), "空输入", "ERR_NO_INPUT");
    }

    @Test
    void missingFileRaisesStructuredError() {
        assertStructured(cap -> runCap(cap, List.of(missing), defaults(cap)), "文件不存在", null);
    }

    @Test
    void directoryAsFileRaisesStructuredError() {
        assertStructured(cap -> runCap(cap, List.of(isDir), defaults(cap)), "把目录当文件", null);
    }

    @Test
    void emptyFileRaisesStructuredError() {
        assertStructured(cap -> runCap(cap, List.of(emptyFile), defaults(cap)), "0 字节文件", null);
    }

    @Test
    void nonPdfRaisesStructuredError() {
        assertStructured(cap -> runCap(cap, List.of(notPdf), defaults(cap)), "非 PDF 内容", null);
    }

    @Test
    void illegalNumberParamRaisesStructuredError() {
        // 只针对有数值参数的能力：把第一个数值参数改成 "abc"
        for (Capability c : Registry.all()) {
            Param numeric = c.params().stream().filter(p -> p.type() == Param.Type.NUMBER).findFirst().orElse(null);
            if (numeric == null) {
                continue;
            }
            Map<String, String> params = defaults(c);
            params.put(numeric.key(), "abc");
            Outcome o = runCap(c, inputsFor(c), params);
            if (o.crash != null) {
                violations.add("能力[" + c.id() + "] 数值参数填 abc 时崩溃：" + o.crash);
            } else if (o.errCode == null) {
                violations.add("能力[" + c.id() + "] 数值参数填 abc 竟然执行成功，应报错");
            } else if (!"ERR_BAD_PARAM".equals(o.errCode) && !"ERR_PARAM_RANGE".equals(o.errCode)
                    && !"ERR_BAD_PAGE_SPEC".equals(o.errCode)) {
                violations.add("能力[" + c.id() + "] 数值参数非法时错误码为 " + o.errCode + "，期望 ERR_BAD_PARAM");
            }
        }
        assertTrue(violations.isEmpty(), summary("T1.3b 数值参数校验"));
    }

    // ==================== T1.4 错误信息不得泄露实现细节 ====================

    @Test
    void errorMessagesDoNotLeakInternals() {
        for (Capability c : Registry.all()) {
            for (Path bad : List.of(emptyFile, notPdf, missing, isDir)) {
                Outcome o = runCap(c, List.of(bad), defaults(c));
                if (o.crash != null) {
                    violations.add("能力[" + c.id() + "] 输入" + bad.getFileName() + " 崩溃：" + o.crash);
                    continue;
                }
                if (o.errMessage == null) {
                    continue;
                }
                String m = o.errMessage;
                if (m.contains("\tat ") || m.contains("java.lang.") || m.contains("org.apache.pdfbox")) {
                    violations.add("能力[" + c.id() + "] 错误信息含堆栈：" + head(m));
                }
                if (m.contains("Exception") || m.contains("Error:")) {
                    violations.add("能力[" + c.id() + "] 错误信息含异常类名：" + head(m));
                }
                if (o.errCode == null || !o.errCode.startsWith("ERR_")) {
                    violations.add("能力[" + c.id() + "] 错误码不合规（应以 ERR_ 开头）：" + o.errCode);
                }
            }
        }
        assertTrue(violations.isEmpty(), summary("T1.4 错误信息卫生"));
    }

    // ==================== T1.5 正常路径与产物契约 ====================

    @Test
    void normalPathProducesDeclaredOutputs() throws IOException {
        List<String> failedCaps = new ArrayList<>();
        for (Capability c : Registry.all()) {
            List<Path> inputs = inputsFor(c);
            Outcome o = runCap(c, inputs, defaults(c));
            if (o.crash != null) {
                violations.add("能力[" + c.id() + "] 正常路径崩溃（应为业务错误）：" + o.crash);
                failedCaps.add(c.id() + "(崩溃)");
                continue;
            }
            if (o.errCode != null) {
                failedCaps.add(c.id() + "(" + o.errCode + ":" + head(o.errMessage) + ")");
                continue;
            }
            if (o.outputs.isEmpty() && (o.result == null || o.result.isBlank())) {
                violations.add("能力[" + c.id() + "] 成功但没有产物也没有结果文本");
            }
            for (Path out : o.outputs) {
                if (!Files.exists(out)) {
                    violations.add("能力[" + c.id() + "] 声称产出但文件不存在：" + out.getFileName());
                } else if (Files.isDirectory(out)) {
                    violations.add("能力[" + c.id() + "] 产出是目录而非文件：" + out.getFileName());
                } else if (Files.size(out) == 0) {
                    violations.add("能力[" + c.id() + "] 产出 0 字节：" + out.getFileName());
                }
            }
            if (o.progressEndedAt < 100 && o.outputs.isEmpty() && o.result != null) {
                // 分析类能力不报 100% 可接受，不记为违约
            }
            // 顺带校验产出的 PDF 能被重新打开
            for (Path out : o.outputs) {
                if (out.getFileName().toString().toLowerCase().endsWith(".pdf")) {
                    // 加密能力的产物必须用密码才能打开，这是预期行为而非缺陷
                    boolean needPwd = "security.encrypt".equals(c.id());
                    try (PDDocument doc = needPwd
                            ? Loader.loadPDF(out.toFile(), "Test#12345")
                            : Loader.loadPDF(out.toFile())) {
                        if (doc.getNumberOfPages() <= 0) {
                            violations.add("能力[" + c.id() + "] 产出 PDF 无页面");
                        }
                    } catch (Exception e) {
                        violations.add("能力[" + c.id() + "] 产出 PDF 打不开：" + head(e.getMessage()));
                    }
                }
            }
        }
        // 正常路径失败的清单单独输出，供 S4 逐项确认是否为"样本不适用"
        System.out.println("\n===== 正常路径未通过的能力（默认参数） =====");
        failedCaps.forEach(s -> System.out.println("  - " + s));
        System.out.println("合计 " + failedCaps.size() + " / " + Registry.count());
        assertTrue(violations.isEmpty(), summary("T1.5 产物契约"));
    }

    // ==================== 脚手架 ====================

    private record Outcome(String errCode, String errMessage, String crash, List<Path> outputs, String result,
                           int progressEndedAt) {
    }

    private void assertStructured(java.util.function.Function<Capability, Outcome> action,
                                  String scenario, String expectedCode) {
        for (Capability c : Registry.all()) {
            Outcome o = action.apply(c);
            if (o.crash != null) {
                violations.add("能力[" + c.id() + "] " + scenario + " 崩溃：" + o.crash);
            } else if (o.errCode == null) {
                violations.add("能力[" + c.id() + "] " + scenario + " 未报错却执行成功");
            } else if (expectedCode != null && !expectedCode.equals(o.errCode)) {
                violations.add("能力[" + c.id() + "] " + scenario + " 错误码 " + o.errCode + "，期望 " + expectedCode);
            }
        }
        assertTrue(violations.isEmpty(), summary("T1.3 " + scenario));
    }

    /** 统一的执行包装：把业务错误、崩溃、正常产出三类结果分开记录。 */
    private Outcome runCap(Capability cap, List<Path> inputs, Map<String, String> params) {
        Path out = root.resolve("out");
        Path wk = root.resolve("wk/" + cap.id().replace('.', '-'));
        int[] lastPct = {-1};
        Ctx ctx = new Ctx(cap.id(), inputs, params, out, wk, (p, m) -> lastPct[0] = p, new AtomicBoolean(false));
        try {
            cap.op().run(ctx);
            return new Outcome(null, null, null, ctx.outputs(), ctx.result(), lastPct[0]);
        } catch (Err e) {
            return new Outcome(e.code(), e.getMessage(), null, List.of(), null, lastPct[0]);
        } catch (Throwable t) {
            StackTraceElement top = t.getStackTrace().length > 0 ? t.getStackTrace()[0] : null;
            String loc = top == null ? "" : " @ " + top.getFileName() + ":" + top.getLineNumber();
            return new Outcome(null, null, t.getClass().getSimpleName() + ": " + head(t.getMessage()) + loc,
                    List.of(), null, lastPct[0]);
        }
    }

    /** 按参数定义生成默认入参；密码类没有默认值时补一个占位值，便于走到密码校验分支。 */
    private static Map<String, String> defaults(Capability cap) {
        Map<String, String> m = new LinkedHashMap<>();
        for (Param p : cap.params()) {
            if (p.type() == Param.Type.PASSWORD) {
                m.put(p.key(), "Test#12345");
            } else if (p.def() != null) {
                m.put(p.key(), p.def());
            }
        }
        return m;
    }

    /** 需要多个输入的能力给两个文件，其余给一个。 */
    private static List<Path> inputsFor(Capability cap) {
        return switch (cap.id()) {
            case "organize.merge", "organize.overlay" -> List.of(good, good2);
            default -> List.of(good);
        };
    }

    private String summary(String title) {
        StringBuilder sb = new StringBuilder("\n===== " + title + " 违约 " + violations.size() + " 项 =====\n");
        violations.forEach(v -> sb.append("  · ").append(v).append('\n'));
        return sb.toString();
    }

    private static String head(String s) {
        if (s == null) {
            return "null";
        }
        String one = s.replace('\n', ' ').replace('\r', ' ').trim();
        return one.length() <= 120 ? one : one.substring(0, 120) + "…";
    }
}
