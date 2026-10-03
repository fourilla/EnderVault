package io.github.fourilla.endervault.filerequest;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;

public record FileRequest(
        String id,
        String token,
        String title,
        String description,
        String destinationPath,
        UploaderNamePolicy uploaderNamePolicy,
        long maxFileSizeBytes,
        long maxTotalBytes,
        int maxFiles,
        List<String> allowedExtensions,
        long acceptedBytes,
        int acceptedFiles,
        List<String> acceptedUploadIds,
        Instant createdAt,
        Instant expiresAt,
        boolean enabled
) {

    private static final DateTimeFormatter LABEL_FORMATTER =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm").withZone(ZoneId.systemDefault());

    public FileRequest {
        description = description == null ? "" : description;
        allowedExtensions = allowedExtensions == null ? List.of() : List.copyOf(allowedExtensions);
        acceptedUploadIds = acceptedUploadIds == null ? List.of() : List.copyOf(acceptedUploadIds);
    }

    public boolean expired(Instant now) {
        return expiresAt != null && !expiresAt.isAfter(now);
    }

    public boolean full() {
        return acceptedFiles >= maxFiles || acceptedBytes >= maxTotalBytes;
    }

    public boolean usable(Instant now) {
        return enabled && !expired(now) && !full();
    }

    public String statusLabel() {
        return statusLabel(Instant.now());
    }

    public String statusLabel(Instant now) {
        return switch (statusKey(now)) {
            case "revoked" -> "Revoked";
            case "expired" -> "Expired";
            case "full" -> "Full";
            default -> "Active";
        };
    }

    public String statusKey(Instant now) {
        if (!enabled) {
            return "revoked";
        }
        if (expired(now)) {
            return "expired";
        }
        if (full()) {
            return "full";
        }
        return "active";
    }

    public String statusClass() {
        return statusClass(Instant.now());
    }

    public String statusClass(Instant now) {
        String status = statusKey(now);
        return status.equals("full") ? "warning" : status;
    }

    public String createdLabel() {
        return LABEL_FORMATTER.format(createdAt);
    }

    public String expiresLabel() {
        return expiresAt == null ? "Never" : LABEL_FORMATTER.format(expiresAt);
    }

    public FileRequest revoke() {
        return new FileRequest(
                id, token, title, description, destinationPath, uploaderNamePolicy,
                maxFileSizeBytes, maxTotalBytes, maxFiles, allowedExtensions,
                acceptedBytes, acceptedFiles, acceptedUploadIds, createdAt, expiresAt, false
        );
    }

    public FileRequest withDestinationPath(String path) {
        return new FileRequest(
                id, token, title, description, path, uploaderNamePolicy,
                maxFileSizeBytes, maxTotalBytes, maxFiles, allowedExtensions,
                acceptedBytes, acceptedFiles, acceptedUploadIds, createdAt, expiresAt, enabled
        );
    }

    public FileRequest withAcceptedUpload(String uploadId, long size) {
        if (acceptedUploadIds.contains(uploadId)) {
            return this;
        }
        List<String> nextUploadIds = new java.util.ArrayList<>(acceptedUploadIds);
        nextUploadIds.add(uploadId);
        return new FileRequest(
                id, token, title, description, destinationPath, uploaderNamePolicy,
                maxFileSizeBytes, maxTotalBytes, maxFiles, allowedExtensions,
                Math.addExact(acceptedBytes, size), Math.addExact(acceptedFiles, 1),
                nextUploadIds, createdAt, expiresAt, enabled
        );
    }

    public FileRequest withoutAcceptedUpload(String uploadId, long size) {
        if (!acceptedUploadIds.contains(uploadId)) {
            return this;
        }
        List<String> nextUploadIds = new java.util.ArrayList<>(acceptedUploadIds);
        nextUploadIds.remove(uploadId);
        return new FileRequest(
                id, token, title, description, destinationPath, uploaderNamePolicy,
                maxFileSizeBytes, maxTotalBytes, maxFiles, allowedExtensions,
                Math.max(0L, acceptedBytes - Math.max(0L, size)),
                Math.max(0, acceptedFiles - 1),
                nextUploadIds, createdAt, expiresAt, enabled
        );
    }
}
