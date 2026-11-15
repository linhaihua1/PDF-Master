package io.pdfkit.util;

import io.pdfkit.core.Err;
import org.apache.fontbox.ttf.TrueTypeCollection;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.font.PDFont;
import org.apache.pdfbox.pdmodel.font.PDType0Font;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;

/**
 * 中文字体解析。顺序：内置资源 → 系统字体 → 标准字体降级。
 * 内置字体随安装包分发，用户无需配置任何环境。
 *
 * <p><b>为什么要有三级回退</b>：
 * <ol>
 *   <li><b>内置字体</b> —— 正常路径。9.75 MB 的思源黑体子集随 jar 分发，
 *       覆盖 GB2312 全部 7445 个码位。目标机没装任何中文字体也能正确显示中文，
 *       这是「零依赖」承诺的兑现方式。</li>
 *   <li><b>系统 TTF/TTC</b> —— 内置字体出问题时的兜底（资源缺失、字体文件损坏、
 *       被安全软件拦截）。只在用户机器本来就有中文字体时有效。</li>
 *   <li><b>抛错</b> —— 三级都失败时明确报 {@code ERR_NO_CJK_FONT}，而<b>不是</b>静默
 *       换成不支持中文的标准字体。静默降级会让用户拿到一份全是方框的 PDF，
 *       还以为程序正常完成了——这比直接报错糟糕得多。</li>
 * </ol>
 *
 * <p>所有字体都用子集嵌入（{@code PDType0Font.load(doc, ..., true)}）：只把实际用到的
 * 字形写进 PDF，每个 PDF 平均只增加约 40 KB，而不是整个 9.75 MB。
 */
public final class Fonts {

    /**
     * 内置思源黑体 Regular（OFL 许可），随安装包分发，目标机无需装任何字体。
     *
     * <p>三条硬要求，改字体前务必先跑 RoundTrip 校验：
     * <ol>
     *   <li>必须是 glyf 轮廓的 TrueType。PDFBox 的 PDType0Font 不支持 CFF 轮廓的 OTF，
     *       加载会直接失败并回退到系统字体。</li>
     *   <li>cmap 里不能有部首区（U+2E80-2FDF）/ 兼容汉字区（U+F900-FAFF）的重复映射。
     *       Noto 原始可变字体把「页」的部首形码位也映射到同一个字形，PDFBox 生成
     *       ToUnicode 时按 GID 反查会挑到部首码位，结果是字形正确但复制、搜索、
     *       文本提取全部错误（提取出 U+2EDA 而不是 U+9875）。</li>
     *   <li>不要用 hasGlyph() 判断本字体是否缺字。PDFBox 对 embedSubset=true 加载的
     *       PDType0Font，hasGlyph(int) 把入参当 CID/GID 而非 Unicode 码位，只有码位
     *       数值小于字形总数时才返回 true，结论完全不可信。判定缺字只能用「写入后
     *       用 PDFTextStripper 提取比对」的方式。</li>
     * </ol>
     * 当前文件由 NotoSansSC-VF 实例化为 wght=400 并定向子集而成，
     * 构建步骤见 docs/build.md 第 9 节「重建内置字体」，脚本为 scripts/build-font.py，
     * 硬约束摘要见 README 的「设计要点 → 中文不出方框」。
     */
    private static final String BUNDLED = "/fonts/NotoSansSC-Regular.ttf";

    /**
     * 系统可用的中文 TTF（ttc 不在此列，PDFBox 直接 load 不了 ttc 单字体）
     *
     * <p>顺序即优先级。微软雅黑排第一是因为它的字形覆盖最全、屏幕显示效果最好
     * （无衬线、字重适中）；黑体次之；后面的楷体、仿宋属于装饰性字体，
     * 只在用户删了前面几个时才用到，显示效果不如前两者适合正文。
     */
    private static final List<String> SYSTEM_TTF = List.of(
            "C:/Windows/Fonts/msyh.ttf",
            "C:/Windows/Fonts/simhei.ttf",
            "C:/Windows/Fonts/Deng.ttf",
            "C:/Windows/Fonts/simkai.ttf",
            "C:/Windows/Fonts/simfang.ttf"
    );

    /**
     * 这些 ttc 里第 0 个字体通常是可用中文，作为最后的中文兜底
     *
     * <p>ttc 是「字体集合」，一个文件里打包多个字面（常规/粗体/细体）。
     * PDFBox 不能直接把 ttc 文件交给 {@code PDType0Font.load}，必须先开
     * {@link TrueTypeCollection} 再逐字面尝试。这里取第一个能加载成功的字面，
     * 不保证是最合适的那个——但在兜底场景下，能显示中文就算达标。
     */
    private static final List<String> SYSTEM_TTC = List.of(
            "C:/Windows/Fonts/msyh.ttc",
            "C:/Windows/Fonts/simsun.ttc"
    );

    /** 工具类，禁止实例化。 */
    private Fonts() {
    }

    /**
     * 加载可显示中文的字体（子集嵌入，体积可控）。
     * 找不到任何中文字体时抛出明确错误，而不是静默输出方框。
     *
     * <p>每次调用都会重新加载并嵌入一份字体：同一个 {@code PDDocument} 里调用多次会
     * 得到多个独立的字体对象，PDF 体积会重复计算。需要多处写中文时，
     * 调用一次把返回值存起来复用。
     *
     * @param doc 目标文档，字体被嵌入到它的资源里
     * @return 可写入中文的字体对象
     * @throws Err {@code ERR_NO_CJK_FONT} 内置资源与系统字体全部不可用
     */
    public static PDFont chinese(PDDocument doc) {
        // 1) 内置字体
        try (InputStream in = Fonts.class.getResourceAsStream(BUNDLED)) {
            if (in != null) {
                return PDType0Font.load(doc, in, true);
            }
            Log.warn("内置字体资源缺失：%s", BUNDLED);
        } catch (Exception e) {
            // 走到这里说明字体文件本身有问题（轮廓类型不对、文件损坏）。
            // 继续尝试系统字体，但要留日志——这是「零依赖」承诺失效的信号。
            Log.warn("内置字体加载失败：%s", e.getMessage());
        }

        // 2) 系统 TTF
        for (String p : SYSTEM_TTF) {
            Path f = Paths.get(p);
            if (Files.isReadable(f)) {
                try {
                    return PDType0Font.load(doc, f.toFile());
                } catch (Exception e) {
                    Log.warn("字体 %s 加载失败：%s", p, e.getMessage());
                }
            }
        }

        // 3) 系统 TTC 取第 0 个字体
        for (String p : SYSTEM_TTC) {
            Path f = Paths.get(p);
            if (!Files.isReadable(f)) {
                continue;
            }
            try (TrueTypeCollection ttc = new TrueTypeCollection(f.toFile())) {
                // 用单元素数组接结果：lambda 里要给外部变量赋值，Java 要求 effectively final
                final PDType0Font[] holder = new PDType0Font[1];
                ttc.processAllFonts(ttf -> {
                    if (holder[0] == null) {
                        try {
                            holder[0] = PDType0Font.load(doc, ttf, true);
                        } catch (Exception ignored) {
                            // 该字面不可用，继续下一个
                        }
                    }
                });
                if (holder[0] != null) {
                    return holder[0];
                }
            } catch (Exception e) {
                Log.warn("TTC %s 解析失败：%s", p, e.getMessage());
            }
        }

        throw new Err("ERR_NO_CJK_FONT",
                "系统未找到可用中文字体，无法写入中文。请安装任意中文字体（如微软雅黑），或把字体放到程序 fonts 目录。");
    }

    /**
     * 纯英文场景用标准字体，零开销。
     *
     * <p><b>警告：这个字体不含任何中文字形。</b>用它写中文会抛
     * {@code IllegalArgumentException: U+XXXX ('.notdef') is not available in the font Helvetica}。
     * 只有确认待写文本全为 ASCII 时才能用——比如页码数字、英文水印。
     *
     * <p>判断依据不能靠「看起来是英文」，要用 {@link #hasChinese(String)} 检查一遍。
     * 踩过一次：示例文本里混了中文，直接调 {@code latin()} 导致整条能力崩溃。
     *
     * <p>标准 14 字体不需要嵌入，所以体积为零——这是它唯一的优势。
     */
    public static PDFont latin() {
        return new PDType1Font(Standard14Fonts.FontName.HELVETICA);
    }

    /**
     * 判断字符串里是否含非 ASCII 字符。
     *
     * <p>阈值取 {@code 0x2E80} 而不是 {@code 0x7F}：{@code 0x2E80} 是 CJK 部首补充区的起点，
     * 往上都是「需要中文字体才能显示」的字符（汉字、假名、全角标点、CJK 符号）。
     * 用这个阈值可以一并覆盖中文标点（U+3000 起）和全角字符（U+FF00 起）——
     * 这些都是 Helvetica 渲染不出来的，必须走中文字体。
     *
     * <p>副作用：{@code 0x2E80} 以下的部分字符（如希腊字母、西里尔字母、带重音的拉丁字母）
     * 会被判成「非中文」，但 Helvetica 其实也不支持它们。目前业务上不会遇到，
     * 真需要时应该改用「尝试渲染、失败则回退」的策略，而不是抬阈值。
     */
    public static boolean hasChinese(String s) {
        if (s == null) {
            return false;
        }
        for (int i = 0; i < s.length(); i++) {
            if (s.charAt(i) > 0x2E80) {
                return true;
            }
        }
        return false;
    }
}
