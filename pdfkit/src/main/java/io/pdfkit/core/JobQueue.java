package io.pdfkit.core;

import io.pdfkit.util.Log;
import io.pdfkit.util.Sys;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/**
 * 任务队列：固定并发（默认 2），每个任务独立 work 目录，状态变化回调给 UI。
 *
 * <p>这是「界面」与「能力」之间唯一的桥梁：界面提交 {@link Job}，队列在后台线程跑
 * {@link Op#run(Ctx)}，过程中通过 {@link #onChange} 回调把状态推给界面。
 *
 * <p><b>为什么要队列而不是直接在当前线程跑</b>：PDF 处理动辄几秒到几十秒，
 * 在 EDT 上跑会让整个界面假死——进度条不动、取消按钮点不了、窗口拖不动。
 * 队列把耗时工作挪到后台线程，界面只负责刷新。
 *
 * <p><b>并发数为什么夹在 1–4</b>：<ul>
 *   <li>下界 1：并发数配成 0 会让 {@code newFixedThreadPool} 抛异常。</li>
 *   <li>上界 4：每个任务在处理 500 页 PDF 时峰值能到 700 MB 左右（实测），
 *       再高就有 OOM 风险。默认用 2，兼顾「同时干活」和「内存可控」。</li>
 * </ul>
 *
 * <p><b>取消为什么不用 {@code Future.cancel}</b>：{@code Future.cancel(true)} 靠线程中断，
 * 而 PDFBox 的 IO 操作大多不响应中断，实际是「请求了但停不下来」。
 * 这里改用协作式取消——{@link Job#cancelFlag()} 置位后，由能力实现里的
 * {@code ctx.report()} / {@code ctx.checkCancel()} 在检查点主动抛出。见 {@link Job} 的类注释。
 */
public final class JobQueue {

    /** 工作线程池。固定大小，任务多了排队。 */
    private final ExecutorService pool;
    /** 实际生效的并发数（已夹到 1–4）。 */
    private final int concurrency;
    /** 状态变化回调，通常是界面刷新任务表格。可为 null（无界面场景）。 */
    private final Consumer<Job> onChange;

    /**
     * @param concurrency 期望并发数，会被夹到 1–4
     * @param onChange    状态变化回调，可为 null
     */
    public JobQueue(int concurrency, Consumer<Job> onChange) {
        this.concurrency = Math.max(1, Math.min(4, concurrency));
        // 线程设为 daemon：否则用户关窗后，只要还有任务在跑，JVM 就退不出去，
        // 表现为「关了窗口进程还在任务管理器里」。
        this.pool = Executors.newFixedThreadPool(this.concurrency, r -> {
            Thread t = new Thread(r, "pdfkit-worker");
            t.setDaemon(true);
            return t;
        });
        this.onChange = onChange;
    }

    /** 实际生效的并发数，界面显示在状态栏。 */
    public int concurrency() {
        return concurrency;
    }

    /**
     * 提交一个任务，立即返回（不阻塞）。
     *
     * <p>先 {@code notifyChange} 再提交：界面需要马上看到一行「排队中」，否则用户点了执行
     * 却什么都没发生，会以为按钮没响。提交本身也可能排队，等待期间状态就该是 QUEUED。
     *
     * <p>返回的 {@link Job} 是<b>可变对象</b>，但界面拿它只能读（状态迁移方法都是包私有），
     * 想取消得点取消按钮，走 {@link Job#cancelFlag()}。
     *
     * @param cap    要执行的能力
     * @param inputs 输入文件
     * @param params 用户填的参数
     * @param outDir 输出目录
     * @return 新建的任务，调用方可立即用于界面绑定
     */
    public Job submit(Capability cap, List<Path> inputs, Map<String, String> params, Path outDir) {
        Job job = new Job(cap.id(), cap.title(), inputs);
        notifyChange(job);
        Future<?> f = pool.submit(() -> execute(cap, job, inputs, params, outDir));
        // 不保留 Future 引用，取消通过 job.cancelFlag 实现（理由见类注释）
        return job;
    }

    /**
     * 在工作线程里执行一个任务，并负责把结果写回 {@link Job}。
     *
     * <p>整个过程包在 try-catch-finally 里，保证三件事：<ul>
     *   <li>业务异常（{@link Err}）→ 用它的错误码置 FAILED，消息可直接给用户看；</li>
     *   <li>其它异常 → 置 {@code ERR_UNEXPECTED}，消息取根因的简短描述；</li>
     *   <li>无论成败都清理临时目录，并做最后一次状态通知。</li>
     * </ul>
     */
    private void execute(Capability cap, Job job, List<Path> inputs, Map<String, String> params, Path outDir) {
        job.markRunning();
        notifyChange(job);
        // 每个任务独占一个 work 子目录，用 job.id 命名。并发执行时若共用同一目录，
        // 两个任务往同名临时文件里写会互相覆盖，产物可能张冠李戴。
        Path workDir = Sys.jobsDir().resolve(job.id());
        try {
            Files.createDirectories(workDir);
            AtomicBoolean cancel = job.cancelFlag();
            Ctx ctx = new Ctx(cap.id(), inputs, params, outDir, workDir,
                    (pct, msg) -> {
                        job.updateProgress(pct, msg);
                        notifyChange(job);
                    }, cancel);
            cap.op().run(ctx);
            if (cancel.get()) {
                // 能力正常返回但取消标志已置位：说明它在最后一个检查点之后才跑完。
                // 这种情况按「已取消」处理，不展示产物——用户已经表示不要了。
                job.markCanceled();
            } else {
                List<Path> outs = ctx.outputs();
                job.setResultText(ctx.result());
                // 既没产物也没文字结论，说明这个能力什么都没干成。这种情况必须报错：
                // 否则任务显示「已完成」，用户却找不到任何输出文件，比直接报错更让人困惑。
                if (outs.isEmpty() && (ctx.result() == null || ctx.result().isBlank())) {
                    job.markFailed("ERR_EMPTY_OUTPUT", "任务结束但没有产出文件");
                } else {
                    job.markSucceeded(outs);
                }
            }
            Log.info("任务 %s [%s] 结束：%s", job.id().substring(0, 8), cap.id(), job.status());
        } catch (Err e) {
            if ("ERR_CANCELED".equals(e.code())) {
                // 取消不算失败，不写 error 日志——它是正常的用户操作
                job.markCanceled();
            } else {
                job.markFailed(e.code(), e.getMessage());
                Log.error("任务失败 [" + e.code() + "] " + e.getMessage(), e);
            }
        } catch (Exception e) {
            job.markFailed("ERR_UNEXPECTED", "执行异常：" + rootMessage(e));
            Log.error("任务异常 " + cap.id(), e);
        } finally {
            try {
                deleteRecursively(workDir);
            } catch (Exception ignored) {
                // 临时目录清理失败不影响任务结果。残留目录下次启动时会被清扫。
            }
            notifyChange(job);
        }
    }

    /**
     * 取最内层异常的消息。
     *
     * <p>为什么要剥到根因：PDFBox 的异常层层包装，最外层往往是
     * 「Cannot save document」这类没信息量的话，真正的线索（「文件被占用」「磁盘已满」）
     * 藏在最里层。给用户看的消息取最里层更有用。
     *
     * <p>消息为空时退化成异常类名——总比空字符串强。
     */
    private static String rootMessage(Throwable t) {
        Throwable c = t;
        while (c.getCause() != null && c.getCause() != c) {
            c = c.getCause();
        }
        String m = c.getMessage();
        return m == null || m.isBlank() ? c.getClass().getSimpleName() : m;
    }

    /**
     * 递归删除目录。
     *
     * <p>必须<b>先删子项再删父目录</b>，所以按路径深度倒序排——Windows 上直接删非空目录
     * 会抛异常。单文件删除失败只记忽略、继续删下一个：临时文件可能被其它进程短暂占用，
     * 为此中断整个清理不值得。
     */
    private static void deleteRecursively(Path dir) {
        if (dir == null || !Files.exists(dir)) {
            return;
        }
        try (var walk = Files.walk(dir)) {
            List<Path> all = new ArrayList<>(walk.sorted((a, b) -> b.getNameCount() - a.getNameCount()).toList());
            for (Path p : all) {
                try {
                    Files.deleteIfExists(p);
                } catch (Exception ignored) {
                    // 单文件删除失败继续
                }
            }
        } catch (Exception ignored) {
            // 目录遍历失败忽略
        }
    }

    /**
     * 通知界面状态变了。
     *
     * <p>回调异常必须吞掉：界面刷新代码出 bug 时，不该连带把正在执行的任务搞崩。
     * 这里只记一条 warn，任务继续跑。
     */
    private void notifyChange(Job job) {
        if (onChange != null) {
            try {
                onChange.accept(job);
            } catch (Exception e) {
                Log.warn("UI 回调异常：%s", e.getMessage());
            }
        }
    }

    /**
     * 关闭队列，中断正在执行的任务。
     *
     * <p>用 {@code shutdownNow} 而非 {@code shutdown}：后者会等所有任务跑完，
     * 用户关窗时如果有 500 页任务在跑，进程会挂很久退不出去。
     * 中断后任务会抛异常，被上面的 catch 捕获并置 FAILED——这种「关窗导致的失败」
     * 不影响数据，因为产物都是原子写入（先写临时文件再改名）。
     */
    public void shutdown() {
        pool.shutdownNow();
    }
}
