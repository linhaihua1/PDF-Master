package io.pdfkit.core;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.BiConsumer;

/**
 * 一次任务执行的上下文：输入、参数、输出目录、进度、取消令牌。
 *
 * <p>由 {@link JobQueue} 在<b>后台工作线程</b>里创建，交给 {@link Op#run(Ctx)} 消费，
 * 任务结束后丢弃。因此它不是共享状态：同一时刻只有一个线程在用，字段不需要加锁。
 * 唯一的例外是 {@link #outputs}——它用写时复制列表，因为界面线程可能在任务执行途中
 * 读它刷新「已生成文件」列表。
 *
 * <p><b>三个目录别搞混</b>：
 * <ul>
 *   <li>{@link #outDir()} —— 最终产物落地的地方。用户可见。</li>
 *   <li>{@link #workDir()} —— 中间文件、拆分片段的暂存区，任务结束由队列清理。
 *       需要「先切再拼」「逐页处理后合并」的能力必须往这里写，别污染输出目录。</li>
 *   <li>{@link #inputs()} 所在的目录 —— 只读。绝不往源文件旁边写东西，
 *       用户的文件夹被塞进一堆中间产物是很糟糕的体验。</li>
 * </ul>
 *
 * <p><b>两个出口别搞混</b>：产出文件走 {@link #addOutput}，纯文字结论走 {@link #setResult}。
 * 分析类能力（文档信息、字体体检、结构探查）只需后者，不必为了「有产物」而硬造文件。
 */
public final class Ctx {

    /** 能力 id，如 {@code organize.merge}。出错时用于定位是哪个能力炸的。 */
    private final String capId;
    /** 用户选中的文件，顺序即界面里的显示顺序。不可变。 */
    private final List<Path> inputs;
    /** 用户填的参数，key 对应 {@link Param#key()}。用 LinkedHashMap 保持声明顺序，便于日志阅读。 */
    private final Map<String, String> params;
    /** 产物输出目录，用户选定。 */
    private final Path outDir;
    /** 临时工作目录，任务结束清理。 */
    private final Path workDir;
    /** 进度回调，参数是百分比与当前动作描述。可能为 null（无界面场景，如测试）。 */
    private final BiConsumer<Integer, String> progress;
    /** 取消令牌，由队列持有并置位。可能为 null。 */
    private final java.util.concurrent.atomic.AtomicBoolean canceled;
    /**
     * 已登记产物。用写时复制列表，因为界面线程会并发读它。
     * 元素数一般很少（个位数），写时复制的开销可以忽略。
     */
    private final List<Path> outputs = new CopyOnWriteArrayList<>();
    /** 分析类能力的文字结论，可能为 null。 */
    private volatile String result;

    /**
     * @param capId    能力 id
     * @param inputs   输入文件，不可为空列表（空列表本身合法，取用时才报错）
     * @param params   参数表，null 会 NPE，调用方需保证非 null
     * @param outDir   输出目录
     * @param workDir  临时目录
     * @param progress 进度回调，可为 null
     * @param canceled 取消令牌，可为 null
     */
    public Ctx(String capId, List<Path> inputs, Map<String, String> params, Path outDir, Path workDir,
               BiConsumer<Integer, String> progress, java.util.concurrent.atomic.AtomicBoolean canceled) {
        this.capId = capId;
        this.inputs = List.copyOf(inputs);
        this.params = new LinkedHashMap<>(params);
        this.outDir = outDir;
        this.workDir = workDir;
        this.progress = progress;
        this.canceled = canceled;
    }

    /** 能力 id。 */
    public String capId() {
        return capId;
    }

    /** 全部输入文件（不可变）。需要「批量处理每个文件」的能力用这个遍历。 */
    public List<Path> inputs() {
        return inputs;
    }

    /**
     * 取第一个输入文件，同时充当空输入校验。
     *
     * <p>能力实现应以这一行开头。它保证「没选文件」的提示先于「参数不对」的提示出现——
     * 用户还没选文件却被告知页码格式错误，会很困惑。
     *
     * @throws Err {@code ERR_NO_INPUT} 输入列表为空
     */
    public Path input() {
        if (inputs.isEmpty()) {
            throw new Err("ERR_NO_INPUT", "请先选择文件");
        }
        return inputs.get(0);
    }

    /**
     * 取第 {@code i} 个输入文件（0 基）。
     *
     * <p>用于「至少需要两个文件」的能力，如合并、叠加、比对。
     *
     * @throws Err {@code ERR_NO_INPUT} 数量不够，消息里会说明还差几个
     */
    public Path input(int i) {
        if (i >= inputs.size()) {
            throw Err.of("ERR_NO_INPUT", "需要至少 %d 个文件，当前 %d 个", i + 1, inputs.size());
        }
        return inputs.get(i);
    }

    /** 参数表原始视图，一般不用，取值请走下面的便捷方法。 */
    public Map<String, String> params() {
        return params;
    }

    /** 取字符串参数，缺省为空串。不会 trim。 */
    public String str(String key) {
        return params.getOrDefault(key, "");
    }

    /**
     * 取字符串参数，缺省或空白时返回 {@code def}，否则返回 trim 后的值。
     *
     * <p>多数能力该用这个重载：用户把输入框清空（只剩空格）时按「没填」处理，
     * 比把一串空格当有效值更符合直觉。
     */
    public String str(String key, String def) {
        String v = params.get(key);
        return v == null || v.isBlank() ? def : v.trim();
    }

    /**
     * 取整数参数。
     *
     * @param def 未填时的默认值
     * @throws Err {@code ERR_BAD_PARAM} 填了但不是整数
     */
    public int intVal(String key, int def) {
        String v = str(key);
        if (v.isEmpty()) {
            return def;
        }
        try {
            return Integer.parseInt(v.trim());
        } catch (NumberFormatException e) {
            throw Err.of("ERR_BAD_PARAM", "参数「%s」需要整数，当前值：%s", key, v);
        }
    }

    /**
     * 取整数参数并做范围校验。
     *
     * <p>范围检查放在这里而不是让每个能力自己写 if，是为了错误码统一成
     * {@code ERR_PARAM_RANGE}、消息格式统一。测试据此断言「越界参数必须被拒绝」。
     *
     * @throws Err {@code ERR_BAD_PARAM} 非整数；{@code ERR_PARAM_RANGE} 超出范围
     */
    public int intVal(String key, int def, int min, int max) {
        int v = intVal(key, def);
        if (v < min || v > max) {
            throw Err.of("ERR_PARAM_RANGE", "参数「%s」必须在 %d 到 %d 之间，当前值：%d", key, min, max, v);
        }
        return v;
    }

    /** 取布尔参数。只有字符串 {@code "true"} 才算真，其余（含空）按 {@code def} 或假处理。 */
    public boolean bool(String key, boolean def) {
        String v = str(key);
        return v.isEmpty() ? def : Boolean.parseBoolean(v);
    }

    /** 输出目录。 */
    public Path outDir() {
        return outDir;
    }

    /** 临时工作目录。写中间文件前不必自己建目录，用 {@link #work(String)} 即可。 */
    public Path workDir() {
        return workDir;
    }

    /**
     * 生成输出文件路径，自动避让已存在文件与源文件。
     *
     * <p>两重避让都是必要的：
     * <ul>
     *   <li><b>避让已存在文件</b> —— 用户重复执行同一操作时，不该静默覆盖上一次的结果。
     *       重名就加 {@code _1}、{@code _2} 后缀。</li>
     *   <li><b>避让源文件</b> —— 如果输出名恰好等于输入名（比如「原地压缩」），
     *       直接写会把用户的原件毁掉。这种情况同样走加后缀。</li>
     * </ul>
     *
     * <p>文件名里的 {@code \ / : * ? " < > |} 会替换成下划线：这些字符在 Windows 上
     * 非法，多数来自用户输入的标题（水印文字、附件名、书签名）。
     *
     * @param fileName 期望的文件名（不含目录）
     * @return 可安全写入的路径，目录部分为 {@link #outDir()}
     */
    public Path out(String fileName) {
        String safe = fileName.replaceAll("[\\\\/:*?\"<>|]", "_");
        Path p = outDir.resolve(safe);
        if (Files.exists(p) || isSource(p)) {
            String base = safe;
            String ext = "";
            int dot = safe.lastIndexOf('.');
            if (dot > 0) {
                base = safe.substring(0, dot);
                ext = safe.substring(dot);
            }
            for (int i = 1; i < 1000; i++) {
                p = outDir.resolve(base + "_" + i + ext);
                if (!Files.exists(p) && !isSource(p)) {
                    break;
                }
            }
        }
        return p;
    }

    /**
     * 按源文件名派生输出名，如 {@code a.pdf} + 后缀 {@code _合并} → {@code a_合并.pdf}
     *
     * <p>以第一个输入文件为基准。批量能力里对每个文件调用时，要注意它取的始终是
     * 第一个输入——这类场景应该自己拼文件名再用 {@link #out(String)}。
     *
     * @param suffix 文件名后缀，通常以 {@code _} 开头
     * @param ext    新扩展名，含点，如 {@code ".pdf"}；传空串表示保留原名
     */
    public Path outDerived(String suffix, String ext) {
        String n = input().getFileName().toString();
        int dot = n.lastIndexOf('.');
        String base = dot > 0 ? n.substring(0, dot) : n;
        return out(base + suffix + ext);
    }

    /**
     * 判断某路径是否就是某个输入文件（用于防覆盖原件）。
     *
     * <p>比较前都做 {@code toAbsolutePath().normalize()}，否则 {@code a.pdf} 与
     * {@code ./sub/../a.pdf} 会被判成不同文件，避让失效。
     * 规范化失败时保守返回 false——宁可多写一个文件，也不误判成源文件而放弃输出。
     */
    private boolean isSource(Path p) {
        try {
            Path abs = p.toAbsolutePath().normalize();
            for (Path in : inputs) {
                if (in.toAbsolutePath().normalize().equals(abs)) {
                    return true;
                }
            }
        } catch (Exception ignored) {
            // 路径无法规范化时按非源文件处理
        }
        return false;
    }

    /**
     * 在临时目录里取一个文件路径，必要时先建目录。
     *
     * <p>工作目录是懒创建的：不需要中间文件的能力（大多数分析类）不会产生空目录。
     *
     * @throws Err {@code ERR_WORKDIR} 目录建不出来（磁盘满、权限不足）
     */
    public Path work(String name) {
        try {
            Files.createDirectories(workDir);
        } catch (IOException e) {
            throw new Err("ERR_WORKDIR", "无法创建临时目录：" + workDir, e);
        }
        return workDir.resolve(name);
    }

    /**
     * 上报进度，<b>同时检查是否已取消</b>。
     *
     * <p>这个方法承担了双重职责，所以长循环里必须调它——否则「取消」按钮形同虚设。
     * 典型写法是在每页处理完调一次，百分比自然推进。
     *
     * <p>百分比会被夹到 0–100，防止实现里算出来的越界值把进度条搞崩。
     *
     * @param pct 进度百分比
     * @param msg 当前动作，显示在状态栏，如「正在写入第 12 页」
     * @throws Err {@code ERR_CANCELED} 用户已点取消
     */
    public void report(int pct, String msg) {
        checkCancel();
        if (progress != null) {
            progress.accept(Math.max(0, Math.min(100, pct)), msg);
        }
    }

    /**
     * 检查取消令牌，已取消则抛出。
     *
     * <p>用于不适合上报进度的紧凑循环——比如单页内处理大量图元时，
     * 只想在循环里检查取消而不刷进度条。
     *
     * @throws Err {@code ERR_CANCELED}
     */
    public void checkCancel() {
        if (canceled != null && canceled.get()) {
            throw new Err("ERR_CANCELED", "任务已取消");
        }
    }

    /** 登记一个产物文件。界面靠这个列表展示「生成了什么」，不登记用户就找不到输出。 */
    public void addOutput(Path p) {
        outputs.add(p);
    }

    /** 已登记产物的快照（可变副本，改它不影响上下文）。 */
    public List<Path> outputs() {
        return new ArrayList<>(outputs);
    }

    /**
     * {@link #addOutput} 的同义方法。
     *
     * <p>保留两个名字是历史遗留：早期接口叫 {@code register}，后来改成语义更明确的
     * {@code addOutput}，但已有实现大量引用旧名，就都留着了。新代码请用 {@code addOutput}。
     */
    public void register(Path p) {
        addOutput(p);
    }

    /**
     * 分析类能力把结果文本放在这里，界面直接展示，不必先生成文件。
     *
     * <p>适用于「文档信息」「字体体检」「结构探查」这类只出结论的能力。
     * 与 {@link #addOutput} 可以并用——比如既写一份报告文件，又在界面里显示摘要。
     */
    public void setResult(String text) {
        this.result = text;
    }

    /** 文字结论，未设置时为 null。 */
    public String result() {
        return result;
    }
}
