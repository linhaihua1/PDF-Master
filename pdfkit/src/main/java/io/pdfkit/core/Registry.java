package io.pdfkit.core;

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

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 能力注册表：UI 完全由这里驱动，新增能力只需注册一行。
 *
 * <p>全应用只有这一处「能力清单的真相」。左侧功能树、表单、任务、测试用例覆盖度检查
 * 全部从 {@link #all()} 读，没有第二份清单需要同步。
 *
 * <p><b>为什么是静态常量而不是运行时扫描</b>：早期考虑过用注解 + 类路径扫描自动收集，
 * 但那样有三个代价——启动要扫 jar（多几百毫秒）、顺序不可控（界面分组顺序会随
 * 文件系统返回顺序漂移）、出问题时定位困难（哪个类没被扫到？）。手写清单虽然啰嗦，
 * 但一眼能看全、顺序确定、缺失时编译期就发现。
 *
 * <p><b>{@link #ALL} 的顺序就是界面里的显示顺序</b>，同一分类内按这里的先后排列。
 * 想调整界面顺序，改这个列表的顺序即可，不用动任何 UI 代码。
 *
 * <p><b>新增一项能力的完整步骤</b>：
 * <ol>
 *   <li>在 {@code ops} 包对应类里新增一个 {@code public static final Capability XXX}，
 *       写清 id / title / category / desc / params / op。</li>
 *   <li>把常量加进下面的 {@link #ALL}，放在希望它出现的位置。</li>
 *   <li>如果用了新的 category 名，看是否需要加进 {@link #CATEGORY_ORDER} —— 不加也能显示，
 *       只是会排在最后。</li>
 *   <li>跑 {@code FunctionalTest}。它有一个用例拿硬编码的 60 个 id 与注册表比对，
 *       新增能力不补测试会直接失败，这是刻意设的门禁，防止「加了功能没测」。</li>
 * </ol>
 */
public final class Registry {

    /**
     * 全部能力，顺序即界面顺序。
     *
     * <p>下面用空行做的分组只为阅读方便，与 {@link #CATEGORY_ORDER} 无关——
     * 真正的分组依据是每个能力自带的 {@code category} 字段。
     */
    private static final List<Capability> ALL = List.of(
            // 页面组织（18 项）
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
            LayoutPlusOps.POSTER,
            LayoutPlusOps.BOOKLET,
            FilterOps.BY_TEXT,
            FilterOps.BY_SIZE,
            FilterOps.BLANK,
            FilterOps.BY_IMAGE,

            // 内容编辑（15 项）
            EditOps.PAGE_NUMBER,
            EditOps.WATERMARK,
            EditOps.METADATA,
            EditOps.FLATTEN,
            EditPlusOps.STAMP,
            EditPlusOps.ADD_IMAGE,
            EditPlusOps.HEADER_FOOTER,
            EditPlusOps.INVERT,
            EditPlusOps.SCAN_EFFECT,
            AttachOps.LIST,
            AttachOps.ADD,
            AttachOps.EXTRACT,
            AnnotOps.EXPORT,
            AnnotOps.ADD,
            AnnotOps.STRIP,

            // 格式转换（7 项）
            ConvertOps.TO_IMAGE,
            ConvertOps.FROM_IMAGE,
            ConvertOps.TO_TEXT,
            ConvertOps.EXTRACT_IMAGES,
            ConvertPlusOps.TO_TIFF,
            ConvertPlusOps.TO_MARKDOWN,
            ConvertPlusOps.TO_WORD,

            // 安全与合规（8 项）
            SecureOps.ENCRYPT,
            SecureOps.DECRYPT,
            SecureOps.SANITIZE,
            SecureOps.SIGN,
            SecureOps.VERIFY_SIGNATURE,
            SecureOps.REMOVE_SIGNATURE,
            RedactOps.AUTO,
            RedactOps.AREA,

            // 表单处理（3 项）
            FormOps.READ,
            FormOps.FILL,
            FormOps.READONLY,

            // 读取与分析（3 项）
            AnalyzeOps.INFO,
            AnalyzeOps.FONTS,
            AnalyzeOps.STRUCTURE,

            // 优化与修复（3 项）
            OptimizeOps.COMPRESS,
            OptimizeOps.ENHANCE,
            OptimizeOps.REPAIR,

            // 书签与目录（3 项）
            BookmarkOps.AUTO,
            BookmarkOps.EXPORT,
            BookmarkOps.IMPORT
    );

    /**
     * 分类的显示顺序（左侧功能树从上到下）。
     *
     * <p>这个顺序是产品决定，不是技术限制：把「页面组织」放最前因为它是最高频的用法，
     * 「优化与修复」放后面因为用的少。
     *
     * <p>没出现在这里的分类不会消失，只是排到末尾（见 {@link #categories()} 的第二轮循环）。
     * 这样新增分类时不必先来这里登记，不会因为忘了登记就把功能「藏起来」。
     */
    private static final List<String> CATEGORY_ORDER = List.of(
            "页面组织", "内容编辑", "格式转换", "表单处理", "书签与目录", "安全与合规", "读取与分析", "优化与修复");

    /** 工具类，禁止实例化。 */
    private Registry() {
    }

    /** 全部能力（不可变列表，可安全地当只读快照使用）。 */
    public static List<Capability> all() {
        return ALL;
    }

    /** 能力总数。启动日志与界面标题用它，测试也用它断言「60 项都在」。 */
    public static int count() {
        return ALL.size();
    }

    /**
     * 按显示顺序返回实际存在的分类。
     *
     * <p>两轮循环：先按 {@link #CATEGORY_ORDER} 输出已登记且有能力的分类，
     * 再把「没在顺序表里出现过」的分类追加到末尾。
     * 用 {@code LinkedHashSet} 做去重兼保序——同一个分类名被多个能力重复使用时
     * （正常情况下都应该如此）只会出现一次。
     */
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

    /** 取某个分类下的所有能力，顺序沿用 {@link #ALL}。 */
    public static List<Capability> byCategory(String category) {
        return ALL.stream().filter(c -> c.category().equals(category)).toList();
    }

    /**
     * 按 id 查能力，找不到返回 {@code null}。
     *
     * <p>返回 null 而非抛异常，因为调用方多是「可能不存在」的场景——比如从历史配置里
     * 恢复上次使用的能力，那个能力可能已经在新版本里被移除了。调用方需自行判空。
     */
    public static Capability byId(String id) {
        return ALL.stream().filter(c -> c.id().equals(id)).findFirst().orElse(null);
    }
}
