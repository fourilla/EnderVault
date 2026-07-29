package io.github.fourilla.endervault.remote;

import io.github.fourilla.endervault.activity.ActivityLogService;
import io.github.fourilla.endervault.auth.ClientIpResolver;
import io.github.fourilla.endervault.common.StorageAccessException;
import io.github.fourilla.endervault.config.NasProperties;
import io.github.fourilla.endervault.outbound.NetworkRoute;
import io.github.fourilla.endervault.outbound.OutboundHttpClientRegistry;
import io.github.fourilla.endervault.storage.FileItem;
import io.github.fourilla.endervault.storage.StorageService;
import jakarta.annotation.PreDestroy;
import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.Principal;
import java.time.Duration;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.springframework.http.ContentDisposition;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

@Service
public class RemoteDownloadService {

    private static final int BUFFER_SIZE = 64 * 1024;
    private static final DateTimeFormatter FALLBACK_NAME_FORMATTER =
            DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss").withZone(java.time.ZoneId.systemDefault());

    private final NasProperties nasProperties;
    private final StorageService storageService;
    private final ActivityLogService activityLogService;
    private final ClientIpResolver clientIpResolver;
    private final RemoteDownloadValidator validator;
    private final ExecutorService executorService;
    private final HttpClient httpClient;
    private final Map<String, RemoteDownloadTask> tasks = new ConcurrentHashMap<>();
    private final Map<String, Future<?>> taskFutures = new ConcurrentHashMap<>();

    public RemoteDownloadService(
            NasProperties nasProperties,
            StorageService storageService,
            ActivityLogService activityLogService,
            ClientIpResolver clientIpResolver,
            RemoteDownloadValidator validator,
            OutboundHttpClientRegistry httpClientRegistry
    ) {
        this.nasProperties = nasProperties;
        this.storageService = storageService;
        this.activityLogService = activityLogService;
        this.clientIpResolver = clientIpResolver;
        this.validator = validator;
        this.executorService = Executors.newFixedThreadPool(nasProperties.getRemoteDownload().getWorkerThreads());
        this.httpClient = httpClientRegistry.client(
                NetworkRoute.DIRECT,
                Duration.ofSeconds(nasProperties.getRemoteDownload().getConnectTimeoutSeconds())
        );
    }

    public RemoteDownloadTask start(String rawUrl, String targetDirectory, HttpServletRequest request)
            throws IOException {
        NasProperties.RemoteDownload properties = nasProperties.getRemoteDownload();
        if (!properties.isEnabled() || !properties.isDirectEnabled()) {
            throw new StorageAccessException("Direct remote download is disabled.");
        }

        URI sourceUri = validator.validate(rawUrl);
        String safeTargetDirectory = targetDirectory == null ? "" : targetDirectory.trim();
        storageService.ensureVaultDirectory(safeTargetDirectory);

        RemoteDownloadTask task = new RemoteDownloadTask(
                UUID.randomUUID().toString(),
                sourceUri.toString(),
                safeTargetDirectory,
                actor(request),
                clientIpResolver.resolve(request)
        );
        tasks.put(task.id(), task);
        trimHistory();
        activityLogService.record(
                "REMOTE_DOWNLOAD_QUEUED",
                request,
                null,
                safeTargetDirectory,
                "Queued remote download.",
                Map.of("url", task.sourceUrl(), "taskId", task.id())
        );
        Future<?> future = executorService.submit(() -> runDownload(task));
        taskFutures.put(task.id(), future);
        return task;
    }

    public RemoteDownloadProbe inspect(String rawUrl, String targetDirectory) throws IOException {
        NasProperties.RemoteDownload properties = nasProperties.getRemoteDownload();
        if (!properties.isEnabled() || !properties.isDirectEnabled()) {
            throw new StorageAccessException("Direct remote download is disabled.");
        }

        URI sourceUri = validator.validate(rawUrl);
        String safeTargetDirectory = targetDirectory == null ? "" : targetDirectory.trim();
        storageService.ensureVaultDirectory(safeTargetDirectory);

        try (RemoteHttpResponse remoteResponse = openMetadataResponse(sourceUri)) {
            String fileName = fileNameFor(remoteResponse);
            String targetPath = targetPath(safeTargetDirectory, fileName);
            return new RemoteDownloadProbe(
                    sourceUri.toString(),
                    remoteResponse.uri().toString(),
                    safeTargetDirectory,
                    fileName,
                    targetPath,
                    remoteResponse.contentType(),
                    remoteResponse.contentLength()
            );
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new StorageAccessException("Remote file inspection was interrupted.");
        }
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

    @PreDestroy
    public void shutdown() {
        executorService.shutdownNow();
    }

    private void runDownload(RemoteDownloadTask task) {
        Path temporaryFile = null;
        try {
            throwIfCanceled(task);
            task.markRunning();
            throwIfCanceled(task);
            temporaryFile = storageService.createUploadTemporaryFile("remote-download-", ".tmp");
            try (RemoteHttpResponse remoteResponse = openDownloadResponse(URI.create(task.sourceUrl()));
                    InputStream inputStream = remoteResponse.body();
                    OutputStream outputStream = Files.newOutputStream(
                            temporaryFile,
                            StandardOpenOption.TRUNCATE_EXISTING,
                            StandardOpenOption.WRITE
                    )) {
                long contentLength = remoteResponse.contentLength();
                task.setTotalBytes(contentLength);
                enforceMaxSize(contentLength);

                byte[] buffer = new byte[BUFFER_SIZE];
                int read;
                while ((read = inputStream.read(buffer)) != -1) {
                    throwIfCanceled(task);
                    outputStream.write(buffer, 0, read);
                    task.addDownloadedBytes(read);
                    enforceMaxSize(task.downloadedBytes());
                }

                throwIfCanceled(task);
                String fileName = fileNameFor(remoteResponse);
                FileItem item = storageService.moveTemporaryFileIntoVault(
                        temporaryFile,
                        task.targetDirectory(),
                        fileName
                );
                temporaryFile = null;
                task.markComplete(item.name(), item.path());
                recordFinished(task, true, "Remote download completed.");
            }
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
            if (temporaryFile != null) {
                try {
                    Files.deleteIfExists(temporaryFile);
                } catch (IOException ignored) {
                    // Best effort cleanup. The upload temp directory can be cleaned later.
                }
            }
            taskFutures.remove(task.id());
        }
    }

    private RemoteHttpResponse openDownloadResponse(URI sourceUri) throws IOException, InterruptedException {
        return openRemoteResponse(sourceUri, "GET", false);
    }

    private RemoteHttpResponse openMetadataResponse(URI sourceUri) throws IOException, InterruptedException {
        try {
            return openRemoteResponse(sourceUri, "HEAD", false);
        } catch (RemoteHttpStatusException ex) {
            if (ex.status() != 403 && ex.status() != 405) {
                throw ex;
            }
            return openRemoteResponse(sourceUri, "GET", true);
        }
    }

    private RemoteHttpResponse openRemoteResponse(URI sourceUri, String method, boolean previewRange)
            throws IOException, InterruptedException {
        URI current = validator.validate(sourceUri);
        int maxRedirects = nasProperties.getRemoteDownload().getMaxRedirects();
        for (int redirectCount = 0; redirectCount <= maxRedirects; redirectCount++) {
            HttpRequest.Builder requestBuilder = HttpRequest.newBuilder(current)
                    .timeout(Duration.ofSeconds(nasProperties.getRemoteDownload().getResponseTimeoutSeconds()))
                    .header("User-Agent", "EnderVault RemoteDownload");
            if (previewRange) {
                requestBuilder.header("Range", "bytes=0-0");
            }
            HttpRequest request = "HEAD".equals(method)
                    ? requestBuilder.method("HEAD", HttpRequest.BodyPublishers.noBody()).build()
                    : requestBuilder.GET().build();
            HttpResponse<InputStream> response = httpClient.send(request, HttpResponse.BodyHandlers.ofInputStream());
            int status = response.statusCode();
            if (isRedirect(status)) {
                closeQuietly(response.body());
                String location = response.headers().firstValue("Location")
                        .orElseThrow(() -> new StorageAccessException("Remote server returned a redirect without Location."));
                current = validator.validateRedirect(current, location);
                continue;
            }
            if (status < 200 || status >= 300) {
                closeQuietly(response.body());
                throw new RemoteHttpStatusException(status);
            }
            return new RemoteHttpResponse(current, response);
        }
        throw new StorageAccessException("Remote download exceeded the redirect limit.");
    }

    private boolean isRedirect(int status) {
        return status == 301 || status == 302 || status == 303 || status == 307 || status == 308;
    }

    private void enforceMaxSize(long bytes) {
        long maxFileSize = nasProperties.getRemoteDownload().getMaxFileSizeBytes();
        if (maxFileSize > 0L && bytes > maxFileSize) {
            throw new StorageAccessException("Remote file exceeds the configured size limit.");
        }
    }

    private String fileNameFor(RemoteHttpResponse remoteResponse) {
        String headerName = remoteResponse.contentDispositionFileName();
        if (StringUtils.hasText(headerName)) {
            return cleanFileName(headerName);
        }

        String path = remoteResponse.uri().getPath();
        int slashIndex = path == null ? -1 : path.lastIndexOf('/');
        String lastSegment = slashIndex < 0 ? path : path.substring(slashIndex + 1);
        if (StringUtils.hasText(lastSegment)) {
            return withExtensionFromContentType(cleanFileName(decodeUrlSegment(lastSegment)), remoteResponse.contentType());
        }
        return withExtensionFromContentType(
                "remote-download-" + FALLBACK_NAME_FORMATTER.format(Instant.now()),
                remoteResponse.contentType()
        );
    }

    private String decodeUrlSegment(String value) {
        try {
            return java.net.URLDecoder.decode(value, StandardCharsets.UTF_8);
        } catch (IllegalArgumentException ex) {
            return value;
        }
    }

    private String cleanFileName(String rawName) {
        String cleaned = rawName == null ? "" : rawName.trim();
        cleaned = cleaned.replaceAll("[\\\\/:*?\"<>|\\p{Cntrl}]", "_");
        cleaned = cleaned.replaceAll("\\s+", " ");
        while (cleaned.endsWith(".") || cleaned.endsWith(" ")) {
            cleaned = cleaned.substring(0, cleaned.length() - 1);
        }
        if (!StringUtils.hasText(cleaned) || ".".equals(cleaned) || "..".equals(cleaned)) {
            return "remote-download-" + FALLBACK_NAME_FORMATTER.format(Instant.now());
        }
        return cleaned;
    }

    private String withExtensionFromContentType(String fileName, String contentType) {
        if (!StringUtils.hasText(fileName) || fileName.contains(".")) {
            return fileName;
        }
        String extension = extensionForContentType(contentType);
        return StringUtils.hasText(extension) ? fileName + "." + extension : fileName;
    }

    private String extensionForContentType(String contentType) {
        if (!StringUtils.hasText(contentType)) {
            return "";
        }
        String normalized = contentType.toLowerCase().split(";", 2)[0].trim();
        return switch (normalized) {
            case "video/mp4" -> "mp4";
            case "video/webm" -> "webm";
            case "video/x-matroska" -> "mkv";
            case "image/jpeg" -> "jpg";
            case "image/png" -> "png";
            case "image/gif" -> "gif";
            case "image/webp" -> "webp";
            case "text/plain" -> "txt";
            case "text/html" -> "html";
            case "application/pdf" -> "pdf";
            case "application/zip" -> "zip";
            case "application/json" -> "json";
            default -> "";
        };
    }

    private String targetPath(String directory, String fileName) {
        if (!StringUtils.hasText(directory)) {
            return fileName;
        }
        return directory.replace('\\', '/').replaceAll("/+$", "") + "/" + fileName;
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
            recordCanceled(task);
        }
    }

    private void recordCanceled(RemoteDownloadTask task) {
        recordWithType("REMOTE_DOWNLOAD_CANCELED", task, false, "Remote download canceled.");
    }

    private void recordWithType(String type, RemoteDownloadTask task, boolean success, String message) {
        Map<String, String> metadata = new LinkedHashMap<>();
        metadata.put("url", task.sourceUrl());
        metadata.put("taskId", task.id());
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
        if (request == null) {
            return "system";
        }
        Principal principal = request.getUserPrincipal();
        return principal == null ? "anonymous" : principal.getName();
    }

    private String cleanMessage(Exception ex) {
        String message = ex.getMessage();
        return message == null || message.isBlank() ? "Remote download failed." : message;
    }

    private void closeQuietly(InputStream inputStream) {
        try {
            inputStream.close();
        } catch (IOException ignored) {
            // Ignore close failure while handling an HTTP response.
        }
    }

    private record RemoteHttpResponse(URI uri, HttpResponse<InputStream> response) implements AutoCloseable {
        InputStream body() {
            return response.body();
        }

        long contentLength() {
            return response.headers().firstValueAsLong("Content-Length").orElse(-1L);
        }

        String contentDispositionFileName() {
            return response.headers().firstValue("Content-Disposition")
                    .map(this::parseContentDispositionFileName)
                    .orElse(null);
        }

        String contentType() {
            return response.headers().firstValue("Content-Type").orElse("");
        }

        private String parseContentDispositionFileName(String value) {
            try {
                return ContentDisposition.parse(value).getFilename();
            } catch (IllegalArgumentException ex) {
                return null;
            }
        }

        @Override
        public void close() throws IOException {
            response.body().close();
        }
    }

    private static class RemoteDownloadCanceledException extends RuntimeException {
    }

    private static class RemoteHttpStatusException extends StorageAccessException {

        private final int status;

        RemoteHttpStatusException(int status) {
            super("Remote server returned HTTP " + status + ".");
            this.status = status;
        }

        int status() {
            return status;
        }
    }
}
