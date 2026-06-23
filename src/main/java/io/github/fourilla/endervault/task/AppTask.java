package io.github.fourilla.endervault.task;

import io.github.fourilla.endervault.common.ByteSizeFormatter;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.concurrent.atomic.AtomicLong;

public class AppTask {

    private static final DateTimeFormatter LABEL_FORMATTER =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss").withZone(ZoneId.systemDefault());

    private final String id;
    private final TaskType type;
    private final String title;
    private final String actor;
    private final String ip;
    private final Instant createdAt;
    private volatile TaskStatus status = TaskStatus.QUEUED;
    private volatile Instant startedAt;
    private volatile Instant finishedAt;
    private volatile String targetPath;
    private volatile String message = "Waiting to start.";
    private volatile boolean cancelRequested;
    private final AtomicLong processedBytes = new AtomicLong();
    private final AtomicLong totalBytes = new AtomicLong(-1L);
    private final AtomicLong processedItems = new AtomicLong();
    private final AtomicLong totalItems = new AtomicLong(-1L);

    public AppTask(String id, TaskType type, String title, String targetPath, String actor, String ip) {
        this.id = id;
        this.type = type;
        this.title = title == null || title.isBlank() ? type.label() : title;
        this.targetPath = targetPath;
        this.actor = actor == null || actor.isBlank() ? "system" : actor;
        this.ip = ip == null || ip.isBlank() ? "-" : ip;
        this.createdAt = Instant.now();
    }

    public String id() {
        return id;
    }

    public String shortId() {
        return id.length() <= 8 ? id : id.substring(0, 8);
    }

    public TaskType type() {
        return type;
    }

    public String title() {
        return title;
    }

    public String actor() {
        return actor;
    }

    public String ip() {
        return ip;
    }

    public Instant createdAt() {
        return createdAt;
    }

    public TaskStatus status() {
        return status;
    }

    public String statusLabel() {
        return status.label();
    }

    public String statusClass() {
        return switch (status) {
            case COMPLETE -> "active";
            case PARTIAL, QUEUED, RUNNING -> "expired";
            case FAILED, CANCELED -> "revoked";
        };
    }

    public String targetPath() {
        return targetPath;
    }

    public String message() {
        return message;
    }

    public boolean cancelRequested() {
        return cancelRequested;
    }

    public boolean active() {
        return status.active();
    }

    public long processedBytes() {
        return processedBytes.get();
    }

    public long totalBytes() {
        return totalBytes.get();
    }

    public long processedItems() {
        return processedItems.get();
    }

    public long totalItems() {
        return totalItems.get();
    }

    public int progressPercent() {
        long byteTotal = totalBytes();
        if (byteTotal > 0L) {
            return boundedPercent(processedBytes(), byteTotal);
        }
        long itemTotal = totalItems();
        if (itemTotal > 0L) {
            return boundedPercent(processedItems(), itemTotal);
        }
        return status == TaskStatus.COMPLETE || status == TaskStatus.PARTIAL ? 100 : 0;
    }

    public String progressLabel() {
        long byteTotal = totalBytes();
        if (byteTotal > 0L) {
            return "%s / %s".formatted(
                    ByteSizeFormatter.humanSize(processedBytes()),
                    ByteSizeFormatter.humanSize(byteTotal)
            );
        }
        long itemTotal = totalItems();
        if (itemTotal > 0L) {
            return "%d / %d items".formatted(processedItems(), itemTotal);
        }
        return ByteSizeFormatter.humanSize(processedBytes());
    }

    public String createdLabel() {
        return LABEL_FORMATTER.format(createdAt);
    }

    public String startedLabel() {
        return startedAt == null ? "-" : LABEL_FORMATTER.format(startedAt);
    }

    public String finishedLabel() {
        return finishedAt == null ? "-" : LABEL_FORMATTER.format(finishedAt);
    }

    void markRunning() {
        if (cancelRequested) {
            return;
        }
        status = TaskStatus.RUNNING;
        startedAt = Instant.now();
        message = "Running.";
    }

    void markComplete(String message) {
        status = TaskStatus.COMPLETE;
        finishedAt = Instant.now();
        this.message = blankToDefault(message, "Complete.");
    }

    void markPartial(String message) {
        status = TaskStatus.PARTIAL;
        finishedAt = Instant.now();
        this.message = blankToDefault(message, "Completed with warnings.");
    }

    void markFailed(String message) {
        status = TaskStatus.FAILED;
        finishedAt = Instant.now();
        this.message = blankToDefault(message, "Task failed.");
    }

    boolean requestCancel() {
        if (!active()) {
            return false;
        }
        cancelRequested = true;
        message = "Canceling.";
        return true;
    }

    void markCanceled(String message) {
        status = TaskStatus.CANCELED;
        finishedAt = Instant.now();
        this.message = blankToDefault(message, "Canceled.");
    }

    void setTargetPath(String targetPath) {
        this.targetPath = targetPath;
    }

    void setMessage(String message) {
        if (message != null && !message.isBlank()) {
            this.message = message;
        }
    }

    void setTotalBytes(long totalBytes) {
        this.totalBytes.set(Math.max(-1L, totalBytes));
    }

    void addProcessedBytes(long bytes) {
        if (bytes > 0L) {
            this.processedBytes.addAndGet(bytes);
        }
    }

    void setTotalItems(long totalItems) {
        this.totalItems.set(Math.max(-1L, totalItems));
    }

    void incrementProcessedItems() {
        this.processedItems.incrementAndGet();
    }

    private int boundedPercent(long value, long total) {
        if (total <= 0L) {
            return 0;
        }
        return (int) Math.max(0L, Math.min(100L, Math.round((double) value * 100.0 / total)));
    }

    private String blankToDefault(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }
}
