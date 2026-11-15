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

/** 任务队列：固定并发（默认 2），每个任务独立 work 目录，状态变化回调给 UI。 */
public final class JobQueue {

    private final ExecutorService pool;
    private final int concurrency;
    private final Consumer<Job> onChange;

    public JobQueue(int concurrency, Consumer<Job> onChange) {
        this.concurrency = Math.max(1, Math.min(4, concurrency));
        this.pool = Executors.newFixedThreadPool(this.concurrency, r -> {
            Thread t = new Thread(r, "pdfkit-worker");
            t.setDaemon(true);
            return t;
        });
        this.onChange = onChange;
    }

    public int concurrency() {
        return concurrency;
    }

    public Job submit(Capability cap, List<Path> inputs, Map<String, String> params, Path outDir) {
        Job job = new Job(cap.id(), cap.title(), inputs);
        notifyChange(job);
        Future<?> f = pool.submit(() -> execute(cap, job, inputs, params, outDir));
        // 不保留 Future 引用，取消通过 job.cancelFlag 实现
        return job;
    }

    private void execute(Capability cap, Job job, List<Path> inputs, Map<String, String> params, Path outDir) {
        job.markRunning();
        notifyChange(job);
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
                job.markCanceled();
            } else {
                List<Path> outs = ctx.outputs();
                job.setResultText(ctx.result());
                if (outs.isEmpty() && (ctx.result() == null || ctx.result().isBlank())) {
                    job.markFailed("ERR_EMPTY_OUTPUT", "任务结束但没有产出文件");
                } else {
                    job.markSucceeded(outs);
                }
            }
            Log.info("任务 %s [%s] 结束：%s", job.id().substring(0, 8), cap.id(), job.status());
        } catch (Err e) {
            if ("ERR_CANCELED".equals(e.code())) {
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
                // 临时目录清理失败不影响任务结果
            }
            notifyChange(job);
        }
    }

    private static String rootMessage(Throwable t) {
        Throwable c = t;
        while (c.getCause() != null && c.getCause() != c) {
            c = c.getCause();
        }
        String m = c.getMessage();
        return m == null || m.isBlank() ? c.getClass().getSimpleName() : m;
    }

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

    private void notifyChange(Job job) {
        if (onChange != null) {
            try {
                onChange.accept(job);
            } catch (Exception e) {
                Log.warn("UI 回调异常：%s", e.getMessage());
            }
        }
    }

    public void shutdown() {
        pool.shutdownNow();
    }
}
