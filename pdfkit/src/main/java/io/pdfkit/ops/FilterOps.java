package io.pdfkit.ops;

import io.pdfkit.core.Capability;
import io.pdfkit.core.Ctx;
import io.pdfkit.core.Err;
import io.pdfkit.core.Param;
import io.pdfkit.util.PdfUtil;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.text.PDFTextStripper;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * 筛选类能力：按文本、按尺寸、删空白页、按有无图片。
 *
 * <p>四项能力走同一个模式：<b>逐页判定 → 收集要保留的页码 → 抽出这些页生成新文件</b>。
 * 抽页统一委托给 {@link OrganizeOps#writeSubset}，本类只负责「怎么判」。
 *
 * <p><b>共同的两处保护</b>：
 * <ul>
 *   <li>一页都没匹配上时报 {@code ERR_NO_MATCH} 并且<b>不生成文件</b>。
 *       不生成比生成一个 0 页的 PDF 好——后者打开就是空白，用户不知道是失败还是文件本身是空的。</li>
 *   <li>「删除空白页」如果算出所有页都是空白，报 {@code ERR_DELETE_ALL} 拒绝执行。
 *       这几乎一定是判定逻辑失效或文件异常，真删了会让用户丢掉整份文档。</li>
 * </ul>
 */
public final class FilterOps {

    /** 工具类，禁止实例化。 */
    private FilterOps() {
    }

    /**
     * 按文本筛选页面。保留（或排除）包含指定关键词/正则的页面。
     *
     * <p>两种匹配方式：<ul>
     *   <li>纯文本模式（默认）—— 用 {@link Pattern#quote} 把用户输入转义成字面量。
     *       这步不能省：用户输入的 {@code .}、{@code *}、{@code (} 在正则里都有特殊含义，
     *       不转义的话搜「图1.2」会变成「图1 任意字符 2」，搜「(试行)」直接语法错误。</li>
     *   <li>正则模式 —— 用户勾选后原样编译，写错时报 {@code ERR_BAD_REGEX} 并回显原因。</li>
     * </ul>
     *
     * <p>单页抽取文本失败时按空串处理、继续扫下一页：某些页的内容流本身有问题
     * （损坏、加密异常），为了一页坏数据中断整批筛选不值得。
     */
    public static final Capability BY_TEXT = new Capability(
            "filter.text", "按文本筛选页面", "页面组织",
            "保留（或排除）包含指定关键词/正则的页面，生成新文件。",
            List.of(
                    Param.text("pattern", "关键词或正则", "", "例如 发票|金额\\d+"),
                    Param.bool("regex", "按正则解释", false, "不勾选则按纯文本包含判断"),
                    Param.bool("exclude", "反向：排除匹配页", false, "勾选后只保留不匹配的页"),
                    Param.bool("caseSensitive", "区分大小写", false, "")
            ),
            ctx -> {
                ctx.input(); // 先确认已选文件：让「没选文件」的提示先于「没填关键词」出现
                String pat = ctx.str("pattern");
                if (pat.isBlank()) {
                    throw new Err("ERR_BAD_PARAM", "请填写要筛选的关键词");
                }
                Pattern p;
                try {
                    p = ctx.bool("regex", false)
                            // 正则模式：0 表示不启用任何标志；CASE_INSENSITIVE 只对拉丁字母有效，中文无所谓
                            ? Pattern.compile(pat, ctx.bool("caseSensitive", false) ? 0 : Pattern.CASE_INSENSITIVE)
                            // 纯文本模式：quote 把整串转义成字面量
                            : Pattern.compile(Pattern.quote(pat),
                            ctx.bool("caseSensitive", false) ? 0 : Pattern.CASE_INSENSITIVE);
                } catch (Exception e) {
                    throw new Err("ERR_BAD_REGEX", "正则无效：" + e.getMessage(), e);
                }
                boolean exclude = ctx.bool("exclude", false);
                try (PDDocument doc = PdfUtil.load(ctx.input(), ctx.str("password"))) {
                    PDFTextStripper stripper = new PDFTextStripper();
                    int total = doc.getNumberOfPages();
                    List<Integer> keep = new ArrayList<>();
                    List<Integer> hitPages = new ArrayList<>();
                    for (int i = 1; i <= total; i++) {
                        ctx.report(5 + i * 85 / total, "扫描第 " + i + "/" + total + " 页");
                        // 逐页设置范围而不是一次抽全文：需要知道命中页的页码，
                        // 而且一次抽取整份大文档会占用大量内存
                        stripper.setStartPage(i);
                        stripper.setEndPage(i);
                        String text;
                        try {
                            text = stripper.getText(doc);
                        } catch (Exception e) {
                            text = "";
                        }
                        boolean hit = p.matcher(text).find();
                        if (hit) {
                            hitPages.add(i);
                        }
                        // 异或：正常模式保留命中页，排除模式保留未命中页
                        if (hit != exclude) {
                            keep.add(i);
                        }
                    }
                    if (keep.isEmpty()) {
                        throw new Err("ERR_NO_MATCH",
                                "没有页面符合条件（匹配 " + hitPages.size() + " 页 / 共 " + total + " 页），未生成文件");
                    }
                    Path out = ctx.outDerived("_筛选", ".pdf");
                    OrganizeOps.writeSubset(doc, PdfUtil.toIndices(keep), out);
                    ctx.addOutput(out);
                    // 把命中页列出来，用户能核对判定是否符合预期，不必打开文件自己找
                    ctx.setResult("匹配页：" + (hitPages.isEmpty() ? "无" : hitPages.toString())
                            + "\n保留 " + keep.size() + " 页，输出 " + out.getFileName() + "。");
                    ctx.report(100, "保留 " + keep.size() + " 页");
                }
            });

    /**
     * 按页面尺寸筛选：只保留竖版/横版页面，或指定纸张规格的页面。
     *
     * <p>判定用 {@code CropBox} 而非 {@code MediaBox}：CropBox 是「实际可见区域」，
     * 有些 PDF 的 MediaBox 比实际内容大一圈（印刷留白），用 MediaBox 判断方向会误判。
     *
     * <p>A4/Letter 判定同时接受横竖两种朝向：一份横向排版的 A4 文档，
     * 用户选「A4」是想保留它，不会因为方向而筛掉。
     */
    public static final Capability BY_SIZE = new Capability(
            "filter.size", "按尺寸筛选页面", "页面组织",
            "只保留指定方向的页面（竖版/横版）或指定纸张尺寸的页面。",
            List.of(Param.choice("mode", "筛选条件", "竖版", "竖版", "横版", "A4", "Letter")),
            ctx -> {
                String mode = ctx.str("mode", "竖版");
                try (PDDocument doc = PdfUtil.load(ctx.input(), ctx.str("password"))) {
                    int total = doc.getNumberOfPages();
                    List<Integer> keep = new ArrayList<>();
                    for (int i = 1; i <= total; i++) {
                        PDPage page = doc.getPage(i - 1);
                        PDRectangle b = page.getCropBox();
                        boolean ok;
                        switch (mode) {
                            case "横版" -> ok = b.getWidth() > b.getHeight();
                            // 构造一个转 90° 的矩形再比，这样横版 A4 也能命中
                            case "A4" -> ok = near(b, PDRectangle.A4) || near(b, new PDRectangle(
                                    PDRectangle.A4.getHeight(), PDRectangle.A4.getWidth()));
                            case "Letter" -> ok = near(b, PDRectangle.LETTER) || near(b, new PDRectangle(
                                    PDRectangle.LETTER.getHeight(), PDRectangle.LETTER.getWidth()));
                            // 正方形页面按竖版处理（高 >= 宽）
                            default -> ok = b.getHeight() >= b.getWidth();
                        }
                        if (ok) {
                            keep.add(i);
                        }
                    }
                    if (keep.isEmpty()) {
                        throw new Err("ERR_NO_MATCH", "没有符合「" + mode + "」的页面（共 " + total + " 页）");
                    }
                    Path out = ctx.outDerived("_" + mode, ".pdf");
                    OrganizeOps.writeSubset(doc, PdfUtil.toIndices(keep), out);
                    ctx.addOutput(out);
                    ctx.report(100, "保留 " + keep.size() + " 页");
                }
            });

    /**
     * 删除空白页。
     *
     * <p>「空白」的判定是<b>文字字符数少于阈值 且 没有图片</b>。两个条件都要满足才算空白，
     * 因为扫描件是「没有文字层但有大图」——只看文字会把所有扫描页当空白删掉。
     * 所以 {@code checkImages} 默认开启，且帮助文本里写明扫描件务必勾选。
     *
     * <p>没有空白页时<b>不生成新文件</b>，直接返回提示。这种情况本来就无需改动，
     * 凭空造一个内容相同的副本只会让输出目录变乱。
     */
    public static final Capability BLANK = new Capability(
            "filter.blank", "删除空白页", "页面组织",
            "移除没有文字内容的空白页，扫描件产生的空白页也能识别。",
            List.of(
                    Param.num("minChars", "最少字符数", "3", "低于该值视为空白"),
                    Param.bool("checkImages", "把含图片的页也算非空", true, "扫描件务必勾选")
            ),
            ctx -> {
                int minChars = ctx.intVal("minChars", 3, 0, 200);
                boolean checkImages = ctx.bool("checkImages", true);
                try (PDDocument doc = PdfUtil.load(ctx.input(), ctx.str("password"))) {
                    PDFTextStripper stripper = new PDFTextStripper();
                    int total = doc.getNumberOfPages();
                    List<Integer> keep = new ArrayList<>();
                    List<Integer> blanks = new ArrayList<>();
                    for (int i = 1; i <= total; i++) {
                        ctx.report(5 + i * 85 / total, "检查第 " + i + "/" + total + " 页");
                        stripper.setStartPage(i);
                        stripper.setEndPage(i);
                        String text;
                        try {
                            text = stripper.getText(doc).trim();
                        } catch (Exception e) {
                            text = "";
                        }
                        boolean hasImage = checkImages && hasImage(doc.getPage(i - 1));
                        // 文字不足 且 无图 → 空白。有图的一律保留，哪怕一个字都没有
                        boolean blank = text.length() < minChars && !hasImage;
                        if (blank) {
                            blanks.add(i);
                        } else {
                            keep.add(i);
                        }
                    }
                    if (blanks.isEmpty()) {
                        ctx.setResult("没有发现空白页，共 " + total + " 页，文件未改动。");
                        ctx.report(100, "无空白页");
                        return;
                    }
                    if (keep.isEmpty()) {
                        // 全被判成空白基本意味着判定失效（或文件确实全空）。
                        // 真删掉用户就什么都没了，所以宁可报错让他自己检查。
                        throw new Err("ERR_DELETE_ALL", "所有页都被判定为空白，为安全起见不生成文件");
                    }
                    Path out = ctx.outDerived("_去空白", ".pdf");
                    OrganizeOps.writeSubset(doc, PdfUtil.toIndices(keep), out);
                    ctx.addOutput(out);
                    ctx.setResult("删除空白页 " + blanks.size() + " 页：" + blanks + "\n保留 " + keep.size() + " 页。");
                    ctx.report(100, "删除 " + blanks.size() + " 个空白页");
                }
            });

    /**
     * 按有无图片筛选，挑出含图片的页（插图页、扫描页）或纯文字页单独导出。
     *
     * <p>典型用途：把合同正文和扫描的附件页分开、从图文混排的册子里单独取出图片页。
     */
    public static final Capability BY_IMAGE = new Capability(
            "filter.image", "按有无图片筛选", "页面组织",
            "挑出含图片的页（如插图、扫描页）或只有文字的页，单独导出成一个文件。",
            List.of(Param.choice("mode", "保留", "含图片的页", "含图片的页", "不含图片的页")),
            ctx -> {
                boolean wantImage = !"不含图片的页".equals(ctx.str("mode", "含图片的页"));
                try (PDDocument doc = PdfUtil.load(ctx.input(), ctx.str("password"))) {
                    int total = doc.getNumberOfPages();
                    List<Integer> keep = new ArrayList<>();
                    for (int i = 1; i <= total; i++) {
                        // 这里判定很轻（只看资源字典，不解析图像数据），但页数可能很多，
                        // 所以除了 report 再显式 checkCancel，提高取消响应速度
                        ctx.checkCancel();
                        ctx.report(5 + i * 85 / total, "检查第 " + i + "/" + total + " 页");
                        if (hasImage(doc.getPage(i - 1)) == wantImage) {
                            keep.add(i);
                        }
                    }
                    if (keep.isEmpty()) {
                        throw new Err("ERR_NO_MATCH", "没有符合「" + ctx.str("mode", "含图片的页")
                                + "」的页面（共 " + total + " 页）");
                    }
                    Path out = ctx.outDerived(wantImage ? "_含图页" : "_纯文字页", ".pdf");
                    OrganizeOps.writeSubset(doc, PdfUtil.toIndices(keep), out);
                    ctx.addOutput(out);
                    ctx.setResult("保留 " + keep.size() + " 页：" + keep);
                    ctx.report(100, "保留 " + keep.size() + " 页");
                }
            });

    /**
     * 比较两个矩形尺寸是否近似相等。
     *
     * <p>容差 3pt（约 1.06 mm）是必要的：A4 标准尺寸是 595.276 × 841.89 pt，
     * 但实际 PDF 里常见 595 × 842、596 × 843 这类因软件换算取整而产生的偏差。
     * 精确比较会把本该命中的 A4 页面判成「不是 A4」。
     */
    private static boolean near(PDRectangle a, PDRectangle b) {
        return Math.abs(a.getWidth() - b.getWidth()) < 3 && Math.abs(a.getHeight() - b.getHeight()) < 3;
    }

    /**
     * 判断页面是否含图片。
     *
     * <p>只看页面的 XObject 资源里有没有 {@code PDImageXObject}。这是「有没有图」的
     * 廉价判定——不解析图像内容、不解码像素，对 500 页文档也只是遍历资源字典。
     *
     * <p>覆盖不到的两种情况：
     * <ul>
     *   <li>内容流里直接内联的图片（{@code BI ... EI} 内联图像），不进资源字典。</li>
     *   <li>图片画在 Form XObject 内部，本方法不递归进去看。</li>
     * </ul>
     * 这两种在常见 PDF 里占比很低（多数生成器都把图放成独立的 Image XObject），
     * 为实现完整判定去解析内容流代价太大，不值得。
     *
     * <p>资源解析异常时按「无图」处理：宁可把图页误判成文字页，也不要因为一页坏资源
     * 让整个筛选失败。
     */
    private static boolean hasImage(PDPage page) {
        try {
            var res = page.getResources();
            if (res == null) {
                return false;
            }
            for (var name : res.getXObjectNames()) {
                if (res.getXObject(name) instanceof org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject) {
                    return true;
                }
            }
        } catch (Exception ignored) {
            // 资源解析失败按无图处理
        }
        return false;
    }
}
