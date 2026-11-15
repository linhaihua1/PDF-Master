package io.pdfkit.core;

import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;

/** 一次任务。状态迁移只允许 QUEUED → RUNNING → SUCCEEDED/FAILED/CANCELED。 */
public final class Job {

    public enum Status {
        QUEUED("排队中"), RUNNING("执行中"), SUCCEEDED("已完成"), FAILED("失败"), CANCELED("已取消");

        private final String label;

        Status(String label) {
            this.label = label;
        }

        public String label() {
            return label;
        }
    }

    private final String id = UUID.randomUUID().toString();
    private final String capId;
    private final String title;
    private final List<Path> inputs;
    private final LocalDateTime createdAt = LocalDateTime.now();

    private volatile Status status = Status.QUEUED;
    private volatile int progress;
    private volatile String message = "等待执行";
    private volatile String errorCode;
    private volatile LocalDateTime startedAt;
    private volatile LocalDateTime endedAt;
    private final List<Path> outputs = new CopyOnWriteArrayList<>();
    private volatile String resultText;
    private volatile java.util.concurrent.atomic.AtomicBoolean cancelFlag = new java.util.concurrent.atomic.AtomicBoolean();

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

    void setResultText(String t) {
        this.resultText = t;
    }

    public java.util.concurrent.atomic.AtomicBoolean cancelFlag() {
        return cancelFlag;
    }

    public boolean isTerminal() {
        return status == Status.SUCCEEDED || status == Status.FAILED || status == Status.CANCELED;
    }

    void markRunning() {
        this.status = Status.RUNNING;
        this.startedAt = LocalDateTime.now();
        this.message = "开始执行";
    }

    void updateProgress(int pct, String msg) {
        this.progress = pct;
        if (msg != null && !msg.isBlank()) {
            this.message = msg;
        }
    }

    void markSucceeded(List<Path> outs) {
        this.outputs.clear();
        this.outputs.addAll(outs);
        this.status = Status.SUCCEEDED;
        this.progress = 100;
        this.message = "完成，产出 " + outs.size() + " 个文件";
        this.endedAt = LocalDateTime.now();
    }

    void markFailed(String code, String msg) {
        this.status = Status.FAILED;
        this.errorCode = code;
        this.message = msg;
        this.endedAt = LocalDateTime.now();
    }

    void markCanceled() {
        this.status = Status.CANCELED;
        this.message = "已取消";
        this.endedAt = LocalDateTime.now();
    }
}
