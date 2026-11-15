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

/** 一次任务执行的上下文：输入、参数、输出目录、进度、取消令牌。 */
public final class Ctx {

    private final String capId;
    private final List<Path> inputs;
    private final Map<String, String> params;
    private final Path outDir;
    private final Path workDir;
    private final BiConsumer<Integer, String> progress;
    private final java.util.concurrent.atomic.AtomicBoolean canceled;
    private final List<Path> outputs = new CopyOnWriteArrayList<>();
    private volatile String result;

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

    public String capId() {
        return capId;
    }

    public List<Path> inputs() {
        return inputs;
    }

    public Path input() {
        if (inputs.isEmpty()) {
            throw new Err("ERR_NO_INPUT", "请先选择文件");
        }
        return inputs.get(0);
    }

    public Path input(int i) {
        if (i >= inputs.size()) {
            throw Err.of("ERR_NO_INPUT", "需要至少 %d 个文件，当前 %d 个", i + 1, inputs.size());
        }
        return inputs.get(i);
    }

    public Map<String, String> params() {
        return params;
    }

    public String str(String key) {
        return params.getOrDefault(key, "");
    }

    public String str(String key, String def) {
        String v = params.get(key);
        return v == null || v.isBlank() ? def : v.trim();
    }

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

    public int intVal(String key, int def, int min, int max) {
        int v = intVal(key, def);
        if (v < min || v > max) {
            throw Err.of("ERR_PARAM_RANGE", "参数「%s」必须在 %d 到 %d 之间，当前值：%d", key, min, max, v);
        }
        return v;
    }

    public boolean bool(String key, boolean def) {
        String v = str(key);
        return v.isEmpty() ? def : Boolean.parseBoolean(v);
    }

    public Path outDir() {
        return outDir;
    }

    public Path workDir() {
        return workDir;
    }

    /** 生成输出文件路径，自动避让已存在文件与源文件。 */
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

    /** 按源文件名派生输出名，如 a.pdf + 后缀 "_合并" → a_合并.pdf */
    public Path outDerived(String suffix, String ext) {
        String n = input().getFileName().toString();
        int dot = n.lastIndexOf('.');
        String base = dot > 0 ? n.substring(0, dot) : n;
        return out(base + suffix + ext);
    }

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

    public Path work(String name) {
        try {
            Files.createDirectories(workDir);
        } catch (IOException e) {
            throw new Err("ERR_WORKDIR", "无法创建临时目录：" + workDir, e);
        }
        return workDir.resolve(name);
    }

    public void report(int pct, String msg) {
        checkCancel();
        if (progress != null) {
            progress.accept(Math.max(0, Math.min(100, pct)), msg);
        }
    }

    public void checkCancel() {
        if (canceled != null && canceled.get()) {
            throw new Err("ERR_CANCELED", "任务已取消");
        }
    }

    public void addOutput(Path p) {
        outputs.add(p);
    }

    public List<Path> outputs() {
        return new ArrayList<>(outputs);
    }

    public void register(Path p) {
        addOutput(p);
    }

    /** 分析类能力把结果文本放在这里，UI 直接展示，不必先生成文件。 */
    public void setResult(String text) {
        this.result = text;
    }

    public String result() {
        return result;
    }
}
