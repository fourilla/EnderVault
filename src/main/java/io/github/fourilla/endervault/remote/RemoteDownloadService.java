package io.github.fourilla.endervault.remote;

import io.github.fourilla.endervault.activity.ActivityLogService;
import io.github.fourilla.endervault.auth.ClientIpResolver;
import io.github.fourilla.endervault.common.StorageAccessException;
import io.github.fourilla.endervault.config.NasProperties;
import io.github.fourilla.endervault.outbound.NetworkRoute;
import io.github.fourilla.endervault.storage.ConflictPolicy;
import io.github.fourilla.endervault.storage.StorageService;
import jakarta.annotation.PreDestroy;
import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Path;
import java.security.Principal;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

@Service
public class RemoteDownloadService {

    private final NasProperties nasProperties;
    private final StorageService storageService;
    private final ActivityLogService activityLogService;
    private final ClientIpResolver clientIpResolver;
    private final RemoteDownloadRequestParser requestParser;
    private final RemoteDownloadInspectionService inspectionService;
    private final RemoteDownloadRequestTicketService ticketService;
    private final RemoteDownloadTransferEngine transferEngine;
    private final ExecutorService executorService;
    private final Map<String, RemoteDownloadTask> tasks = new ConcurrentHashMap<>();
    private final Map<String, Future<?>> taskFutures = new ConcurrentHashMap<>();

    public RemoteDownloadService(
            NasProperties nasProperties,
            StorageService storageService,
            ActivityLogService activityLogService,
            ClientIpResolver clientIpResolver,
            RemoteDownloadRequestParser requestParser,
            RemoteDownloadInspectionService inspectionService,
            RemoteDownloadRequestTicketService ticketService,
            RemoteDownloadTransferEngine transferEngine
    ) {
        this.nasProperties = nasProperties;
        this.storageService = storageService;
        this.activityLogService = activityLogService;
        this.clientIpResolver = clientIpResolver;
        this.requestParser = requestParser;
        this.inspectionService = inspectionService;
        this.ticketService = ticketService;
        this.transferEngine = transferEngine;
        this.executorService = Executors.newFixedThreadPool(nasProperties.getRemoteDownload().getWorkerThreads());
    }

    public RemoteDownloadInspection inspect(
            String rawUrl,
            String targetDirectory,
            NetworkRoute networkRoute,
            int connections,
            String conflictPolicy,
            boolean skipInspection,
            String customHeaders,
            HttpServletRequest request
    ) throws IOException {
        requireEnabled();
        RemoteDownloadRequestSpec requestSpec = requestParser.parse(
                rawUrl,
                targetDirectory,
                networkRoute,
                connections,
                resolveConflictPolicy(conflictPolicy),
                skipInspection,
                customHeaders
        );
        storageService.ensureVaultDirectory(requestSpec.targetDirectory());
        try {
            RemoteDownloadProbe probe = requestSpec.inspectionSkipped()
                    ? inspectionService.skipped(requestSpec)
                    : inspectionService.inspect(requestSpec);
            String requestId = ticketService.issue(requestSpec, probe, request);
            return new RemoteDownloadInspection(requestId, probe);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new StorageAccessException("Remote file inspection was interrupted.");
        }
    }

    public RemoteDownloadTask start(String requestId, HttpServletRequest request) throws IOException {
        requireEnabled();
        RemoteDownloadPreparedRequest prepared = ticketService.consume(requestId, request);
        RemoteDownloadRequestSpec requestSpec = prepared.request();
        if (requestSpec.requestedConnections() > 1
                && prepared.probe().rangeCapability() == RemoteDownloadRangeCapability.UNSUPPORTED) {
            throw new StorageAccessException(
                    "The inspected server does not support parallel downloads. Use 1 connection."
            );
        }
        storageService.ensureVaultDirectory(requestSpec.targetDirectory());

        RemoteDownloadTask task = new RemoteDownloadTask(
                UUID.randomUUID().toString(),
                requestSpec.sourceLabel(),
                requestSpec.targetDirectory(),
                requestSpec.networkRoute(),
                actor(request),
                clientIpResolver.resolve(request),
                requestSpec.requestedConnections(),
                requestSpec.conflictPolicy()
        );
        tasks.put(task.id(), task);
        trimHistory();
        activityLogService.record(
                "REMOTE_DOWNLOAD_QUEUED",
                request,
                null,
                requestSpec.targetDirectory(),
                "Queued remote download.",
                Map.of(
                        "source", task.sourceUrl(),
                        "taskId", task.id(),
                        "networkRoute", task.networkRoute().settingValue(),
                        "requestedConnections", Integer.toString(task.requestedConnections()),
                        "conflictPolicy", task.conflictPolicy().value(),
                        "inspectionSkipped", Boolean.toString(requestSpec.inspectionSkipped()),
                        "customHeaderCount", Integer.toString(requestSpec.headerCount()),
                        "cookieIncluded", Boolean.toString(requestSpec.cookieIncluded())
                )
        );
        Future<?> future = executorService.submit(() -> runDownload(task, requestSpec));
        taskFutures.put(task.id(), future);
        return task;
    }

    public List<RemoteDownloadTask> listTasks() {
        return tasks.values().stream()
                .sorted(Comparator.comparing(RemoteDownloadTask::createdAt).reversed())
                .toList();
    }

    public RemoteDownloadSummary summary(int recentLimit) {
        List<RemoteDownloadTask> allTasks = listTasks();
        long running = allTasks.stream().filter(RemoteDownloadTask::active).count();
        long complete = allTasks.stream().filter(task -> task.status() == RemoteDownloadStatus.COMPLETE).count();
        long failed = allTasks.stream().filter(task -> task.status() == RemoteDownloadStatus.FAILED).count();
        return new RemoteDownloadSummary(
                allTasks.size(),
                running,
                complete,
                failed,
                allTasks.stream().limit(Math.max(0, recentLimit)).toList()
        );
    }

    public RemoteDownloadTask cancel(String taskId) {
        RemoteDownloadTask task = task(taskId);
        if (!task.requestCancel()) {
            throw new StorageAccessException("Only queued or running remote downloads can be canceled.");
        }

        Future<?> future = taskFutures.get(task.id());
        boolean futureCanceled = future != null && future.cancel(true);
        if (task.status() == RemoteDownloadStatus.QUEUED && futureCanceled) {
            markCanceledAndRecord(task, "Canceled before it started.");
            taskFutures.remove(task.id());
        }
        return task;
    }

    public void deleteTask(String taskId) {
        RemoteDownloadTask task = task(taskId);
        if (task.active()) {
            throw new StorageAccessException("Running remote download tasks cannot be deleted.");
        }
        tasks.remove(task.id());
        taskFutures.remove(task.id());
    }

    @PreDestroy
    public void shutdown() {
        executorService.shutdownNow();
    }

    private void runDownload(RemoteDownloadTask task, RemoteDownloadRequestSpec requestSpec) {
        try {
            throwIfCanceled(task);
            task.markRunning();
            throwIfCanceled(task);
            RemoteDownloadTransferResult result = transferEngine.transfer(task, requestSpec);
            throwIfCanceled(task);
            task.markComplete(result.fileName(), result.targetPath());
            recordFinished(task, true, "Remote download completed.");
        } catch (RemoteDownloadCanceledException ex) {
            markCanceledAndRecord(task, "Canceled.");
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            if (task.cancelRequested()) {
                markCanceledAndRecord(task, "Canceled.");
            } else {
                task.markFailed("Remote download was interrupted.");
                recordFinished(task, false, task.message());
            }
        } catch (Exception ex) {
            if (task.cancelRequested()) {
                markCanceledAndRecord(task, "Canceled.");
            } else {
                task.markFailed(cleanMessage(ex));
                recordFinished(task, false, task.message());
            }
        } finally {
            taskFutures.remove(task.id());
        }
    }

    private void requireEnabled() {
        NasProperties.RemoteDownload properties = nasProperties.getRemoteDownload();
        if (!properties.isEnabled() || !properties.isDirectEnabled()) {
            throw new StorageAccessException("Direct remote download is disabled.");
        }
    }

    private void trimHistory() {
        int historyLimit = nasProperties.getRemoteDownload().getHistoryLimit();
        List<RemoteDownloadTask> allTasks = listTasks();
        if (allTasks.size() <= historyLimit) {
            return;
        }
        allTasks.stream()
                .skip(historyLimit)
                .filter(task -> !task.active())
                .map(RemoteDownloadTask::id)
                .forEach(tasks::remove);
    }

    private RemoteDownloadTask task(String taskId) {
        RemoteDownloadTask task = tasks.get(taskId);
        if (task == null) {
            throw new StorageAccessException("Remote download task was not found.");
        }
        return task;
    }

    private void throwIfCanceled(RemoteDownloadTask task) {
        if (task.cancelRequested() || Thread.currentThread().isInterrupted()) {
            throw new RemoteDownloadCanceledException();
        }
    }

    private void recordFinished(RemoteDownloadTask task, boolean success, String message) {
        recordWithType(success ? "REMOTE_DOWNLOAD_COMPLETE" : "REMOTE_DOWNLOAD_FAILED", task, success, message);
    }

    private void markCanceledAndRecord(RemoteDownloadTask task, String message) {
        boolean alreadyCanceled = task.status() == RemoteDownloadStatus.CANCELED;
        task.markCanceled(message);
        if (!alreadyCanceled) {
            recordWithType("REMOTE_DOWNLOAD_CANCELED", task, false, "Remote download canceled.");
        }
    }

    private void recordWithType(String type, RemoteDownloadTask task, boolean success, String message) {
        Map<String, String> metadata = new LinkedHashMap<>();
        metadata.put("source", task.sourceUrl());
        metadata.put("taskId", task.id());
        metadata.put("networkRoute", task.networkRoute().settingValue());
        metadata.put("requestedConnections", Integer.toString(task.requestedConnections()));
        metadata.put("actualConnections", Integer.toString(task.actualConnections()));
        metadata.put("retryCount", Integer.toString(task.retryCount()));
        metadata.put("conflictPolicy", task.conflictPolicy().value());
        if (StringUtils.hasText(task.fileName())) {
            metadata.put("fileName", task.fileName());
        }
        activityLogService.record(
                type,
                task.actor(),
                task.ip(),
                task.targetPath(),
                task.targetDirectory(),
                success,
                message,
                metadata
        );
    }

    private String actor(HttpServletRequest request) {
        Principal principal = request == null ? null : request.getUserPrincipal();
        return principal == null ? "anonymous" : principal.getName();
    }

    private ConflictPolicy resolveConflictPolicy(String rawPolicy) {
        try {
            if (rawPolicy == null || rawPolicy.isBlank() || "default".equalsIgnoreCase(rawPolicy.trim())) {
                return storageService.defaultConflictPolicy();
            }
            return ConflictPolicy.from(rawPolicy);
        } catch (IllegalArgumentException ex) {
            throw new StorageAccessException("Conflict policy is invalid.", ex);
        }
    }

    private String cleanMessage(Exception ex) {
        if (ex instanceof FileAlreadyExistsException fileAlreadyExistsException) {
            String filename = cleanConflictFilename(fileAlreadyExistsException.getFile());
            return "A file named \"" + filename + "\" already exists in the target directory.";
        }
        String message = ex.getMessage();
        return message == null || message.isBlank() ? "Remote download failed." : message;
    }

    private String cleanConflictFilename(String rawPath) {
        if (!StringUtils.hasText(rawPath)) {
            return "the requested file";
        }
        try {
            Path filename = Path.of(rawPath).getFileName();
            return filename == null ? "the requested file" : filename.toString();
        } catch (RuntimeException ex) {
            return "the requested file";
        }
    }
}
