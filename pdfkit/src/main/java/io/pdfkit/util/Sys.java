package io.pdfkit.util;

import java.awt.Desktop;
import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;

/** 运行环境：数据目录、单实例锁、打开文件夹。全部路径由本程序自己决定，不依赖环境变量。 */
public final class Sys {

    private static FileLock lock;
    private static FileChannel lockChannel;

    private Sys() {
    }

    public static Path dataDir() {
        String local = System.getenv("LOCALAPPDATA");
        if (local == null || local.isBlank()) {
            local = System.getProperty("user.home") + "/AppData/Local";
        }
        return Paths.get(local, "PdfKit");
    }

    public static Path jobsDir() {
        return dataDir().resolve("jobs");
    }

    public static Path logsDir() {
        return dataDir().resolve("logs");
    }

    public static Path configDir() {
        return dataDir().resolve("config");
    }

    public static Path defaultOutDir() {
        Path p = Paths.get(System.getProperty("user.home"), "Documents", "PdfKit");
        try {
            Files.createDirectories(p);
        } catch (IOException ignored) {
            return Paths.get(System.getProperty("java.io.tmpdir"));
        }
        return p;
    }

    /** 单实例：拿到锁返回 true，已在运行返回 false。 */
    public static boolean acquireSingleInstance() {
        try {
            Files.createDirectories(dataDir());
            Path lockFile = dataDir().resolve(".lock");
            lockChannel = FileChannel.open(lockFile, StandardOpenOption.CREATE, StandardOpenOption.WRITE);
            lock = lockChannel.tryLock();
            if (lock == null) {
                return false;
            }
            lockFile.toFile().deleteOnExit();
            return true;
        } catch (Exception e) {
            Log.warn("单实例锁获取失败，继续启动：%s", e.getMessage());
            return true;
        }
    }

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

    public static boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase().contains("win");
    }
}
