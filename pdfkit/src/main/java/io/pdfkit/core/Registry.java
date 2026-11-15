package io.pdfkit.core;

import io.pdfkit.ops.AnalyzeOps;
import io.pdfkit.ops.AttachOps;
import io.pdfkit.ops.BookmarkOps;
import io.pdfkit.ops.ConvertOps;
import io.pdfkit.ops.EditOps;
import io.pdfkit.ops.EditPlusOps;
import io.pdfkit.ops.FilterOps;
import io.pdfkit.ops.FormOps;
import io.pdfkit.ops.LayoutOps;
import io.pdfkit.ops.OptimizeOps;
import io.pdfkit.ops.OrganizeOps;
import io.pdfkit.ops.RedactOps;
import io.pdfkit.ops.SecureOps;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/** 能力注册表：UI 完全由这里驱动，新增能力只需注册一行。 */
public final class Registry {

    private static final List<Capability> ALL = List.of(
            OrganizeOps.MERGE,
            OrganizeOps.SPLIT,
            OrganizeOps.ROTATE,
            OrganizeOps.CROP,
            OrganizeOps.DELETE_PAGES,
            OrganizeOps.EXTRACT_PAGES,
            OrganizeOps.REORDER,
            OrganizeOps.SCALE,
            OrganizeOps.SPLIT_BY_BOOKMARK,
            LayoutOps.NUP,
            LayoutOps.SPLIT_BY_SIZE,
            LayoutOps.OVERLAY,
            FilterOps.BY_TEXT,
            FilterOps.BY_SIZE,
            FilterOps.BLANK,

            EditOps.PAGE_NUMBER,
            EditOps.WATERMARK,
            EditOps.METADATA,
            EditOps.FLATTEN,
            EditPlusOps.STAMP,
            EditPlusOps.ADD_IMAGE,
            EditPlusOps.INVERT,
            EditPlusOps.SCAN_EFFECT,
            AttachOps.LIST,
            AttachOps.ADD,
            AttachOps.EXTRACT,

            ConvertOps.TO_IMAGE,
            ConvertOps.FROM_IMAGE,
            ConvertOps.TO_TEXT,
            ConvertOps.EXTRACT_IMAGES,

            SecureOps.ENCRYPT,
            SecureOps.DECRYPT,
            SecureOps.SANITIZE,
            SecureOps.VERIFY_SIGNATURE,
            SecureOps.REMOVE_SIGNATURE,
            RedactOps.AUTO,
            RedactOps.AREA,

            FormOps.READ,
            FormOps.FILL,

            AnalyzeOps.INFO,
            AnalyzeOps.FONTS,
            AnalyzeOps.STRUCTURE,

            OptimizeOps.COMPRESS,
            OptimizeOps.REPAIR,

            BookmarkOps.AUTO,
            BookmarkOps.EXPORT,
            BookmarkOps.IMPORT
    );

    private static final List<String> CATEGORY_ORDER = List.of(
            "页面组织", "内容编辑", "格式转换", "书签与目录", "安全与合规", "读取与分析", "优化与修复");

    private Registry() {
    }

    public static List<Capability> all() {
        return ALL;
    }

    public static int count() {
        return ALL.size();
    }

    public static List<String> categories() {
        Set<String> present = new LinkedHashSet<>();
        for (String c : CATEGORY_ORDER) {
            if (ALL.stream().anyMatch(cap -> cap.category().equals(c))) {
                present.add(c);
            }
        }
        for (Capability c : ALL) {
            present.add(c.category());
        }
        return new ArrayList<>(present);
    }

    public static List<Capability> byCategory(String category) {
        return ALL.stream().filter(c -> c.category().equals(category)).toList();
    }

    public static Capability byId(String id) {
        return ALL.stream().filter(c -> c.id().equals(id)).findFirst().orElse(null);
    }
}
