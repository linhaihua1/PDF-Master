package io.pdfkit.util;

import java.awt.Desktop;
import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;

/**
 * 运行环境：数据目录、单实例锁、打开文件夹。全部路径由本程序自己决定，不依赖环境变量。
 *
 * <p><b>所有路径都从 {@link #dataDir()} 派生，不要在各处硬拼路径。</b>
 * 换存储位置时只改这一个方法。
 *
 * <p>目录约定：
 * <pre>
 * %LOCALAPPDATA%\PdfKit\
 * ├── .lock          单实例锁文件
 * ├── jobs\          每个任务的临时目录，任务结束即删
 * ├── logs\          按天滚动的日志
 * └── config\        预留，当前未使用
 *
 * %USERPROFILE%\Documents\PdfKit\   默认输出目录（用户可见）
 * </pre>
 */
public final class Sys {

    /** 单实例锁。持有到进程退出，不能提前释放，否则第二个实例就能进来了。 */
    private static FileLock lock;
    /** 锁文件通道，需保持打开状态，关掉锁就失效。 */
    private static FileChannel lockChannel;

    /** 工具类，禁止实例化。 */
    private Sys() {
    }

    /**
     * 数据目录：{@code %LOCALAPPDATA%\PdfKit}。
     *
     * <p><b>为什么用 LOCALAPPDATA 而不是用户文档目录</b>：这里放的是日志、临时文件、
     * 锁文件，都是程序内部产物，不该出现在用户的「文档」里被误删或误当成资料。
     * 用户真正关心的输出文件放在 {@link #defaultOutDir()}。
     *
     * <p>取不到环境变量时退化成 {@code ~/AppData/Local}——直接拼路径虽然不严谨，
     * 但比启动失败好。
     */
    public static Path dataDir() {
        String local = System.getenv("LOCALAPPDATA");
        if (local == null || local.isBlank()) {
            local = System.getProperty("user.home") + "/AppData/Local";
        }
        return Paths.get(local, "PdfKit");
    }

    /** 任务临时目录的根。每个任务在里面再建一个以 job.id 命名的子目录。 */
    public static Path jobsDir() {
        return dataDir().resolve("jobs");
    }

    /** 日志目录。 */
    public static Path logsDir() {
        return dataDir().resolve("logs");
    }

    /** 配置目录，当前预留未用。 */
    public static Path configDir() {
        return dataDir().resolve("config");
    }

    /**
     * 默认输出目录：{@code ~/Documents/PdfKit}。
     *
     * <p>建不出来时退回系统临时目录——虽然位置不理想，但保证「至少能输出」。
     * 这种情况一般意味着用户账户没有文档目录写权限（少见），报错让程序不可用更糟。
     */
    public static Path defaultOutDir() {
        Path p = Paths.get(System.getProperty("user.home"), "Documents", "PdfKit");
        try {
            Files.createDirectories(p);
        } catch (IOException ignored) {
            return Paths.get(System.getProperty("java.io.tmpdir"));
        }
        return p;
    }

    /**
     * 单实例：拿到锁返回 true，已在运行返回 false。
     *
     * <p>实现是「对锁文件加 OS 级排他锁」而非「检查 PID 文件」：前者在进程崩溃时
     * 由操作系统自动释放，不会留下「僵尸锁」导致程序再也开不起来。
     *
     * <p><b>锁获取异常时返回 true（允许启动）</b>：这是刻意选择。锁机制的失败原因可能是
     * 文件系统不支持锁、权限异常等，此时多开一个窗口是可接受的小问题，
     * 而「因为锁机制故障导致程序完全打不开」是不可接受的大问题。宁可失效开放，不要失效关闭。
     *
     * @return true 表示可以继续启动
     */
    public static boolean acquireSingleInstance() {
        try {
            Files.createDirectories(dataDir());
            Path lockFile = dataDir().resolve(".lock");
            lockChannel = FileChannel.open(lockFile, StandardOpenOption.CREATE, StandardOpenOption.WRITE);
            lock = lockChannel.tryLock();
            if (lock == null) {
                return false;
            }
            // deleteOnExit 只是尽力而为：进程被强杀时删不掉，但下次启动 CREATE 会复用这个文件
            lockFile.toFile().deleteOnExit();
            return true;
        } catch (Exception e) {
            Log.warn("单实例锁获取失败，继续启动：%s", e.getMessage());
            return true;
        }
    }

    /**
     * 用系统资源管理器打开文件夹，不存在则先创建。
     *
     * <p>必须检查 {@link Desktop#isDesktopSupported()}：在无桌面环境（某些服务器、
     * 精简系统）下调用会抛 {@code UnsupportedOperationException}。失败只记日志，
     * 因为「打开文件夹」通常是锦上添花的操作，不该因为它中断主流程。
     */
    public static void openFolder(Path dir) {
        try {
            if (!Files.exists(dir)) {
                Files.createDirectories(dir);
            }
            if (Desktop.isDesktopSupported()) {
                Desktop.getDesktop().open(dir.toFile());
            }
        } catch (Exception e) {
            Log.warn("打开文件夹失败：%s", e.getMessage());
        }
    }

    /** 是否 Windows。部分能力（字体路径、文件名规则）需要按平台区分。 */
    public static boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase().contains("win");
    }
}
