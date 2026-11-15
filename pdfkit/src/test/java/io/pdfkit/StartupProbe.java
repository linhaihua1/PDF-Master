package io.pdfkit;

import io.pdfkit.ui.MainFrame;
import io.pdfkit.ui.Ui;

import javax.swing.JFrame;
import javax.swing.SwingUtilities;

/**
 * 启动与内存探针。由 PerformanceTest 以独立 JVM 启动，避免测试框架自身的堆占用
 * 污染数据 —— 用户机器上跑的是纯应用，不是带着 JUnit 的应用。
 *
 * <p>两种模式：
 * <ul>
 *   <li>默认：输出 {@code READY <毫秒>}，从进程创建到主窗口可见，含 JVM 启动开销</li>
 *   <li>{@code --heap}：额外输出 {@code HEAP <字节>}，主窗口稳定显示后 GC 两次再取 used heap</li>
 * </ul>
 *
 * <p>不调用 App.main，避开单实例文件锁；只复现 App 的初始化路径。
 */
public final class StartupProbe {

    private StartupProbe() {
    }

    public static void main(String[] args) throws Exception {
        boolean heapMode = args.length > 0 && "--heap".equals(args[0]);

        long t0 = System.nanoTime();
        try {
            com.formdev.flatlaf.FlatLightLaf.setup();
            Ui.install();
        } catch (Exception ignored) {
            // 主题失败不影响启动耗时结论
        }

        final JFrame[] holder = new JFrame[1];
        final long[] mark = new long[2];
        // 整个过程保持在一个 invokeAndWait 里，与真实启动路径一致；
        // 内部打两个时间点，只为拿到「构建 / 显示」的分布
        SwingUtilities.invokeAndWait(() -> {
            mark[0] = System.nanoTime();
            holder[0] = new MainFrame();
            mark[1] = System.nanoTime();
            holder[0].setLocation(-4000, -4000);
            holder[0].setVisible(true);
        });
        long t1 = System.nanoTime();

        System.out.println("READY " + (t1 - t0) / 1_000_000L);
        System.out.flush();   // 父进程按行读取并以读到时刻计时，必须立即落管道
        // 阶段剖面：用于定位启动瓶颈，报告里要给出头号开销
        System.out.printf("STAGES laf=%d frame=%d show=%d%n",
                (mark[0] - t0) / 1_000_000L,
                (mark[1] - mark[0]) / 1_000_000L,
                (t1 - mark[1]) / 1_000_000L);

        if (heapMode) {
            // 让事件队列把首屏渲染、字体加载都跑完，再测空闲态
            Thread.sleep(500);
            System.gc();
            Thread.sleep(250);

            // 只统计堆会严重低估：Metaspace、CodeCache、线程栈都不在 Runtime 里。
            // 进程真实足迹 ≈ 堆 used + 非堆 used + 线程数 × 1 MB 栈
            var mmb = java.lang.management.ManagementFactory.getMemoryMXBean();
            long heapUsed = mmb.getHeapMemoryUsage().getUsed();
            long nonHeapUsed = mmb.getNonHeapMemoryUsage().getUsed();
            int threads = java.lang.management.ManagementFactory.getThreadMXBean().getThreadCount();
            long footprint = heapUsed + nonHeapUsed + threads * 1024L * 1024L;

            System.out.println("HEAP " + footprint);
            System.out.printf("HEAP_DETAIL heap=%d nonheap=%d threads=%d heapCommitted=%d%n",
                    heapUsed, nonHeapUsed, threads, mmb.getHeapMemoryUsage().getCommitted());
        }

        System.out.flush();
        System.exit(0);
    }
}
