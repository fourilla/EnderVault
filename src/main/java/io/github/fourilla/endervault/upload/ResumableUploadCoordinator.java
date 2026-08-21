package io.github.fourilla.endervault.upload;

import io.github.fourilla.endervault.activity.ActivityLogService;
import io.github.fourilla.endervault.common.StorageAccessException;
import io.github.fourilla.endervault.config.NasProperties;
import io.github.fourilla.endervault.upload.ResumableUploadService.FinalizationResult;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.file.Path;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Semaphore;
import me.desair.tus.server.exception.TusException;
import me.desair.tus.server.upload.UploadInfo;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

@Service
public class ResumableUploadCoordinator {

    private static final Logger logger = LoggerFactory.getLogger(ResumableUploadCoordinator.class);
    private static final String TUS_RESUMABLE = "Tus-Resumable";
    private static final String UPLOAD_LENGTH = "Upload-Length";
    private static final String UPLOAD_METADATA = "Upload-Metadata";
    private static final String OFFSET_CONTENT_TYPE = "application/offset+octet-stream";

    private final ResumableUploadService uploadService;
    private final ResumableUploadProtocolService protocolService;
    private final ActivityLogService activityLogService;
    private final Semaphore globalChunkPermits;
    private final int fileRequestChunkLimit;
    private final Map<String, Semaphore> fileRequestChunkPermits = new ConcurrentHashMap<>();

    public ResumableUploadCoordinator(
            ResumableUploadService uploadService,
            ResumableUploadProtocolService protocolService,
            ActivityLogService activityLogService,
            NasProperties nasProperties
    ) {
        this.uploadService = uploadService;
        this.protocolService = protocolService;
        this.activityLogService = activityLogService;
        this.globalChunkPermits = new Semaphore(nasProperties.getUpload().getMaxConcurrentChunks(), true);
        this.fileRequestChunkLimit = nasProperties.getFileRequest().getMaxConcurrentUploadsPerRequest();
    }

    public void process(
            String sessionId,
            HttpServletRequest request,
            HttpServletResponse response
    ) throws IOException {
        String method = request.getMethod();
        if ("POST".equals(method)) {
            processCreation(sessionId, request, response);
            return;
        }
        ResumableUploadSession session = "DELETE".equals(method)
                ? uploadService.authorizeCancellation(sessionId, request.isUserInRole("ADMIN"))
                : uploadService.authorizeProtocolAccess(sessionId, request.isUserInRole("ADMIN"));
        validateProtocolRequest(session, request);

        try (ChunkPermit ignored = acquireChunkPermit(session, method)) {
            try {
                protocolService.process(normalizePatchContentType(request), response, session.id());
            } catch (IOException ex) {
                throw publicUploadFailure(session, "protocol processing", ex);
            }
        }

        if (response.getStatus() >= 400) {
            return;
        }
        if ("DELETE".equals(method)) {
            uploadService.cancel(session.id());
            return;
        }
        if (session.protocolUploadUri() != null) {
            try {
                tryFinalize(session.id(), request);
            } catch (IOException | StorageAccessException ex) {
                throw publicUploadFailure(session, "finalization", ex);
            }
        }
    }

    private synchronized void processCreation(
            String sessionId,
            HttpServletRequest request,
            HttpServletResponse response
    ) throws IOException {
        ResumableUploadSession session = uploadService.authorizeProtocolAccess(
                sessionId, request.isUserInRole("ADMIN")
        );
        validateProtocolRequest(session, request);
        try {
            protocolService.process(request, response, session.id());
        } catch (IOException ex) {
            throw publicUploadFailure(session, "protocol creation", ex);
        }
        if (response.getStatus() >= 400) {
            return;
        }
        String location = response.getHeader(HttpHeaders.LOCATION);
        if (location == null) {
            throw new IOException("Resumable protocol resource location is missing.");
        }
        uploadService.bindProtocolUpload(session.id(), location);
    }

    @Scheduled(fixedDelayString = "${nas.upload.resumable-cleanup-interval-ms:600000}")
    public void cleanupAndRecover() {
        try {
            for (ResumableUploadSession session : uploadService.list()) {
                try {
                    if (session.expired(Instant.now())) {
                        deleteProtocolDataIfPresent(session);
                        uploadService.remove(session.id());
                        continue;
                    }
                    if (session.status() == ResumableUploadStatus.UPLOADING
                            || session.status() == ResumableUploadStatus.STAGED
                            || session.status() == ResumableUploadStatus.FINALIZING) {
                        tryFinalize(session.id(), null);
                    }
                } catch (Exception ex) {
                    logger.warn("Failed to recover resumable upload session {}.", session.id(), ex);
                }
            }
            protocolService.cleanup();
            pruneFileRequestPermits();
        } catch (IOException ex) {
            logger.warn("Failed to clean resumable upload state.", ex);
        }
    }

    public void deleteProtocolDataIfPresent(ResumableUploadSession session) {
        if (session.protocolUploadUri() == null) {
            return;
        }
        try {
            protocolService.deleteUpload(session.protocolUploadUri(), session.id());
        } catch (IOException | TusException ex) {
            logger.debug("Resumable protocol data was already unavailable for {}.", session.id());
        }
    }

    private synchronized void tryFinalize(String sessionId, HttpServletRequest request) throws IOException {
        ResumableUploadSession session = uploadService.require(sessionId);
        if (session.status().terminal()) {
            return;
        }

        if (session.status() == ResumableUploadStatus.UPLOADING) {
            if (session.protocolUploadUri() == null) {
                return;
            }
            UploadInfo info;
            try {
                info = protocolService.uploadInfo(session.protocolUploadUri(), session.id());
            } catch (TusException ex) {
                throw new IOException("Resumable upload metadata is unavailable.", ex);
            }
            if (info == null || info.isUploadInProgress()) {
                return;
            }
            Path staged;
            try {
                staged = protocolService.claimCompletedData(
                        session.protocolUploadUri(), session.id(), session.id()
                );
            } catch (TusException ex) {
                throw new IOException("Completed resumable upload data is unavailable.", ex);
            }
            uploadService.markStaged(session.id(), staged);
            deleteProtocolDataIfPresent(uploadService.require(session.id()));
        }

        ResumableUploadSession beforeFinalize = uploadService.require(session.id());
        if (beforeFinalize.status() != ResumableUploadStatus.STAGED
                && beforeFinalize.status() != ResumableUploadStatus.FINALIZING) {
            return;
        }
        FinalizationResult result = uploadService.finalizeStaged(session.id());
        recordCompletion(uploadService.require(session.id()), result, request);
    }

    private void validateProtocolRequest(ResumableUploadSession session, HttpServletRequest request) {
        String method = request.getMethod();
        String requestUri = request.getRequestURI();
        if ("POST".equals(method)) {
            if (session.status() != ResumableUploadStatus.ADMITTED || session.protocolUploadUri() != null) {
                throw rejected(HttpStatus.CONFLICT, "Upload protocol resource already exists.");
            }
            long declaredLength = parseUploadLength(request.getHeader(UPLOAD_LENGTH));
            if (declaredLength != session.size()) {
                throw rejected(HttpStatus.BAD_REQUEST, "Upload length does not match the admitted file.");
            }
            UploadInfo uploadInfo = new UploadInfo(request);
            uploadInfo.setEncodedMetadata(request.getHeader(UPLOAD_METADATA));
            Map<String, String> metadata = uploadInfo.getMetadata();
            if (!session.id().equals(metadata.get("sessionId"))
                    || !session.originalFilename().equals(metadata.get("filename"))) {
                throw rejected(HttpStatus.BAD_REQUEST, "Upload metadata does not match the admitted file.");
            }
            return;
        }

        if ("OPTIONS".equals(method)) {
            return;
        }

        if (session.protocolUploadUri() == null || !session.protocolUploadUri().equals(requestUri)) {
            throw rejected(HttpStatus.NOT_FOUND, "Upload protocol resource was not found.");
        }
        if (session.status().terminal()) {
            throw rejected(HttpStatus.GONE, "Upload session is already complete.");
        }
        if (request.getHeader(TUS_RESUMABLE) == null) {
            throw rejected(HttpStatus.PRECONDITION_FAILED, "Tus-Resumable header is required.");
        }
    }

    private long parseUploadLength(String value) {
        try {
            return Long.parseLong(value);
        } catch (NumberFormatException ex) {
            throw rejected(HttpStatus.BAD_REQUEST, "Upload-Length header is required.");
        }
    }

    private HttpServletRequest normalizePatchContentType(HttpServletRequest request) {
        if (!"PATCH".equals(request.getMethod())) {
            return request;
        }
        String contentType = request.getContentType();
        if (contentType == null
                || !contentType.regionMatches(true, 0, OFFSET_CONTENT_TYPE, 0, OFFSET_CONTENT_TYPE.length())
                || (contentType.length() > OFFSET_CONTENT_TYPE.length()
                && contentType.charAt(OFFSET_CONTENT_TYPE.length()) != ';')) {
            return request;
        }
        return new HttpServletRequestWrapper(request) {
            @Override
            public String getContentType() {
                return OFFSET_CONTENT_TYPE;
            }

            @Override
            public String getHeader(String name) {
                return HttpHeaders.CONTENT_TYPE.equalsIgnoreCase(name)
                        ? OFFSET_CONTENT_TYPE
                        : super.getHeader(name);
            }
        };
    }

    private ChunkPermit acquireChunkPermit(ResumableUploadSession session, String method) {
        if (!"PATCH".equals(method)) {
            return ChunkPermit.NONE;
        }
        if (!globalChunkPermits.tryAcquire()) {
            throw new ResumableUploadRejectedException(
                    HttpStatus.TOO_MANY_REQUESTS, "Upload capacity is busy. Retry shortly.", 2
            );
        }
        Semaphore sourcePermit = null;
        if (session.source() == ResumableUploadSource.FILE_REQUEST) {
            sourcePermit = fileRequestChunkPermits.computeIfAbsent(
                    session.sourceReference(), ignored -> new Semaphore(fileRequestChunkLimit, true)
            );
            if (!sourcePermit.tryAcquire()) {
                globalChunkPermits.release();
                throw new ResumableUploadRejectedException(
                        HttpStatus.TOO_MANY_REQUESTS, "Upload capacity is busy. Retry shortly.", 2
                );
            }
        }
        return new ChunkPermit(globalChunkPermits, sourcePermit);
    }

    private void pruneFileRequestPermits() throws IOException {
        Instant now = Instant.now();
        Set<String> activeRequestIds = uploadService.list().stream()
                .filter(session -> session.source() == ResumableUploadSource.FILE_REQUEST)
                .filter(session -> !session.expired(now))
                .filter(session -> !session.status().terminal())
                .map(ResumableUploadSession::sourceReference)
                .collect(java.util.stream.Collectors.toUnmodifiableSet());
        fileRequestChunkPermits.keySet().removeIf(id -> !activeRequestIds.contains(id));
    }

    private void recordCompletion(
            ResumableUploadSession session,
            FinalizationResult result,
            HttpServletRequest request
    ) {
        Map<String, String> metadata = new LinkedHashMap<>();
        metadata.put("uploadId", session.id());
        metadata.put("filename", session.originalFilename());
        metadata.put("size", String.valueOf(session.size()));
        metadata.put("pendingDecision", String.valueOf(result.status() == ResumableUploadStatus.PENDING));
        if (session.submittedBy() != null) {
            metadata.put("uploaderName", session.submittedBy());
        }
        if (session.source() == ResumableUploadSource.FILE_REQUEST) {
            metadata.put("requestId", session.sourceReference());
            record("FILE_REQUEST_UPLOAD", request, result.committedPath(),
                    "File received through file request", metadata);
        } else {
            record("UPLOAD", request, result.committedPath(), "Uploaded " + session.originalFilename(), metadata);
        }
    }

    private void record(
            String type,
            HttpServletRequest request,
            String path,
            String message,
            Map<String, String> metadata
    ) {
        if (request != null) {
            activityLogService.record(type, request, path, null, message, Map.copyOf(metadata));
        } else {
            activityLogService.record(type, "system", "-", path, null, true, message, Map.copyOf(metadata));
        }
    }

    private ResumableUploadRejectedException rejected(HttpStatus status, String message) {
        return new ResumableUploadRejectedException(status, message);
    }

    private RuntimeException publicUploadFailure(
            ResumableUploadSession session,
            String operation,
            Exception exception
    ) throws IOException {
        if (session.source() == ResumableUploadSource.FILE_REQUEST) {
            logger.warn("File request upload {} failed during {}.", session.id(), operation, exception);
            return rejected(HttpStatus.INTERNAL_SERVER_ERROR, "Upload could not be processed.");
        }
        if (exception instanceof IOException ioException) {
            throw ioException;
        }
        return (RuntimeException) exception;
    }

    private static final class ChunkPermit implements AutoCloseable {

        private static final ChunkPermit NONE = new ChunkPermit(null, null);
        private final Semaphore global;
        private final Semaphore source;

        private ChunkPermit(Semaphore global, Semaphore source) {
            this.global = global;
            this.source = source;
        }

        @Override
        public void close() {
            if (source != null) {
                source.release();
            }
            if (global != null) {
                global.release();
            }
        }
    }
}
