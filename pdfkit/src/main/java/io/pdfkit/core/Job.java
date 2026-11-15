package io.pdfkit.core;

import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * 一次任务。状态迁移只允许 QUEUED → RUNNING → SUCCEEDED/FAILED/CANCELED。
 *
 * <p><b>线程模型是这个类最重要的约束</b>。任务对象被两个线程同时访问：
 * <ul>
 *   <li><b>工作线程</b>（{@link JobQueue} 的线程池）负责写：置 RUNNING、刷进度、
 *       写产物、置终态。</li>
 *   <li><b>界面线程</b>（EDT）负责读：刷新任务表格的行。</li>
 * </ul>
 * 因此所有可变字段都是 {@code volatile}——不是为了让操作原子，而是保证写入对另一个线程
 * 立即可见。没有 volatile 的话，界面可能一直显示「排队中」，直到某个偶然的内存屏障
 * 才刷新，表现为「卡住了」。
 *
 * <p><b>状态迁移方法是包私有</b>（无 {@code public}）：只有同包的 {@link JobQueue} 能改状态。
 * 界面拿到 {@link Job} 只能读，改不了——杜绝「界面直接把任务标成成功」这种越权操作。
 *
 * <p><b>为什么取消用独立的 {@code AtomicBoolean}</b>，而不是把状态置成 CANCELED 了事：
 * 状态字段表达的是「任务现在处于什么阶段」，而取消是「请求」，两者语义不同。
 * 用户点取消的瞬间任务还在跑（可能正写到一半），此时状态仍是 RUNNING，
 * 只是取消标志被置位；{@code Op} 里的 {@code ctx.report()} / {@code ctx.checkCancel()}
 * 会在下一个检查点抛出 {@code ERR_CANCELED}，那时才真正迁移到 CANCELED。
 */
public final class Job {

    /**
     * 任务状态。
     *
     * <p>前两个是过程态（可继续变化），后三个是终态（{@link #isTerminal()} 为真，不再变）。
     * 界面按 label 显示中文，不要在界面里另写一份「把枚举翻译成中文」的 switch——
     * 加状态时容易漏改。
     */
    public enum Status {
        QUEUED("排队中"), RUNNING("执行中"), SUCCEEDED("已完成"), FAILED("失败"), CANCELED("已取消");

        /** 界面显示用的中文名。 */
        private final String label;

        Status(String label) {
            this.label = label;
        }

        public String label() {
            return label;
        }
    }

    /** 任务标识，UUID。用于日志关联同一次任务的多条记录。 */
    private final String id = UUID.randomUUID().toString();
    /** 能力 id，如 {@code organize.merge}。 */
    private final String capId;
    /** 创建时的能力名称快照。不实时查注册表——万一能力后来被移除，历史任务还能显示名字。 */
    private final String title;
    /** 输入文件，不可变。 */
    private final List<Path> inputs;
    /** 创建时刻，用于界面按时间排序。 */
    private final LocalDateTime createdAt = LocalDateTime.now();

    /** 当前状态。volatile：工作线程写，界面线程读。 */
    private volatile Status status = Status.QUEUED;
    /** 进度百分比 0–100。 */
    private volatile int progress;
    /** 当前动作描述，显示在任务表格的「消息」列。 */
    private volatile String message = "等待执行";
    /** 失败时的错误码。成功或取消时为 null。 */
    private volatile String errorCode;
    /** 开始执行时刻，用于算耗时。 */
    private volatile LocalDateTime startedAt;
    /** 结束时刻（无论成功、失败还是取消）。 */
    private volatile LocalDateTime endedAt;
    /**
     * 产物列表。写时复制——工作线程追加时界面可能正在遍历渲染，
     * 普通 ArrayList 会抛 ConcurrentModificationException。
     */
    private final List<Path> outputs = new CopyOnWriteArrayList<>();
    /** 分析类能力的文字结论。 */
    private volatile String resultText;
    /** 取消请求标志，见类注释。 */
    private volatile java.util.concurrent.atomic.AtomicBoolean cancelFlag = new java.util.concurrent.atomic.AtomicBoolean();

    /**
     * @param capId  能力 id
     * @param title  能力名称，创建时快照
     * @param inputs 输入文件
     */
    public Job(String capId, String title, List<Path> inputs) {
        this.capId = capId;
        this.title = title;
        this.inputs = List.copyOf(inputs);
    }

    public String id() {
        return id;
    }

    public String capId() {
        return capId;
    }

    public String title() {
        return title;
    }

    public List<Path> inputs() {
        return inputs;
    }

    public LocalDateTime createdAt() {
        return createdAt;
    }

    public Status status() {
        return status;
    }

    public int progress() {
        return progress;
    }

    public String message() {
        return message;
    }

    public String errorCode() {
        return errorCode;
    }

    public LocalDateTime startedAt() {
        return startedAt;
    }

    public LocalDateTime endedAt() {
        return endedAt;
    }

    public List<Path> outputs() {
        return outputs;
    }

    public String resultText() {
        return resultText;
    }

    /** 包私有：只有队列能写结果文本。 */
    void setResultText(String t) {
        this.resultText = t;
    }

    /**
     * 取消令牌，交给 {@link Ctx} 使用。
     *
     * <p>{@code Op} 通过这个引用读取消状态，而不必反过来持有 {@link Job}——
     * 这让能力实现与任务模型解耦，测试时可以塞一个任意 AtomicBoolean 进去。
     */
    public java.util.concurrent.atomic.AtomicBoolean cancelFlag() {
        return cancelFlag;
    }

    /** 是否已到终态。界面据此决定「取消」按钮要不要置灰、行还要不要再刷新。 */
    public boolean isTerminal() {
        return status == Status.SUCCEEDED || status == Status.FAILED || status == Status.CANCELED;
    }

    /** 迁移到 RUNNING。由队列在工作线程开始执行前调用。 */
    void markRunning() {
        this.status = Status.RUNNING;
        this.startedAt = LocalDateTime.now();
        this.message = "开始执行";
    }

    /**
     * 刷新进度。
     *
     * <p>消息为空或全空白时不动原来的 message——很多能力只报百分比不报动作，
     * 这时保留上一次的动作描述比清空更有信息量。
     */
    void updateProgress(int pct, String msg) {
        this.progress = pct;
        if (msg != null && !msg.isBlank()) {
            this.message = msg;
        }
    }

    /**
     * 迁移到 SUCCEEDED。
     *
     * <p>先 clear 再 addAll：{@code Op} 执行途中可能已通过 {@code ctx.addOutput} 登记过产物，
     * 这里用最终结果整体覆盖，避免出现重复项。
     * 进度强制置 100——有些能力结尾不报进度，不置的话会停在 99% 看着像没跑完。
     */
    void markSucceeded(List<Path> outs) {
        this.outputs.clear();
        this.outputs.addAll(outs);
        this.status = Status.SUCCEEDED;
        this.progress = 100;
        this.message = "完成，产出 " + outs.size() + " 个文件";
        this.endedAt = LocalDateTime.now();
    }

    /**
     * 迁移到 FAILED。
     *
     * @param code 错误码；非业务异常传 {@code ERR_UNEXPECTED}
     * @param msg  面向用户的消息
     */
    void markFailed(String code, String msg) {
        this.status = Status.FAILED;
        this.errorCode = code;
        this.message = msg;
        this.endedAt = LocalDateTime.now();
    }

    /** 迁移到 CANCELED。进度保持不变——用户能看到取消时跑到哪一步。 */
    void markCanceled() {
        this.status = Status.CANCELED;
        this.message = "已取消";
        this.endedAt = LocalDateTime.now();
    }
}
