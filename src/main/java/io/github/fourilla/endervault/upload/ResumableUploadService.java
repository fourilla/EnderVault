package io.github.fourilla.endervault.upload;

import io.github.fourilla.endervault.common.StorageAccessException;
import io.github.fourilla.endervault.config.NasProperties;
import io.github.fourilla.endervault.filerequest.FileRequest;
import io.github.fourilla.endervault.filerequest.FileRequestPendingDecisionObserver.FileRequestUploadReference;
import io.github.fourilla.endervault.filerequest.FileRequestService;
import io.github.fourilla.endervault.filerequest.UploaderNamePolicy;
import io.github.fourilla.endervault.filecommit.FileCommitCoordinator;
import io.github.fourilla.endervault.filecommit.FileCommitConflictException;
import io.github.fourilla.endervault.filecommit.FileCommitOwner;
import io.github.fourilla.endervault.filecommit.FileCommitOwnerType;
import io.github.fourilla.endervault.pending.PendingFileDecision;
import io.github.fourilla.endervault.pending.PendingFileDecisionService;
import io.github.fourilla.endervault.pending.PendingFileDecisionSource;
import io.github.fourilla.endervault.storage.FileItem;
import io.github.fourilla.endervault.storage.StorageService;
import io.github.fourilla.endervault.storage.StorageUsage;
import java.io.IOException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

@Service
public class ResumableUploadService {

    private static final long GIBIBYTE = 1024L * 1024 * 1024;
    private static final long MINIMUM_FREE_RESERVE_BYTES = 5L * GIBIBYTE;
    private static final int MAX_OUTSTANDING_SESSIONS = 100;
    private static final int MAX_OUTSTANDING_SESSIONS_PER_SOURCE = 8;
    private static final int MAX_UPLOADER_NAME_LENGTH = 100;
    private static final int MAX_CONTENT_TYPE_LENGTH = 255;
    private static final String ADMIN_SOURCE_REFERENCE = "admin";

    private final ResumableUploadRepository repository;
    private final FileRequestService fileRequestService;
    private final StorageService storageService;
    private final PendingFileDecisionService pendingFileDecisionService;
    private final FileCommitCoordinator fileCommitCoordinator;
    private final NasProperties.Upload uploadProperties;

    public ResumableUploadService(
            ResumableUploadRepository repository,
            FileRequestService fileRequestService,
            StorageService storageService,
            PendingFileDecisionService pendingFileDecisionService,
            FileCommitCoordinator fileCommitCoordinator,
            NasProperties nasProperties
    ) {
        this.repository = repository;
        this.fileRequestService = fileRequestService;
        this.storageService = storageService;
        this.pendingFileDecisionService = pendingFileDecisionService;
        this.fileCommitCoordinator = fileCommitCoordinator;
        this.uploadProperties = nasProperties.getUpload();
    }

    public synchronized ResumableUploadSession admitAdmin(
            String destinationPath,
            String filename,
            String contentType,
            long size,
            String fingerprint,
            String resumeSessionId
    ) throws IOException {
        String destination = storageService.normalizeVaultDirectory(destinationPath);
        return admit(
                ResumableUploadSource.ADMIN,
                ADMIN_SOURCE_REFERENCE,
                destination,
                cleanFilename(filename),
                cleanContentType(contentType),
                size,
                null,
                cleanFingerprint(fingerprint),
                cleanOptionalSessionId(resumeSessionId)
        );
    }

    public synchronized ResumableUploadSession admitFileRequest(
            String token,
            String filename,
            String contentType,
            long size,
            String uploaderName,
            String fingerprint,
            String resumeSessionId
    ) throws IOException {
        return admitFileRequest(
                token, filename, contentType, size, uploaderName, fingerprint,
                resumeSessionId, () -> { }
        );
    }

    public synchronized ResumableUploadSession admitFileRequest(
            String token,
            String filename,
            String contentType,
            long size,
            String uploaderName,
            String fingerprint,
            String resumeSessionId,
            Runnable newSessionAdmissionGuard
    ) throws IOException {
        FileRequest request = fileRequestService.requireUsable(token);
        String normalizedFilename = cleanFilename(filename);
        storageService.validateVaultEntryName(normalizedFilename);
        validateFileRequestFile(request, normalizedFilename, size);
        String submittedBy = cleanUploaderName(request.uploaderNamePolicy(), uploaderName);
        String normalizedFingerprint = cleanFingerprint(fingerprint);
        String normalizedResumeSessionId = cleanOptionalSessionId(resumeSessionId);

        Optional<ResumableUploadSession> reusable = reusableSession(
                ResumableUploadSource.FILE_REQUEST,
                request.id(),
                request.destinationPath(),
                normalizedFilename,
                size,
                normalizedFingerprint,
                normalizedResumeSessionId
        );
        if (reusable.isPresent()) {
            return reusable.get();
        }

        newSessionAdmissionGuard.run();

        validateOutstandingLimits(request.id());
        long reservedBytes = reservedBytes(request.id());
        int reservedFiles = reservedFiles(request.id());
        long remainingBytes = Math.max(0L, request.maxTotalBytes() - request.acceptedBytes());
        int remainingFiles = Math.max(0, request.maxFiles() - request.acceptedFiles());
        if (reservedFiles >= remainingFiles
                || reservedBytes > remainingBytes
                || size > remainingBytes - reservedBytes) {
            throw rejected(HttpStatus.CONFLICT, "This file request has reached its quota.");
        }
        ensureDiskCapacity(size);
        return createSession(
                ResumableUploadSource.FILE_REQUEST,
                request.id(),
                request.destinationPath(),
                normalizedFilename,
                cleanContentType(contentType),
                size,
                submittedBy,
                normalizedFingerprint
        );
    }

    public synchronized ResumableUploadSession require(String id) throws IOException {
        String normalizedId = cleanSessionId(id);
        return repository.find(normalizedId)
                .orElseThrow(() -> new NoSuchFileException("Upload session was not found."));
    }

    synchronized ResumableUploadSession admitDirectoryFile(
            String groupId, String relativePath, String destination, ResumableUploadAdmissionRequest request,
            String existingSessionId
    ) throws IOException {
        String reference = "directory:" + groupId + "/" + relativePath;
        String fingerprint = cleanFingerprint(request.fingerprint());
        if (existingSessionId != null) {
            Optional<ResumableUploadSession> existing = repository.find(existingSessionId);
            if (existing.isPresent()) {
                ResumableUploadSession session = existing.get();
                if (!session.directoryMember() || !reference.equals(session.sourceReference())
                        || session.size() != request.size() || !session.fingerprint().equals(fingerprint)) {
                    throw rejected(HttpStatus.CONFLICT, "The selected file differs from the directory upload.");
                }
                if (session.status() == ResumableUploadStatus.DIRECTORY_READY) return session;
            }
        }
        return admit(ResumableUploadSource.ADMIN, reference, destination, cleanFilename(request.filename()),
                cleanContentType(request.contentType()), request.size(), null, fingerprint, existingSessionId);
    }

    public synchronized ResumableUploadSession authorizeProtocolAccess(String id, boolean adminAuthenticated)
            throws IOException {
        ResumableUploadSession session = require(id);
        if (session.expired(Instant.now()) && !session.status().terminal()) {
            throw rejected(HttpStatus.GONE, "Upload session has expired.");
        }
        if (session.source() == ResumableUploadSource.ADMIN) {
            if (!adminAuthenticated) {
                throw rejected(HttpStatus.UNAUTHORIZED, "Administrator authentication is required.");
            }
        } else if (!session.status().terminal()) {
            fileRequestService.requireUploadSessionAllowedById(session.sourceReference());
        }
        return session;
    }

    public synchronized ResumableUploadSession authorizeCancellation(String id, boolean adminAuthenticated)
            throws IOException {
        ResumableUploadSession session = require(id);
        if (session.source() == ResumableUploadSource.ADMIN && !adminAuthenticated) {
            throw rejected(HttpStatus.UNAUTHORIZED, "Administrator authentication is required.");
        }
        return session;
    }

    public synchronized ResumableUploadSession bindProtocolUpload(String id, String uploadUri) throws IOException {
        ResumableUploadSession session = require(id);
        String expectedPrefix = endpoint(session.id()) + "/";
        if (uploadUri == null || !uploadUri.startsWith(expectedPrefix)
                || uploadUri.substring(expectedPrefix.length()).contains("/")) {
            throw new StorageAccessException("Invalid resumable protocol upload URI.");
        }
        if (session.protocolUploadUri() != null) {
            if (!session.protocolUploadUri().equals(uploadUri)) {
                throw new StorageAccessException("Upload session is already bound to another protocol resource.");
            }
            return session;
        }
        if (session.status() != ResumableUploadStatus.ADMITTED) {
            throw new StorageAccessException("Upload session cannot create another protocol resource.");
        }
        ResumableUploadSession updated = session.withProtocolUpload(uploadUri);
        repository.save(updated);
        return updated;
    }

    public synchronized ResumableUploadSession markStaged(String id, Path stagedFile) throws IOException {
        ResumableUploadSession session = require(id);
        if (session.status().terminal()) {
            return session;
        }
        String stagingFilename = storageService.fileStagingFilename(stagedFile);
        if (Files.size(stagedFile) != session.size()) {
            throw new StorageAccessException("Completed upload size does not match the admitted size.");
        }
        ResumableUploadSession updated = session.withStagingFile(stagingFilename);
        repository.save(updated);
        return updated;
    }

    public synchronized FinalizationResult finalizeStaged(String id) throws IOException {
        ResumableUploadSession session = require(id);
        if (session.directoryMember()) {
            if (session.status() == ResumableUploadStatus.DIRECTORY_READY) return FinalizationResult.from(session);
            if (session.status() != ResumableUploadStatus.STAGED) {
                throw new StorageAccessException("Directory upload member is not staged.");
            }
            Path staged = stagedFilePath(session);
            if (!Files.isRegularFile(staged, LinkOption.NOFOLLOW_LINKS)) {
                throw new NoSuchFileException("Directory upload member data is missing.");
            }
            ResumableUploadSession ready = session.directoryReady();
            repository.save(ready);
            return FinalizationResult.from(ready);
        }
        if (session.status() == ResumableUploadStatus.COMPLETED) {
            fileCommitCoordinator.completeForOwnerIfPresent(commitOwner(session));
            return FinalizationResult.from(session);
        }
        if (session.status() == ResumableUploadStatus.PENDING) {
            return FinalizationResult.from(session);
        }
        if (session.status() != ResumableUploadStatus.STAGED
                && session.status() != ResumableUploadStatus.FINALIZING) {
            throw new StorageAccessException("Upload session is not ready for finalization.");
        }

        String sourceReference = pendingSourceReference(session);
        Optional<PendingFileDecision> existingPending = pendingFileDecisionService.findBySourceReference(
                pendingSource(session), sourceReference
        );
        if (existingPending.isPresent()) {
            fileCommitCoordinator.completeConflictForOwnerIfPresent(commitOwner(session));
            recordAcceptedQuota(session);
            ResumableUploadSession pending = session.pending(existingPending.get().id());
            repository.save(pending);
            return FinalizationResult.from(pending);
        }

        if (session.committedPath() != null) {
            FileItem committed = storageService.describeVaultPath(session.committedPath());
            if (committed.directory() || committed.size() != session.size()) {
                throw new StorageAccessException("Committed upload target no longer matches the upload session.");
            }
            fileCommitCoordinator.completeForOwnerIfPresent(commitOwner(session));
            recordAcceptedQuota(session);
            ResumableUploadSession completed = session.completed(session.committedPath());
            repository.save(completed);
            return FinalizationResult.from(completed);
        }

        Path stagedFile = stagedFilePath(session);
        ResumableUploadSession finalizing = session.finalizing();
        repository.save(finalizing);
        try {
            FileCommitCoordinator.StagedFileCommit commit = fileCommitCoordinator.commitSingleFile(
                    commitOwner(session),
                    stagedFile,
                    session.destinationPath(),
                    session.originalFilename()
            );
            StorageService.CommittedVaultFile committed = commit.file();
            ResumableUploadSession committedSession = finalizing.withCommittedTarget(committed.path());
            repository.save(committedSession);
            fileCommitCoordinator.complete(commit.operationId());
            recordAcceptedQuota(committedSession);
            ResumableUploadSession completed = committedSession.completed(committed.path());
            repository.save(completed);
            return FinalizationResult.from(completed);
        } catch (FileCommitConflictException ex) {
            PendingFileDecision pendingDecision = pendingFileDecisionService.create(
                    stagedFile,
                    pendingSource(session),
                    session.destinationPath(),
                    session.originalFilename(),
                    session.size(),
                    sourceReference,
                    session.submittedBy()
            );
            fileCommitCoordinator.completeConflict(ex.operationId());
            recordAcceptedQuota(session);
            ResumableUploadSession pending = finalizing.pending(pendingDecision.id());
            repository.save(pending);
            return FinalizationResult.from(pending);
        } catch (FileAlreadyExistsException ex) {
            PendingFileDecision pendingDecision = pendingFileDecisionService.create(
                    stagedFile,
                    pendingSource(session),
                    session.destinationPath(),
                    session.originalFilename(),
                    session.size(),
                    sourceReference,
                    session.submittedBy()
            );
            recordAcceptedQuota(session);
            ResumableUploadSession pending = finalizing.pending(pendingDecision.id());
            repository.save(pending);
            return FinalizationResult.from(pending);
        }
    }

    public synchronized ResumableUploadSession cancel(String id) throws IOException {
        ResumableUploadSession session = require(id);
        if (session.status().terminal()) {
            return session;
        }
        requireNoActiveCommit(session);
        deleteStagingIfPresent(session);
        ResumableUploadSession canceled = session.canceled();
        repository.remove(session.id());
        return canceled;
    }

    public synchronized ResumableUploadSession fail(String id, String message) throws IOException {
        ResumableUploadSession session = require(id);
        if (session.status().terminal()) {
            return session;
        }
        ResumableUploadSession failed = session.failed(message);
        repository.save(failed);
        return failed;
    }

    public synchronized List<ResumableUploadSession> list() throws IOException {
        return repository.list().stream()
                .sorted(Comparator.comparing(ResumableUploadSession::createdAt))
                .toList();
    }

    public synchronized boolean referencesStagingFile(String filename) throws IOException {
        return activeStagingFilenames().contains(filename);
    }

    public synchronized Set<String> activeStagingFilenames() throws IOException {
        Instant now = Instant.now();
        java.util.HashSet<String> active = new java.util.HashSet<>();
        for (ResumableUploadSession session : repository.list()) {
            if (session.status().terminal() && session.status() != ResumableUploadStatus.DIRECTORY_READY) {
                continue;
            }
            if (!session.expired(now) || fileCommitCoordinator.hasActiveJournal(commitOwner(session))) {
                active.add(expectedStagingFilename(session));
            }
        }
        return Set.copyOf(active);
    }

    public synchronized boolean hasActiveFileCommitJournal(String sessionId) throws IOException {
        ResumableUploadSession session = require(sessionId);
        return fileCommitCoordinator.hasActiveJournal(commitOwner(session));
    }

    public synchronized void remove(String id) throws IOException {
        ResumableUploadSession session = require(id);
        requireNoActiveCommit(session);
        if (session.status() != ResumableUploadStatus.COMPLETED
                && session.status() != ResumableUploadStatus.PENDING) {
            deleteStagingIfPresent(session);
        }
        repository.remove(session.id());
    }

    public long chunkSizeBytes() {
        return uploadProperties.getResumableChunkSizeBytes();
    }

    public String defaultConflictPolicy() {
        return storageService.defaultConflictPolicy().value();
    }

    public String endpoint(String sessionId) {
        return "/api/v1/uploads/" + cleanSessionId(sessionId);
    }

    private ResumableUploadSession admit(
            ResumableUploadSource source,
            String sourceReference,
            String destination,
            String filename,
            String contentType,
            long size,
            String submittedBy,
            String fingerprint,
            String resumeSessionId
    ) throws IOException {
        storageService.validateVaultEntryName(filename);
        validateSize(size);
        Optional<ResumableUploadSession> reusable = reusableSession(
                source, sourceReference, destination, filename, size, fingerprint, resumeSessionId
        );
        if (reusable.isPresent()) {
            return reusable.get();
        }
        validateOutstandingLimits(sourceReference);
        ensureDiskCapacity(size);
        return createSession(
                source, sourceReference, destination, filename, contentType,
                size, submittedBy, fingerprint
        );
    }

    private ResumableUploadSession createSession(
            ResumableUploadSource source,
            String sourceReference,
            String destination,
            String filename,
            String contentType,
            long size,
            String submittedBy,
            String fingerprint
    ) throws IOException {
        Instant now = Instant.now();
        ResumableUploadSession session = new ResumableUploadSession(
                UUID.randomUUID().toString(),
                source,
                sourceReference,
                destination,
                filename,
                contentType,
                size,
                submittedBy,
                fingerprint,
                now,
                now.plus(Duration.ofHours(uploadProperties.getResumableSessionRetentionHours())),
                ResumableUploadStatus.ADMITTED,
                null,
                null,
                null,
                null,
                null
        );
        repository.save(session);
        return session;
    }

    private Optional<ResumableUploadSession> reusableSession(
            ResumableUploadSource source,
            String sourceReference,
            String destination,
            String filename,
            long size,
            String fingerprint,
            String resumeSessionId
    ) throws IOException {
        if (resumeSessionId == null) {
            return Optional.empty();
        }
        Instant now = Instant.now();
        return repository.list().stream()
                .filter(session -> session.id().equals(resumeSessionId))
                .filter(session -> !session.expired(now))
                .filter(session -> session.status().reservesQuota())
                .filter(session -> session.source() == source)
                .filter(session -> session.sourceReference().equals(sourceReference))
                .filter(session -> session.destinationPath().equals(destination))
                .filter(session -> session.originalFilename().equals(filename))
                .filter(session -> session.size() == size)
                .filter(session -> session.fingerprint().equals(fingerprint))
                .findFirst();
    }

    private void validateOutstandingLimits(String sourceReference) throws IOException {
        List<ResumableUploadSession> active = repository.list().stream()
                .filter(session -> !session.expired(Instant.now()))
                .filter(session -> session.status().reservesQuota())
                .toList();
        if (active.size() >= MAX_OUTSTANDING_SESSIONS
                || active.stream().filter(session -> session.sourceReference().equals(sourceReference)).count()
                >= MAX_OUTSTANDING_SESSIONS_PER_SOURCE) {
            throw new ResumableUploadRejectedException(
                    HttpStatus.TOO_MANY_REQUESTS,
                    "Too many uploads are waiting. Retry shortly.",
                    5
            );
        }
    }

    private long reservedBytes(String requestId) throws IOException {
        Instant now = Instant.now();
        return repository.list().stream()
                .filter(session -> !session.expired(now))
                .filter(session -> session.source() == ResumableUploadSource.FILE_REQUEST)
                .filter(session -> session.sourceReference().equals(requestId))
                .filter(session -> session.status().reservesQuota())
                .mapToLong(ResumableUploadSession::size)
                .sum();
    }

    private int reservedFiles(String requestId) throws IOException {
        Instant now = Instant.now();
        return (int) repository.list().stream()
                .filter(session -> !session.expired(now))
                .filter(session -> session.source() == ResumableUploadSource.FILE_REQUEST)
                .filter(session -> session.sourceReference().equals(requestId))
                .filter(session -> session.status().reservesQuota())
                .count();
    }

    private long reservedBytesAllSessions() throws IOException {
        Instant now = Instant.now();
        return repository.list().stream()
                .filter(session -> !session.expired(now))
                .filter(session -> session.status().reservesQuota())
                .mapToLong(ResumableUploadSession::size)
                .sum();
    }

    private void ensureDiskCapacity(long size) throws IOException {
        validateSize(size);
        StorageUsage usage = storageService.storageUsage();
        long requiredReserve = Math.max(MINIMUM_FREE_RESERVE_BYTES, usage.totalBytes() / 20L);
        long usableForUploads = Math.max(0L, usage.usableBytes() - requiredReserve);
        long reserved = reservedBytesAllSessions();
        if (reserved > usableForUploads || size > usableForUploads - reserved) {
            throw rejected(HttpStatus.INSUFFICIENT_STORAGE, "The server does not have enough free storage.");
        }
    }

    private void validateFileRequestFile(FileRequest request, String filename, long size) {
        validateSize(size);
        if (size > request.maxFileSizeBytes()) {
            throw rejected(HttpStatus.PAYLOAD_TOO_LARGE, "This file exceeds the request file-size limit.");
        }
        if (!request.allowedExtensions().isEmpty() && !hasAllowedExtension(filename, request.allowedExtensions())) {
            throw rejected(HttpStatus.UNSUPPORTED_MEDIA_TYPE, "This file extension is not accepted.");
        }
    }

    private boolean hasAllowedExtension(String filename, Iterable<String> extensions) {
        String normalized = filename.toLowerCase(Locale.ROOT);
        for (String extension : extensions) {
            if (normalized.length() > extension.length() + 1 && normalized.endsWith("." + extension)) {
                return true;
            }
        }
        return false;
    }

    private void recordAcceptedQuota(ResumableUploadSession session) throws IOException {
        if (session.source() == ResumableUploadSource.FILE_REQUEST) {
            try {
                fileRequestService.recordAcceptedUpload(session.sourceReference(), session.id(), session.size());
            } catch (NoSuchFileException ex) {
                // The administrator may delete an expired request after all bytes were received.
                // The committed file remains valid even though there is no request record left to update.
            }
        }
    }

    private PendingFileDecisionSource pendingSource(ResumableUploadSession session) {
        return session.source() == ResumableUploadSource.FILE_REQUEST
                ? PendingFileDecisionSource.FILE_REQUEST
                : PendingFileDecisionSource.ADMIN_UPLOAD;
    }

    private String pendingSourceReference(ResumableUploadSession session) {
        return session.source() == ResumableUploadSource.FILE_REQUEST
                ? FileRequestUploadReference.format(session.sourceReference(), session.id())
                : session.id();
    }

    private Path stagedFilePath(ResumableUploadSession session) throws IOException {
        Path stagedFile = session.stagingFilename() == null
                ? storageService.resumableUploadStagingFile(session.id())
                : storageService.resolveFileStagingFile(session.stagingFilename());
        if (Files.exists(stagedFile, LinkOption.NOFOLLOW_LINKS)
                && (!Files.isRegularFile(stagedFile, LinkOption.NOFOLLOW_LINKS)
                || Files.isSymbolicLink(stagedFile)
                || Files.size(stagedFile) != session.size())) {
            throw new StorageAccessException("Resumable upload staging size is invalid.");
        }
        return stagedFile;
    }

    private String expectedStagingFilename(ResumableUploadSession session) {
        return session.stagingFilename() == null
                ? "resumable-" + session.id() + ".tmp"
                : session.stagingFilename();
    }

    private void deleteStagingIfPresent(ResumableUploadSession session) throws IOException {
        Path stagedFile = session.stagingFilename() == null
                ? storageService.resumableUploadStagingFile(session.id())
                : storageService.resolveFileStagingFile(session.stagingFilename());
        Files.deleteIfExists(stagedFile);
    }

    private void requireNoActiveCommit(ResumableUploadSession session) throws IOException {
        if (fileCommitCoordinator.hasActiveJournal(commitOwner(session))) {
            throw new StorageAccessException("Upload finalization is still being recovered.");
        }
    }

    private FileCommitOwner commitOwner(ResumableUploadSession session) {
        return new FileCommitOwner(FileCommitOwnerType.RESUMABLE_UPLOAD, session.id());
    }

    private String cleanFilename(String filename) {
        if (filename == null || filename.isBlank() || !filename.equals(filename.trim())) {
            throw rejected(HttpStatus.BAD_REQUEST, "File name is invalid.");
        }
        return filename;
    }

    private String cleanContentType(String contentType) {
        String normalized = contentType == null ? "" : contentType.trim();
        if (normalized.length() > MAX_CONTENT_TYPE_LENGTH
                || normalized.chars().anyMatch(Character::isISOControl)) {
            throw rejected(HttpStatus.BAD_REQUEST, "File content type is invalid.");
        }
        return normalized;
    }

    private String cleanUploaderName(UploaderNamePolicy policy, String value) {
        if (policy == UploaderNamePolicy.NONE) {
            return null;
        }
        String normalized = value == null ? "" : value.trim();
        if (policy == UploaderNamePolicy.REQUIRED && normalized.isBlank()) {
            throw rejected(HttpStatus.BAD_REQUEST, "Uploader name is required.");
        }
        if (normalized.length() > MAX_UPLOADER_NAME_LENGTH
                || normalized.chars().anyMatch(Character::isISOControl)) {
            throw rejected(HttpStatus.BAD_REQUEST, "Uploader name is invalid.");
        }
        return normalized.isBlank() ? null : normalized;
    }

    private String cleanFingerprint(String fingerprint) {
        String normalized = fingerprint == null ? "" : fingerprint.trim().toLowerCase(Locale.ROOT);
        if (!normalized.matches("[0-9a-f]{64}")) {
            throw rejected(HttpStatus.BAD_REQUEST, "Upload fingerprint is invalid.");
        }
        return normalized;
    }

    private String cleanSessionId(String id) {
        String normalized = id == null ? "" : id.trim();
        try {
            return UUID.fromString(normalized).toString();
        } catch (IllegalArgumentException ex) {
            throw rejected(HttpStatus.NOT_FOUND, "Upload session was not found.");
        }
    }

    private String cleanOptionalSessionId(String id) {
        if (id == null || id.isBlank()) {
            return null;
        }
        try {
            return UUID.fromString(id.trim()).toString();
        } catch (IllegalArgumentException ex) {
            throw rejected(HttpStatus.BAD_REQUEST, "Resume session id is invalid.");
        }
    }

    private void validateSize(long size) {
        if (size < 0L) {
            throw rejected(HttpStatus.BAD_REQUEST, "File size is invalid.");
        }
    }

    private ResumableUploadRejectedException rejected(HttpStatus status, String message) {
        return new ResumableUploadRejectedException(status, message);
    }

    public record FinalizationResult(
            ResumableUploadStatus status,
            String committedPath,
            String pendingDecisionId
    ) {
        static FinalizationResult from(ResumableUploadSession session) {
            return new FinalizationResult(
                    session.status(), session.committedPath(), session.pendingDecisionId()
            );
        }
    }
}
