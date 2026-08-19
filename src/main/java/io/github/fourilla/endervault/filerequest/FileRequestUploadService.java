package io.github.fourilla.endervault.filerequest;

import io.github.fourilla.endervault.config.NasProperties;
import io.github.fourilla.endervault.pending.PendingFileDecision;
import io.github.fourilla.endervault.pending.PendingFileDecisionService;
import io.github.fourilla.endervault.pending.PendingFileDecisionSource;
import io.github.fourilla.endervault.storage.ConflictPolicy;
import io.github.fourilla.endervault.storage.StorageService;
import io.github.fourilla.endervault.storage.StorageService.CommittedVaultFile;
import io.github.fourilla.endervault.storage.StorageUsage;
import io.github.fourilla.endervault.temporary.TemporaryArtifactRegistry;
import io.github.fourilla.endervault.temporary.TemporaryArtifactType;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

@Service
public class FileRequestUploadService {

    private static final long GIBIBYTE = 1024L * 1024 * 1024;
    private static final long MINIMUM_FREE_RESERVE_BYTES = 5L * GIBIBYTE;
    private static final int MAX_OUTSTANDING_TICKETS = 100;
    private static final int MAX_OUTSTANDING_TICKETS_PER_REQUEST = 8;
    private static final int MAX_UPLOADER_NAME_LENGTH = 100;
    private static final int BUFFER_SIZE = 64 * 1024;

    private final FileRequestService fileRequestService;
    private final StorageService storageService;
    private final PendingFileDecisionService pendingFileDecisionService;
    private final TemporaryArtifactRegistry temporaryArtifactRegistry;
    private final NasProperties.FileRequest properties;
    private final SecureRandom secureRandom = new SecureRandom();
    private final Map<String, UploadTicketState> tickets = new HashMap<>();
    private final Map<String, Integer> activeUploadsByRequest = new HashMap<>();
    private int activeUploads;

    public FileRequestUploadService(
            FileRequestService fileRequestService,
            StorageService storageService,
            PendingFileDecisionService pendingFileDecisionService,
            TemporaryArtifactRegistry temporaryArtifactRegistry,
            NasProperties nasProperties
    ) {
        this.fileRequestService = fileRequestService;
        this.storageService = storageService;
        this.pendingFileDecisionService = pendingFileDecisionService;
        this.temporaryArtifactRegistry = temporaryArtifactRegistry;
        this.properties = nasProperties.getFileRequest();
    }

    public synchronized UploadTicket issueTicket(
            String token,
            String originalFilename,
            long size,
            String uploaderName
    ) throws IOException {
        Instant now = Instant.now();
        cleanupExpiredTickets(now);
        FileRequest request = fileRequestService.requireUsable(token);
        String filename = cleanFilename(originalFilename);
        storageService.validateVaultEntryName(filename);
        String submittedBy = cleanUploaderName(request.uploaderNamePolicy(), uploaderName);
        validateFile(request, filename, size);
        validateOutstandingTicketLimits(request.id());

        long reservedBytes = reservedBytes(request.id());
        int reservedFiles = reservedFiles(request.id());
        if (request.acceptedFiles() + reservedFiles >= request.maxFiles()
                || request.acceptedBytes() > request.maxTotalBytes() - size - reservedBytes) {
            throw rejected(HttpStatus.CONFLICT, "This file request has reached its quota.");
        }
        ensureDiskCapacity(size, reservedBytesAllRequests());

        String id = randomId();
        Instant expiresAt = now.plus(Duration.ofMinutes(properties.getUploadTicketTtlMinutes()));
        UploadTicketState state = new UploadTicketState(
                id,
                request.id(),
                filename,
                size,
                submittedBy,
                now,
                expiresAt,
                TicketStatus.ISSUED,
                null
        );
        tickets.put(id, state);
        return new UploadTicket(id, filename, size, expiresAt);
    }

    public UploadReceipt receive(
            String token,
            String ticketId,
            InputStream inputStream,
            long contentLength
    ) throws IOException {
        UploadLease lease = beginUpload(token, ticketId, contentLength);
        Path temporaryFile = null;
        boolean acceptedRecorded = false;
        boolean handedOff = false;
        try {
            temporaryFile = storageService.createFileStagingTemporaryFile("request-", ".tmp");
            try (TemporaryArtifactRegistry.Registration ignored = temporaryArtifactRegistry.register(
                    temporaryFile,
                    TemporaryArtifactType.FILE_REQUEST_UPLOAD,
                    lease.ticket().id()
            )) {
                copyBounded(inputStream, temporaryFile, lease);
            }

            fileRequestService.requireUsableById(lease.ticket().requestId());
            fileRequestService.recordAcceptedUpload(lease.ticket().requestId(), lease.ticket().size());
            acceptedRecorded = true;
            try {
                CommittedVaultFile committed = storageService.commitTemporaryFileIntoVault(
                        temporaryFile,
                        lease.request().destinationPath(),
                        lease.ticket().originalFilename(),
                        ConflictPolicy.CANCEL
                );
                handedOff = true;
                return new UploadReceipt(
                        lease.ticket().requestId(),
                        lease.ticket().originalFilename(),
                        lease.ticket().uploaderName(),
                        lease.ticket().size(),
                        committed.path(),
                        false
                );
            } catch (FileAlreadyExistsException ex) {
                PendingFileDecision pending = pendingFileDecisionService.create(
                        temporaryFile,
                        PendingFileDecisionSource.FILE_REQUEST,
                        lease.request().destinationPath(),
                        lease.ticket().originalFilename(),
                        lease.ticket().size(),
                        lease.ticket().requestId(),
                        lease.ticket().uploaderName()
                );
                handedOff = true;
                return new UploadReceipt(
                        lease.ticket().requestId(),
                        lease.ticket().originalFilename(),
                        lease.ticket().uploaderName(),
                        lease.ticket().size(),
                        null,
                        true
                );
            }
        } catch (IOException | RuntimeException ex) {
            if (acceptedRecorded && !handedOff) {
                fileRequestService.releaseAcceptedUpload(lease.ticket().requestId(), lease.ticket().size());
            }
            throw ex;
        } finally {
            try {
                if (!handedOff && temporaryFile != null) {
                    Files.deleteIfExists(temporaryFile);
                }
            } finally {
                finishUpload(lease.ticket());
            }
        }
    }

    @Scheduled(fixedDelay = 60_000L)
    public synchronized void cleanupExpiredTickets() {
        cleanupExpiredTickets(Instant.now());
    }

    synchronized int outstandingTicketCount() {
        cleanupExpiredTickets(Instant.now());
        return tickets.size();
    }

    private synchronized UploadLease beginUpload(String token, String ticketId, long contentLength)
            throws IOException {
        Instant now = Instant.now();
        cleanupExpiredTickets(now);
        FileRequest request = fileRequestService.requireUsable(token);
        UploadTicketState ticket = tickets.get(cleanTicketId(ticketId));
        if (ticket == null || !ticket.requestId().equals(request.id()) || ticket.status() != TicketStatus.ISSUED) {
            throw rejected(HttpStatus.NOT_FOUND, "Upload ticket is unavailable.");
        }
        if (contentLength >= 0L && contentLength != ticket.size()) {
            throw rejected(HttpStatus.BAD_REQUEST, "Uploaded content length does not match the reserved size.");
        }
        if (activeUploads >= properties.getMaxConcurrentUploads()
                || activeUploadsByRequest.getOrDefault(request.id(), 0)
                >= properties.getMaxConcurrentUploadsPerRequest()) {
            throw new FileRequestUploadRejectedException(
                    HttpStatus.TOO_MANY_REQUESTS,
                    "Upload capacity is busy. Retry shortly.",
                    2
            );
        }

        UploadTicketState uploading = ticket.withStartedAt(now);
        tickets.put(ticket.id(), uploading);
        activeUploads++;
        activeUploadsByRequest.merge(request.id(), 1, Integer::sum);
        return new UploadLease(request, uploading);
    }

    private synchronized void finishUpload(UploadTicketState ticket) {
        UploadTicketState removed = tickets.remove(ticket.id());
        if (removed == null || removed.status() != TicketStatus.UPLOADING) {
            return;
        }
        activeUploads = Math.max(0, activeUploads - 1);
        activeUploadsByRequest.computeIfPresent(ticket.requestId(), (ignored, count) -> count <= 1 ? null : count - 1);
    }

    private void copyBounded(InputStream inputStream, Path target, UploadLease lease) throws IOException {
        Instant deadline = lease.ticket().startedAt().plus(Duration.ofHours(properties.getMaxUploadHours()));
        byte[] buffer = new byte[BUFFER_SIZE];
        long written = 0L;
        try (OutputStream outputStream = Files.newOutputStream(target)) {
            int read;
            while ((read = inputStream.read(buffer)) != -1) {
                if (Instant.now().isAfter(deadline)) {
                    throw rejected(HttpStatus.REQUEST_TIMEOUT, "Upload exceeded the maximum duration.");
                }
                if (written > lease.ticket().size() - read) {
                    throw rejected(HttpStatus.PAYLOAD_TOO_LARGE, "Uploaded data exceeded the reserved size.");
                }
                outputStream.write(buffer, 0, read);
                written += read;
            }
        }
        if (written != lease.ticket().size()) {
            throw rejected(HttpStatus.BAD_REQUEST, "Uploaded data was incomplete.");
        }
    }

    private void validateFile(FileRequest request, String filename, long size) {
        if (size < 0L || size > request.maxFileSizeBytes()) {
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

    private void validateOutstandingTicketLimits(String requestId) {
        if (tickets.size() >= MAX_OUTSTANDING_TICKETS
                || reservedFiles(requestId) >= MAX_OUTSTANDING_TICKETS_PER_REQUEST) {
            throw new FileRequestUploadRejectedException(
                    HttpStatus.TOO_MANY_REQUESTS,
                    "Too many uploads are waiting to start. Retry shortly.",
                    5
            );
        }
    }

    private void ensureDiskCapacity(long size, long reservedBytes) {
        StorageUsage usage = storageService.storageUsage();
        long percentageReserve = usage.totalBytes() / 20L;
        long requiredReserve = Math.max(MINIMUM_FREE_RESERVE_BYTES, percentageReserve);
        long usableForRequests = Math.max(0L, usage.usableBytes() - requiredReserve);
        if (reservedBytes > usableForRequests || size > usableForRequests - reservedBytes) {
            throw rejected(HttpStatus.INSUFFICIENT_STORAGE, "The server does not have enough free storage.");
        }
    }

    private String cleanFilename(String filename) {
        if (filename == null || filename.isBlank()) {
            throw rejected(HttpStatus.BAD_REQUEST, "File name is required.");
        }
        String normalized = filename.trim();
        if (!normalized.equals(filename)) {
            throw rejected(HttpStatus.BAD_REQUEST, "File name cannot start or end with whitespace.");
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

    private long reservedBytes(String requestId) {
        return tickets.values().stream()
                .filter(ticket -> ticket.requestId().equals(requestId))
                .mapToLong(UploadTicketState::size)
                .sum();
    }

    private long reservedBytesAllRequests() {
        return tickets.values().stream().mapToLong(UploadTicketState::size).sum();
    }

    private int reservedFiles(String requestId) {
        return (int) tickets.values().stream().filter(ticket -> ticket.requestId().equals(requestId)).count();
    }

    private void cleanupExpiredTickets(Instant now) {
        tickets.entrySet().removeIf(entry -> entry.getValue().status() == TicketStatus.ISSUED
                && !entry.getValue().expiresAt().isAfter(now));
    }

    private String cleanTicketId(String ticketId) {
        return ticketId == null ? "" : ticketId.trim();
    }

    private String randomId() {
        byte[] bytes = new byte[24];
        String id;
        do {
            secureRandom.nextBytes(bytes);
            id = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        } while (tickets.containsKey(id));
        return id;
    }

    private FileRequestUploadRejectedException rejected(HttpStatus status, String message) {
        return new FileRequestUploadRejectedException(status, message);
    }

    public record UploadTicket(String id, String filename, long size, Instant expiresAt) {
    }

    public record UploadReceipt(
            String requestId,
            String originalFilename,
            String uploaderName,
            long size,
            String committedPath,
            boolean pendingDecision
    ) {
    }

    private record UploadLease(FileRequest request, UploadTicketState ticket) {
    }

    private record UploadTicketState(
            String id,
            String requestId,
            String originalFilename,
            long size,
            String uploaderName,
            Instant createdAt,
            Instant expiresAt,
            TicketStatus status,
            Instant startedAt
    ) {
        UploadTicketState withStartedAt(Instant value) {
            return new UploadTicketState(
                    id, requestId, originalFilename, size, uploaderName,
                    createdAt, expiresAt, TicketStatus.UPLOADING, value
            );
        }
    }

    private enum TicketStatus {
        ISSUED,
        UPLOADING
    }
}
