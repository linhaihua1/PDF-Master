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

/** PDF 通用操作：加载、原子保存、页码范围解析。 */
public final class PdfUtil {

    private PdfUtil() {
    }

    public static PDDocument load(Path p) {
        return load(p, null);
    }

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

    /** 原子保存：先写同目录 .part，再 move 覆盖，避免中途失败留下半个文件。 */
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
                Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException e) {
            try {
                Files.deleteIfExists(tmp);
            } catch (IOException ignored) {
                // 清理失败不影响主错误
            }
            String m = e.getMessage() == null ? "" : e.getMessage();
            if (m.contains("space") || m.contains("空间")) {
                throw new Err("ERR_NO_SPACE", "磁盘空间不足，无法写入：" + target, e);
            }
            throw new Err("ERR_WRITE", "写入失败：" + target + "（" + m + "）", e);
        }
    }

    /**
     * 解析页码范围，支持 "1-3,5,8-" 形式。
     *
     * @return 1 基页码列表，按用户输入顺序去重
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

    private static int parseInt(String s, String origin) {
        try {
            return Integer.parseInt(s.trim());
        } catch (NumberFormatException e) {
            throw Err.of("ERR_BAD_PAGE_SPEC", "页码必须是数字：%s", origin);
        }
    }

    /** 1 基页码 → 0 基索引 */
    public static int[] toIndices(List<Integer> pages) {
        int[] idx = new int[pages.size()];
        for (int i = 0; i < pages.size(); i++) {
            idx[i] = pages.get(i) - 1;
        }
        return idx;
    }

    public static String baseName(Path p) {
        String n = p.getFileName().toString();
        int dot = n.lastIndexOf('.');
        return dot > 0 ? n.substring(0, dot) : n;
    }

    public static long sizeMb(Path p) {
        try {
            return Files.size(p) / 1024 / 1024;
        } catch (IOException e) {
            return -1;
        }
    }
}
