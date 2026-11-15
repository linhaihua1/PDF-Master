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

/** 极简日志：控制台 + 按天滚动文件。不引 slf4j，减少体积与启动开销。 */
public final class Log {

    private static final DateTimeFormatter TS = DateTimeFormatter.ofPattern("HH:mm:ss.SSS");
    private static volatile Path logFile;
    private static final Object LOCK = new Object();

    private Log() {
    }

    public static void init(Path logsDir) {
        try {
            Files.createDirectories(logsDir);
            String name = "pdfkit-" + LocalDate.now().format(DateTimeFormatter.BASIC_ISO_DATE) + ".log";
            logFile = logsDir.resolve(name);
        } catch (IOException e) {
            logFile = null;
        }
    }

    public static Path currentFile() {
        return logFile;
    }

    public static void info(String fmt, Object... args) {
        write("INFO", String.format(Locale.ROOT, fmt, args), null);
    }

    public static void warn(String fmt, Object... args) {
        write("WARN", String.format(Locale.ROOT, fmt, args), null);
    }

    public static void error(String msg, Throwable t) {
        write("ERROR", msg, t);
    }

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
