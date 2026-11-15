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
 */
public final class Fonts {

    private static final String BUNDLED = "/fonts/NotoSansSC-Regular.otf";

    /** 系统可用的中文 TTF（ttc 不在此列，PDFBox 直接 load 不了 ttc 单字体） */
    private static final List<String> SYSTEM_TTF = List.of(
            "C:/Windows/Fonts/msyh.ttf",
            "C:/Windows/Fonts/simhei.ttf",
            "C:/Windows/Fonts/Deng.ttf",
            "C:/Windows/Fonts/simkai.ttf",
            "C:/Windows/Fonts/simfang.ttf"
    );

    /** 这些 ttc 里第 0 个字体通常是可用中文，作为最后的中文兜底 */
    private static final List<String> SYSTEM_TTC = List.of(
            "C:/Windows/Fonts/msyh.ttc",
            "C:/Windows/Fonts/simsun.ttc"
    );

    private Fonts() {
    }

    /**
     * 加载可显示中文的字体（子集嵌入，体积可控）。
     * 找不到任何中文字体时抛出明确错误，而不是静默输出方框。
     */
    public static PDFont chinese(PDDocument doc) {
        // 1) 内置字体
        try (InputStream in = Fonts.class.getResourceAsStream(BUNDLED)) {
            if (in != null) {
                return PDType0Font.load(doc, in, true);
            }
        } catch (Exception e) {
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

    /** 纯英文场景用标准字体，零开销。 */
    public static PDFont latin() {
        return new PDType1Font(Standard14Fonts.FontName.HELVETICA);
    }

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
