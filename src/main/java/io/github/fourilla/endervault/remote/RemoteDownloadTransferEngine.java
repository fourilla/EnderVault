package io.github.fourilla.endervault.remote;

import io.github.fourilla.endervault.common.StorageAccessException;
import io.github.fourilla.endervault.config.NasProperties;
import io.github.fourilla.endervault.storage.StorageService;
import io.github.fourilla.endervault.temporary.TemporaryArtifactRegistry;
import io.github.fourilla.endervault.temporary.TemporaryArtifactType;
import jakarta.annotation.PreDestroy;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.FutureTask;
import java.util.concurrent.atomic.AtomicBoolean;
import org.springframework.stereotype.Component;

@Component
public class RemoteDownloadTransferEngine {

    private static final int BUFFER_SIZE = 64 * 1024;

    private final NasProperties nasProperties;
    private final StorageService storageService;
    private final RemoteDownloadHttpClient httpClient;
    private final RemoteDownloadFileNameResolver fileNameResolver;
    private final RemoteDownloadRetryPolicy retryPolicy;
    private final TemporaryArtifactRegistry temporaryArtifactRegistry;
    private final ExecutorService segmentExecutor;

    public RemoteDownloadTransferEngine(
            NasProperties nasProperties,
            StorageService storageService,
            RemoteDownloadHttpClient httpClient,
            RemoteDownloadFileNameResolver fileNameResolver,
            RemoteDownloadRetryPolicy retryPolicy,
            TemporaryArtifactRegistry temporaryArtifactRegistry
    ) {
        this.nasProperties = nasProperties;
        this.storageService = storageService;
        this.httpClient = httpClient;
        this.fileNameResolver = fileNameResolver;
        this.retryPolicy = retryPolicy;
        this.temporaryArtifactRegistry = temporaryArtifactRegistry;
        int segmentWorkers = nasProperties.getRemoteDownload().getWorkerThreads()
                * RemoteDownloadRequestParser.MAX_CONNECTIONS;
        this.segmentExecutor = Executors.newFixedThreadPool(segmentWorkers);
    }

    RemoteDownloadTransferResult transfer(
            RemoteDownloadTask task,
            RemoteDownloadRequestSpec requestSpec
    ) throws Exception {
        Path temporaryFile = storageService.createUploadTemporaryFile("remote-download-", ".tmp");
        TemporaryArtifactRegistry.Registration registration = temporaryArtifactRegistry.register(
                temporaryFile,
                TemporaryArtifactType.REMOTE_DOWNLOAD,
                task.id()
        );
        boolean committed = false;
        try {
            String fileName = requestSpec.requestedConnections() == 1
                    ? downloadSingle(task, requestSpec, temporaryFile)
                    : downloadParallel(task, requestSpec, temporaryFile);
            throwIfCanceled(task);
            StorageService.CommittedVaultFile committedFile = storageService.commitTemporaryFileIntoVault(
                    temporaryFile,
                    requestSpec.targetDirectory(),
                    fileName,
                    requestSpec.conflictPolicy()
            );
            committed = true;
            return new RemoteDownloadTransferResult(committedFile.name(), committedFile.path());
        } finally {
            if (!committed) {
                try {
                    Files.deleteIfExists(temporaryFile);
                } catch (IOException ignored) {
                    // Metadata Inspector can remove a staging file that could not be deleted here.
                }
            }
            registration.close();
        }
    }

    private String downloadSingle(
            RemoteDownloadTask task,
            RemoteDownloadRequestSpec requestSpec,
            Path temporaryFile
    ) throws Exception {
        task.setActualConnections(1);
        for (int attempt = 0; attempt <= retryPolicy.maxRetries(); attempt++) {
            throwIfCanceled(task);
            task.resetDownloadedBytes();
            try (RemoteHttpResponse response = exchange(requestSpec, null)) {
                requireSuccessfulStatus(response);
                long contentLength = response.contentLength();
                enforceMaxSize(contentLength);
                task.setTotalBytes(contentLength);
                String fileName = fileNameResolver.fileName(response);
                task.setFileName(fileName);
                task.markDownloading();
                try (OutputStream outputStream = Files.newOutputStream(
                        temporaryFile,
                        StandardOpenOption.TRUNCATE_EXISTING,
                        StandardOpenOption.WRITE
                )) {
                    copySingleBody(task, response.body(), outputStream);
                }
                if (contentLength >= 0L && task.downloadedBytes() != contentLength) {
                    throw new RetryableRemoteDownloadException("Remote response ended before the expected file size.");
                }
                return fileName;
            } catch (RetryableRemoteDownloadException ex) {
                if (attempt >= retryPolicy.maxRetries()) {
                    throw new StorageAccessException(ex.getMessage(), ex);
                }
                retry(task, attempt + 1);
            }
        }
        throw new StorageAccessException("Remote download failed after retrying.");
    }

    private String downloadParallel(
            RemoteDownloadTask task,
            RemoteDownloadRequestSpec requestSpec,
            Path temporaryFile
    ) throws Exception {
        RemoteResourceIdentity identity;
        String fileName;
        try (RemoteHttpResponse preflight = rangeResponseWithRetry(
                task,
                requestSpec,
                new RemoteByteRange(0L, 0L, "")
        )) {
            if (preflight.status() != 206) {
                throw new StorageAccessException(
                        "Parallel download requires a valid HTTP 206 Range response. Use 1 connection for this server."
                );
            }
            RemoteContentRange contentRange = preflight.contentRange();
            if (contentRange == null || contentRange.start() != 0L || contentRange.end() != 0L) {
                throw new StorageAccessException("Remote server returned an invalid Content-Range response.");
            }
            enforceMaxSize(contentRange.total());
            if (contentRange.total() <= 0L) {
                throw new StorageAccessException("Parallel download cannot be used for an empty resource.");
            }
            identity = new RemoteResourceIdentity(
                    contentRange.total(),
                    preflight.strongEtag(),
                    preflight.lastModified()
            );
            fileName = fileNameResolver.fileName(preflight);
        }

        int actualConnections = (int) Math.min(requestSpec.requestedConnections(), identity.totalBytes());
        task.setActualConnections(actualConnections);
        task.setTotalBytes(identity.totalBytes());
        task.setFileName(fileName);
        task.resetDownloadedBytes();
        task.markDownloading();
        allocateFile(temporaryFile, identity.totalBytes());

        List<RemoteByteRange> ranges = splitRanges(identity.totalBytes(), actualConnections, identity.ifRange());
        CountDownLatch segmentCompletion = new CountDownLatch(ranges.size());
        List<SegmentFutureTask> futures = ranges.stream()
                .map(range -> new SegmentFutureTask(
                        () -> downloadSegment(task, requestSpec, temporaryFile, range, identity),
                        segmentCompletion
                ))
                .toList();
        boolean completed = false;
        try {
            futures.forEach(segmentExecutor::execute);
            for (SegmentFutureTask future : futures) {
                future.get();
            }
            completed = true;
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw ex;
        } catch (ExecutionException ex) {
            Throwable cause = ex.getCause();
            if (cause instanceof SegmentTransferException segment && segment.getCause() instanceof Exception nested) {
                throw nested;
            }
            throw new StorageAccessException("Parallel remote download failed.", cause);
        } finally {
            if (!completed) {
                cancelAll(futures);
            }
            awaitSegmentCompletion(segmentCompletion);
        }
        return fileName;
    }

    private void downloadSegment(
            RemoteDownloadTask task,
            RemoteDownloadRequestSpec requestSpec,
            Path temporaryFile,
            RemoteByteRange range,
            RemoteResourceIdentity identity
    ) throws Exception {
        for (int attempt = 0; attempt <= retryPolicy.maxRetries(); attempt++) {
            throwIfCanceled(task);
            SegmentProgress progress = new SegmentProgress();
            try (RemoteHttpResponse response = exchange(requestSpec, range)) {
                if (retryPolicy.retryableStatus(response.status())) {
                    throw new RetryableRemoteDownloadException(
                            "Remote server returned HTTP " + response.status() + "."
                    );
                }
                if (response.status() != 206) {
                    throw new StorageAccessException(
                            "Remote server stopped honoring Range requests during the download."
                    );
                }
                RemoteContentRange contentRange = response.contentRange();
                if (contentRange == null || !contentRange.matches(range, identity.totalBytes())) {
                    throw new StorageAccessException("Remote server returned a mismatched Content-Range response.");
                }
                if (!identity.matches(response)) {
                    throw new StorageAccessException("Remote file changed while it was being downloaded.");
                }
                if (response.contentLength() >= 0L && response.contentLength() != range.length()) {
                    throw new StorageAccessException("Remote range length does not match the requested segment.");
                }
                writeSegment(task, response.body(), temporaryFile, range, progress);
                if (progress.bytes != range.length()) {
                    throw new RetryableRemoteDownloadException(
                            "Remote range ended before the requested segment was complete."
                    );
                }
                return;
            } catch (RetryableRemoteDownloadException ex) {
                task.addDownloadedBytes(-progress.bytes);
                if (attempt >= retryPolicy.maxRetries()) {
                    throw new StorageAccessException(ex.getMessage(), ex);
                }
                retry(task, attempt + 1);
            }
        }
    }

    private void writeSegment(
            RemoteDownloadTask task,
            InputStream inputStream,
            Path temporaryFile,
            RemoteByteRange range,
            SegmentProgress progress
    ) throws IOException {
        byte[] buffer = new byte[BUFFER_SIZE];
        try (FileChannel channel = FileChannel.open(temporaryFile, StandardOpenOption.WRITE)) {
            while (progress.bytes < range.length()) {
                throwIfCanceled(task);
                int limit = (int) Math.min(buffer.length, range.length() - progress.bytes);
                int read;
                try {
                    read = inputStream.read(buffer, 0, limit);
                } catch (IOException ex) {
                    throw new RetryableRemoteDownloadException("Remote segment connection was interrupted.", ex);
                }
                if (read < 0) {
                    break;
                }
                ByteBuffer byteBuffer = ByteBuffer.wrap(buffer, 0, read);
                long position = range.start() + progress.bytes;
                while (byteBuffer.hasRemaining()) {
                    int count = channel.write(byteBuffer, position);
                    if (count <= 0) {
                        throw new IOException("Failed to write the remote segment to temporary storage.");
                    }
                    position += count;
                }
                progress.bytes += read;
                task.addDownloadedBytes(read);
                enforceMaxSize(task.downloadedBytes());
            }
        }
    }

    private void copySingleBody(
            RemoteDownloadTask task,
            InputStream inputStream,
            OutputStream outputStream
    ) throws IOException {
        byte[] buffer = new byte[BUFFER_SIZE];
        while (true) {
            throwIfCanceled(task);
            int read;
            try {
                read = inputStream.read(buffer);
            } catch (IOException ex) {
                throw new RetryableRemoteDownloadException("Remote download connection was interrupted.", ex);
            }
            if (read < 0) {
                return;
            }
            outputStream.write(buffer, 0, read);
            task.addDownloadedBytes(read);
            enforceMaxSize(task.downloadedBytes());
        }
    }

    private RemoteHttpResponse rangeResponseWithRetry(
            RemoteDownloadTask task,
            RemoteDownloadRequestSpec requestSpec,
            RemoteByteRange range
    ) throws Exception {
        for (int attempt = 0; attempt <= retryPolicy.maxRetries(); attempt++) {
            try {
                RemoteHttpResponse response = exchange(requestSpec, range);
                if (!retryPolicy.retryableStatus(response.status())) {
                    return response;
                }
                String message = "Remote server returned HTTP " + response.status() + ".";
                response.close();
                if (attempt >= retryPolicy.maxRetries()) {
                    throw new StorageAccessException(message);
                }
            } catch (RetryableRemoteDownloadException ex) {
                if (attempt >= retryPolicy.maxRetries()) {
                    throw new StorageAccessException(ex.getMessage(), ex);
                }
            }
            retry(task, attempt + 1);
        }
        throw new StorageAccessException("Remote range inspection failed after retrying.");
    }

    private RemoteHttpResponse exchange(RemoteDownloadRequestSpec requestSpec, RemoteByteRange range)
            throws RetryableRemoteDownloadException, InterruptedException {
        try {
            return httpClient.exchange(requestSpec, range);
        } catch (IOException ex) {
            throw new RetryableRemoteDownloadException("Remote connection failed.", ex);
        }
    }

    private void requireSuccessfulStatus(RemoteHttpResponse response) throws RetryableRemoteDownloadException {
        if (response.status() >= 200 && response.status() < 300) {
            return;
        }
        if (retryPolicy.retryableStatus(response.status())) {
            throw new RetryableRemoteDownloadException("Remote server returned HTTP " + response.status() + ".");
        }
        throw new RemoteDownloadHttpStatusException(response.status());
    }

    private void retry(RemoteDownloadTask task, int retryNumber) throws InterruptedException {
        task.recordRetry();
        retryPolicy.awaitRetry(retryNumber, () -> throwIfCanceled(task));
        task.markDownloading();
    }

    private List<RemoteByteRange> splitRanges(long totalBytes, int connections, String ifRange) {
        List<RemoteByteRange> ranges = new ArrayList<>(connections);
        long baseLength = totalBytes / connections;
        long remainder = totalBytes % connections;
        long start = 0L;
        for (int index = 0; index < connections; index++) {
            long length = baseLength + (index < remainder ? 1L : 0L);
            long end = start + length - 1L;
            ranges.add(new RemoteByteRange(start, end, ifRange));
            start = end + 1L;
        }
        return ranges;
    }

    private void allocateFile(Path temporaryFile, long size) throws IOException {
        try (FileChannel channel = FileChannel.open(
                temporaryFile,
                StandardOpenOption.WRITE,
                StandardOpenOption.TRUNCATE_EXISTING
        )) {
            channel.position(size - 1L);
            channel.write(ByteBuffer.wrap(new byte[]{0}));
        }
    }

    private void cancelAll(List<? extends Future<?>> futures) {
        futures.forEach(future -> future.cancel(true));
    }

    private void awaitSegmentCompletion(CountDownLatch completion) {
        boolean interrupted = false;
        while (completion.getCount() > 0L) {
            try {
                completion.await();
            } catch (InterruptedException ex) {
                interrupted = true;
            }
        }
        if (interrupted) {
            Thread.currentThread().interrupt();
        }
    }

    private void enforceMaxSize(long bytes) {
        long maxFileSize = nasProperties.getRemoteDownload().getMaxFileSizeBytes();
        if (maxFileSize > 0L && bytes > maxFileSize) {
            throw new StorageAccessException("Remote file exceeds the configured size limit.");
        }
    }

    private void throwIfCanceled(RemoteDownloadTask task) {
        if (task.cancelRequested() || Thread.currentThread().isInterrupted()) {
            throw new RemoteDownloadCanceledException();
        }
    }

    @PreDestroy
    public void shutdown() {
        segmentExecutor.shutdownNow();
    }

    private static final class SegmentTransferException extends RuntimeException {
        private SegmentTransferException(Throwable cause) {
            super(cause);
        }
    }

    private static final class SegmentFutureTask extends FutureTask<Void> {

        private final CountDownLatch completion;
        private final AtomicBoolean runEntered = new AtomicBoolean();
        private final AtomicBoolean completionSignaled = new AtomicBoolean();

        private SegmentFutureTask(ThrowingRunnable transfer, CountDownLatch completion) {
            super(() -> {
                try {
                    transfer.run();
                    return null;
                } catch (Exception ex) {
                    throw new SegmentTransferException(ex);
                }
            });
            this.completion = completion;
        }

        @Override
        public void run() {
            runEntered.set(true);
            try {
                super.run();
            } finally {
                signalCompletion();
            }
        }

        @Override
        protected void done() {
            if (!runEntered.get()) {
                signalCompletion();
            }
        }

        private void signalCompletion() {
            if (completionSignaled.compareAndSet(false, true)) {
                completion.countDown();
            }
        }
    }

    @FunctionalInterface
    private interface ThrowingRunnable {
        void run() throws Exception;
    }

    private static final class SegmentProgress {
        private long bytes;
    }
}
