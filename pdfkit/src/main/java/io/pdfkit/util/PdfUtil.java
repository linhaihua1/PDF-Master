package io.pdfkit.util;

import io.pdfkit.core.Err;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.encryption.InvalidPasswordException;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;

/**
 * PDF 通用操作：加载、原子保存、页码范围解析。
 *
 * <p>三个能力类里重复率最高的操作都在这里，改一处全项目生效。尤其是 {@link #save}——
 * 它决定了「写文件失败时会不会毁掉用户的原件」，所有能力都必须走它，不要自己 {@code doc.save()}。
 */
public final class PdfUtil {

    /** 工具类，禁止实例化。 */
    private PdfUtil() {
    }

    /** 加载不加密的 PDF。 */
    public static PDDocument load(Path p) {
        return load(p, null);
    }

    /**
     * 加载 PDF，按「不存在 → 是目录 → 不可读 → 加密 → 损坏」的顺序逐级校验。
     *
     * <p><b>为什么每步给不同错误码</b>：这五种情况的处理方式完全不同——找不到文件要检查
     * 路径，选成文件夹要重新选，不可读要关掉占用它的程序，加密要输密码，损坏只能换文件。
     * 都报「无法打开 PDF」的话，用户不知道该做什么。
     *
     * <p><b>加密检测为什么要两条路</b>：正常情况 PDFBox 抛 {@link InvalidPasswordException}，
     * 但部分加密文件（尤其用了非标准加密字典的）会以普通 {@code IOException} 抛出，
     * 消息里带 "password" 字样。所以 catch IOException 后还要看消息内容判断一次，
     * 否则加密文件会被误报成「文件损坏」——用户拿着好好的文件被告知损坏，会很困惑。
     *
     * @param p        文件路径
     * @param password 打开密码；null 或空串表示无密码
     * @return 已打开的文档，调用方负责 close
     * @throws Err ERR_NO_FILE / ERR_IS_DIR / ERR_NO_PERM / ERR_ENCRYPTED / ERR_BAD_PDF
     */
    public static PDDocument load(Path p, String password) {
        if (!Files.exists(p)) {
            throw new Err("ERR_NO_FILE", "文件不存在：" + p);
        }
        if (Files.isDirectory(p)) {
            throw new Err("ERR_IS_DIR", "这是文件夹，不是文件：" + p);
        }
        if (Files.isReadable(p) == false) {
            throw new Err("ERR_NO_PERM", "文件不可读（可能被占用或权限不足）：" + p);
        }
        try {
            if (password == null || password.isEmpty()) {
                // 传空密码给 PDFBox 时它对无密码文件正常打开、对有密码文件抛异常，
                // 但这是「试错」而非「判断」，所以上面还要用消息内容做二次确认。
                return Loader.loadPDF(p.toFile());
            }
            return Loader.loadPDF(p.toFile(), password);
        } catch (InvalidPasswordException e) {
            throw new Err("ERR_ENCRYPTED", "PDF 已加密，请提供打开密码：" + p.getFileName(), e);
        } catch (IOException e) {
            String m = e.getMessage() == null ? "" : e.getMessage().toLowerCase();
            if (m.contains("password")) {
                throw new Err("ERR_ENCRYPTED", "PDF 已加密，请提供打开密码：" + p.getFileName(), e);
            }
            throw new Err("ERR_BAD_PDF", "无法解析 PDF（文件损坏或不是 PDF）：" + p.getFileName(), e);
        }
    }

    /**
     * 原子保存：先写同目录 {@code .part} 临时文件，再 move 覆盖目标。
     *
     * <p><b>为什么不直接 {@code doc.save(target)}</b>：直接写目标文件时，如果磁盘满、
     * 进程被杀、或者用户强退，目标位置会留下一个「半个 PDF」——比原文件还糟，
     * 因为它看起来是个正常文件，打开才发现内容残缺。走临时文件 + 改名后，
     * 要么完整成功，要么目标文件保持原样，不会出现中间态。
     *
     * <p><b>为什么临时文件放同目录</b>：{@code ATOMIC_MOVE} 只在同一文件系统内保证原子性。
     * 放系统临时目录（可能在另一个盘）时 move 会退化成「复制 + 删除」，失去原子性。
     *
     * <p><b>ATOMIC_MOVE 失败为什么要降级</b>：部分网络盘、FAT32 U 盘不支持原子改名。
     * 这时退成普通 move——虽然失去原子性保证，但至少能用，比直接失败强。
     *
     * <p>磁盘空间不足单独给 {@code ERR_NO_SPACE}：这个错误用户能自己解决（清理磁盘），
     * 混在「写入失败」里就没法给出针对性建议。
     *
     * @param doc    已打开的文档
     * @param target 目标路径，父目录不存在会自动创建
     * @throws Err ERR_NO_PERM / ERR_NO_SPACE / ERR_WRITE
     */
    public static void save(PDDocument doc, Path target) {
        try {
            Files.createDirectories(target.getParent());
        } catch (IOException e) {
            throw new Err("ERR_NO_PERM", "无法创建输出目录：" + target.getParent(), e);
        }
        Path tmp = target.resolveSibling(target.getFileName() + ".part");
        try {
            try (OutputStream os = Files.newOutputStream(tmp)) {
                doc.save(os);
            }
            try {
                Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (IOException atomicFail) {
                // 文件系统不支持原子改名（网络盘、FAT32），降级为普通 move
                Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException e) {
            try {
                Files.deleteIfExists(tmp);
            } catch (IOException ignored) {
                // 清理失败不影响主错误：真正的问题在下面抛出
            }
            String m = e.getMessage() == null ? "" : e.getMessage();
            if (m.contains("space") || m.contains("空间")) {
                throw new Err("ERR_NO_SPACE", "磁盘空间不足，无法写入：" + target, e);
            }
            throw new Err("ERR_WRITE", "写入失败：" + target + "（" + m + "）", e);
        }
    }

    /**
     * 解析页码范围，支持 {@code "1-3,5,8-"} 形式。
     *
     * <p>语法：
     * <ul>
     *   <li>{@code 3} —— 单页。</li>
     *   <li>{@code 1-3} —— 闭区间，含首含尾。</li>
     *   <li>{@code 8-} —— 从第 8 页到末页。终点留空即「到末页」。</li>
     *   <li>{@code -5} —— 从第 1 页到第 5 页。起点留空即「从首页」。</li>
     * </ul>
     *
     * <p>分隔符接受「半角逗号、全角逗号、半角分号、全角分号、空白」五种：
     * 中文用户经常打出全角逗号，为这个报错没有意义。分号也接受是因为有些人习惯
     * 用分号分隔（法语、西班牙语习惯）。
     *
     * <p><b>传空串表示「全部页」</b>：这是刻意的默认——大多数能力在用户没填页码时
     * 就应当处理整份文档，而不是报错要求必填。
     *
     * <p>结果按用户输入顺序去重，不自动排序：用户写 {@code 3,1,2} 就是想按这个顺序处理
     * （重排页面时会用到）。想排序的能力自己排。
     *
     * @param spec       页码表达式，空白表示全部
     * @param totalPages 文档总页数，用于校验上界与展开开区间
     * @return 1 基页码列表，按输入顺序去重
     * @throws Err ERR_BAD_PAGE_SPEC 格式错误；ERR_PAGE_RANGE 越界
     */
    public static List<Integer> parsePageSpec(String spec, int totalPages) {
        if (spec == null || spec.isBlank()) {
            List<Integer> all = new ArrayList<>();
            for (int i = 1; i <= totalPages; i++) {
                all.add(i);
            }
            return all;
        }
        List<Integer> result = new ArrayList<>();
        for (String part : spec.split("[,，;；\\s]+")) {
            if (part.isBlank()) {
                continue;
            }
            if (part.contains("-")) {
                // split 带 -1 参数：保留尾部空串，否则 "8-" 会被拆成只有 1 个元素
                String[] se = part.split("-", -1);
                if (se.length != 2) {
                    throw Err.of("ERR_BAD_PAGE_SPEC", "页码区间格式错误：%s（示例：1-3,5,8-）", part);
                }
                int from = se[0].isBlank() ? 1 : parseInt(se[0], part);
                int to = se[1].isBlank() ? totalPages : parseInt(se[1], part);
                if (from < 1 || to > totalPages) {
                    throw Err.of("ERR_PAGE_RANGE", "页码 %s 超出范围（共 %d 页）", part, totalPages);
                }
                if (from > to) {
                    throw Err.of("ERR_PAGE_RANGE", "页码区间起点大于终点：%s", part);
                }
                for (int i = from; i <= to; i++) {
                    if (!result.contains(i)) {
                        result.add(i);
                    }
                }
            } else {
                int p = parseInt(part, part);
                if (p < 1 || p > totalPages) {
                    throw Err.of("ERR_PAGE_RANGE", "第 %d 页超出范围（共 %d 页）", p, totalPages);
                }
                if (!result.contains(p)) {
                    result.add(p);
                }
            }
        }
        if (result.isEmpty()) {
            throw new Err("ERR_BAD_PAGE_SPEC", "页码解析结果为空：" + spec);
        }
        return result;
    }

    /** 解析单个整数，失败时报出原始的整段表达式而不是这一小段，方便用户定位。 */
    private static int parseInt(String s, String origin) {
        try {
            return Integer.parseInt(s.trim());
        } catch (NumberFormatException e) {
            throw Err.of("ERR_BAD_PAGE_SPEC", "页码必须是数字：%s", origin);
        }
    }

    /**
     * 1 基页码 → 0 基索引。
     *
     * <p>用户看到的页码是 1 基，PDFBox 的 {@code getPage(i)} 是 0 基，
     * 这里做唯一一次转换。别在能力实现里再减一，那会变成「各减一次」的低级错误。
     */
    public static int[] toIndices(List<Integer> pages) {
        int[] idx = new int[pages.size()];
        for (int i = 0; i < pages.size(); i++) {
            idx[i] = pages.get(i) - 1;
        }
        return idx;
    }

    /** 取不含扩展名的文件名。{@code a.pdf} → {@code a}。 */
    public static String baseName(Path p) {
        String n = p.getFileName().toString();
        int dot = n.lastIndexOf('.');
        return dot > 0 ? n.substring(0, dot) : n;
    }

    /**
     * 文件大小（MB，向下取整）。
     *
     * <p>读不到时返回 -1 而不是抛异常：调用方多用于显示或阈值判断，
     * 拿不到大小不该让整个能力失败。
     */
    public static long sizeMb(Path p) {
        try {
            return Files.size(p) / 1024 / 1024;
        } catch (IOException e) {
            return -1;
        }
    }
}
