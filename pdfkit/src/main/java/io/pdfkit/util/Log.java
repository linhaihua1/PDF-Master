package io.pdfkit.util;

import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Locale;

/**
 * 极简日志：控制台 + 按天滚动文件。不引 slf4j，减少体积与启动开销。
 *
 * <p><b>为什么不用 slf4j + logback</b>：这套组合会给安装包增加约 2 MB，
 * 首启时还要初始化配置、扫描 provider，多几十毫秒。本项目的日志需求只有
 * 「记一行带时间戳的话」和「记一次异常堆栈」，自己写 60 行就够，不值得为此付体积和启动成本。
 *
 * <p><b>输出到两处</b>：
 * <ul>
 *   <li>{@code System.err} —— 开发时在控制台直接看；打包后用户看不到，
 *       但 launcher 崩溃时这段输出会被捕获。</li>
 *   <li>按天滚动的文件 —— 用户反馈问题时让他把 {@link #currentFile()} 发过来。</li>
 * </ul>
 *
 * <p><b>日志绝不能影响业务</b>：所有写入路径的异常都被吞掉。日志写失败（磁盘满、
 * 文件被占用）时，业务该继续跑——没有日志总比功能挂掉好。
 */
public final class Log {

    /** 时间戳格式，只到毫秒，不带日期——日期已经在文件名里了。 */
    private static final DateTimeFormatter TS = DateTimeFormatter.ofPattern("HH:mm:ss.SSS");
    /** 当前日志文件。未初始化或初始化失败时为 null，此时只往控制台输出。 */
    private static volatile Path logFile;
    /** 文件追加锁。多个工作线程会并发写日志，没锁会写出交错的半行。 */
    private static final Object LOCK = new Object();

    /** 工具类，禁止实例化。 */
    private Log() {
    }

    /**
     * 初始化日志目录与文件名，应在程序启动最早期调用。
     *
     * <p>按天滚动：文件名带 {@code yyyyMMdd}，跨天时不会自动切到新文件——
     * 因为单次运行通常不会跨天，需要时重启即可。也不做历史清理，用户可自行删除。
     *
     * @param logsDir 日志目录，通常来自 {@link Sys#logsDir()}
     */
    public static void init(Path logsDir) {
        try {
            Files.createDirectories(logsDir);
            String name = "pdfkit-" + LocalDate.now().format(DateTimeFormatter.BASIC_ISO_DATE) + ".log";
            logFile = logsDir.resolve(name);
        } catch (IOException e) {
            // 建不出目录就退化成「只输出到控制台」，不影响程序启动
            logFile = null;
        }
    }

    /** 当前日志文件路径，用于界面上「打开日志」这类功能。未初始化时为 null。 */
    public static Path currentFile() {
        return logFile;
    }

    /** 记一条普通信息。参数用 {@code String.format} 语法。 */
    public static void info(String fmt, Object... args) {
        write("INFO", String.format(Locale.ROOT, fmt, args), null);
    }

    /** 记一条警告。用于「不影响主流程但值得注意」的情况。 */
    public static void warn(String fmt, Object... args) {
        write("WARN", String.format(Locale.ROOT, fmt, args), null);
    }

    /** 记一条错误，附带异常堆栈。堆栈只进日志不进界面。 */
    public static void error(String msg, Throwable t) {
        write("ERROR", msg, t);
    }

    /**
     * 实际写日志。
     *
     * <p>{@code Locale.ROOT} 是必要的：某些语言环境下 {@code %d} 会输出带千分位分隔符
     * 的数字（如 {@code 1.234}），日志里的任务号、百分比就不可解析了。ROOT 保证
     * 格式行为在任何系统区域设置下一致。
     *
     * @param level 日志级别字符串
     * @param msg   已格式化好的消息
     * @param t     异常，可为 null
     */
    private static void write(String level, String msg, Throwable t) {
        String line = LocalDateTime.now().format(TS) + " " + level + " " + msg;
        System.err.println(line);
        if (t != null) {
            StringWriter sw = new StringWriter();
            t.printStackTrace(new PrintWriter(sw));
            System.err.println(sw);
            line = line + System.lineSeparator() + sw;
        }
        Path f = logFile;
        if (f == null) {
            return;
        }
        synchronized (LOCK) {
            try {
                Files.writeString(f, line + System.lineSeparator(), StandardCharsets.UTF_8,
                        StandardOpenOption.CREATE, StandardOpenOption.APPEND);
            } catch (IOException ignored) {
                // 日志失败不能影响业务
            }
        }
    }
}
