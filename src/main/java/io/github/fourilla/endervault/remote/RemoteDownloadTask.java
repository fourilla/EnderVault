package io.github.fourilla.endervault.remote;

import io.github.fourilla.endervault.common.ByteSizeFormatter;
import io.github.fourilla.endervault.outbound.NetworkRoute;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

public class RemoteDownloadTask {

    private static final DateTimeFormatter LABEL_FORMATTER =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss").withZone(ZoneId.systemDefault());

    private final String id;
    private final String sourceUrl;
    private final String targetDirectory;
    private final NetworkRoute networkRoute;
    private final String actor;
    private final String ip;
    private final int requestedConnections;
    private final Instant createdAt;
    private volatile RemoteDownloadStatus status = RemoteDownloadStatus.QUEUED;
    private volatile Instant startedAt;
    private volatile Instant finishedAt;
    private final AtomicLong downloadedBytes = new AtomicLong();
    private final AtomicInteger retryCount = new AtomicInteger();
    private volatile long totalBytes = -1L;
    private volatile int actualConnections;
    private volatile String fileName;
    private volatile String targetPath;
    private volatile String pendingDecisionId;
    private volatile String message = "Waiting to start.";
    private volatile boolean cancelRequested;

    public RemoteDownloadTask(
            String id,
            String sourceUrl,
            String targetDirectory,
            NetworkRoute networkRoute,
            String actor,
            String ip,
            int requestedConnections
    ) {
        this.id = id;
        this.sourceUrl = sourceUrl;
        this.targetDirectory = targetDirectory == null ? "" : targetDirectory;
        this.networkRoute = networkRoute;
        this.actor = actor == null || actor.isBlank() ? "system" : actor;
        this.ip = ip == null || ip.isBlank() ? "-" : ip;
        this.requestedConnections = requestedConnections;
        this.createdAt = Instant.now();
    }

    public RemoteDownloadTask(
            String id,
            String sourceUrl,
            String targetDirectory,
            NetworkRoute networkRoute,
            String actor,
            String ip
    ) {
        this(id, sourceUrl, targetDirectory, networkRoute, actor, ip, 1);
    }

    public String id() {
        return id;
    }

    public String shortId() {
        return id.length() <= 8 ? id : id.substring(0, 8);
    }

    public String sourceUrl() {
        return sourceUrl;
    }

    public String targetDirectory() {
        return targetDirectory;
    }

    public NetworkRoute networkRoute() {
        return networkRoute;
    }

    public String networkRouteLabel() {
        return networkRoute.label();
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

    public RemoteDownloadStatus status() {
        return status;
    }

    public String statusLabel() {
        return status.label();
    }

    public String statusClass() {
        return switch (status) {
            case COMPLETE -> "active";
            case PENDING -> "warning";
            case FAILED, CANCELED -> "revoked";
            case QUEUED, RUNNING -> "expired";
        };
    }

    public long downloadedBytes() {
        return downloadedBytes.get();
    }

    public long totalBytes() {
        return totalBytes;
    }

    public int requestedConnections() {
        return requestedConnections;
    }

    public int actualConnections() {
        return actualConnections;
    }

    public int retryCount() {
        return retryCount.get();
    }

    public String fileName() {
        return fileName;
    }

    public String targetPath() {
        return targetPath;
    }

    public String pendingDecisionId() {
        return pendingDecisionId;
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

    public int progressPercent() {
        if (totalBytes <= 0L) {
            return status == RemoteDownloadStatus.PENDING || status == RemoteDownloadStatus.COMPLETE ? 100 : 0;
        }
        return (int) Math.max(0L, Math.min(100L, Math.round((double) downloadedBytes() * 100.0 / totalBytes)));
    }

    public String progressLabel() {
        if (totalBytes <= 0L) {
            return ByteSizeFormatter.humanSize(downloadedBytes());
        }
        return "%s / %s".formatted(ByteSizeFormatter.humanSize(downloadedBytes()), ByteSizeFormatter.humanSize(totalBytes));
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
        this.status = RemoteDownloadStatus.RUNNING;
        this.startedAt = Instant.now();
        this.message = "Downloading.";
    }

    void setTotalBytes(long totalBytes) {
        this.totalBytes = totalBytes;
    }

    void setActualConnections(int actualConnections) {
        this.actualConnections = Math.max(1, actualConnections);
    }

    void setFileName(String fileName) {
        this.fileName = fileName;
    }

    void addDownloadedBytes(long bytes) {
        downloadedBytes.updateAndGet(current -> Math.max(0L, current + bytes));
    }

    void resetDownloadedBytes() {
        downloadedBytes.set(0L);
    }

    void recordRetry() {
        retryCount.incrementAndGet();
        this.message = "Retrying download.";
    }

    void markDownloading() {
        this.message = actualConnections > 1
                ? "Downloading with " + actualConnections + " connections."
                : "Downloading.";
    }

    void markComplete(String fileName, String targetPath) {
        this.status = RemoteDownloadStatus.COMPLETE;
        this.fileName = fileName;
        this.targetPath = targetPath;
        this.finishedAt = Instant.now();
        this.cancelRequested = false;
        this.message = "Saved to " + targetPath;
    }

    void markPending(String fileName, String targetPath, String pendingDecisionId) {
        this.status = RemoteDownloadStatus.PENDING;
        this.fileName = fileName;
        this.targetPath = targetPath;
        this.pendingDecisionId = pendingDecisionId;
        this.finishedAt = Instant.now();
        this.cancelRequested = false;
        this.message = "Download complete. A file name conflict needs review.";
    }

    void resolvePending(boolean discarded, String committedPath, String committedName) {
        if (status != RemoteDownloadStatus.PENDING) {
            return;
        }
        pendingDecisionId = null;
        if (discarded) {
            markCanceled("Pending download discarded.");
            return;
        }
        markComplete(committedName, committedPath);
    }

    void markFailed(String message) {
        this.status = RemoteDownloadStatus.FAILED;
        this.finishedAt = Instant.now();
        this.message = message == null || message.isBlank() ? "Remote download failed." : message;
    }

    boolean requestCancel() {
        if (!active()) {
            return false;
        }
        this.cancelRequested = true;
        this.message = "Canceling.";
        return true;
    }

    void markCanceled(String message) {
        this.status = RemoteDownloadStatus.CANCELED;
        this.finishedAt = Instant.now();
        this.message = message == null || message.isBlank() ? "Canceled." : message;
    }
}
