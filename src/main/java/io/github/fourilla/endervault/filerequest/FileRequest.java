package io.github.fourilla.endervault.filerequest;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;

public record FileRequest(
        String id,
        String token,
        String title,
        String destinationPath,
        UploaderNamePolicy uploaderNamePolicy,
        long maxFileSizeBytes,
        long maxTotalBytes,
        int maxFiles,
        List<String> allowedExtensions,
        long acceptedBytes,
        int acceptedFiles,
        Instant createdAt,
        Instant expiresAt,
        boolean enabled
) {

    private static final DateTimeFormatter LABEL_FORMATTER =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm").withZone(ZoneId.systemDefault());

    public FileRequest {
        allowedExtensions = allowedExtensions == null ? List.of() : List.copyOf(allowedExtensions);
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
        if (!enabled) {
            return "Revoked";
        }
        if (expired(Instant.now())) {
            return "Expired";
        }
        if (full()) {
            return "Full";
        }
        return "Active";
    }

    public String statusClass() {
        return switch (statusLabel()) {
            case "Active" -> "active";
            case "Full" -> "warning";
            case "Expired" -> "expired";
            default -> "revoked";
        };
    }

    public String createdLabel() {
        return LABEL_FORMATTER.format(createdAt);
    }

    public String expiresLabel() {
        return expiresAt == null ? "Never" : LABEL_FORMATTER.format(expiresAt);
    }

    public FileRequest revoke() {
        return new FileRequest(
                id, token, title, destinationPath, uploaderNamePolicy,
                maxFileSizeBytes, maxTotalBytes, maxFiles, allowedExtensions,
                acceptedBytes, acceptedFiles, createdAt, expiresAt, false
        );
    }

    public FileRequest withDestinationPath(String path) {
        return new FileRequest(
                id, token, title, path, uploaderNamePolicy,
                maxFileSizeBytes, maxTotalBytes, maxFiles, allowedExtensions,
                acceptedBytes, acceptedFiles, createdAt, expiresAt, enabled
        );
    }

    public FileRequest withAcceptedUpload(long size) {
        return new FileRequest(
                id, token, title, destinationPath, uploaderNamePolicy,
                maxFileSizeBytes, maxTotalBytes, maxFiles, allowedExtensions,
                Math.addExact(acceptedBytes, size), Math.addExact(acceptedFiles, 1),
                createdAt, expiresAt, enabled
        );
    }

    public FileRequest withoutAcceptedUpload(long size) {
        return new FileRequest(
                id, token, title, destinationPath, uploaderNamePolicy,
                maxFileSizeBytes, maxTotalBytes, maxFiles, allowedExtensions,
                Math.max(0L, acceptedBytes - Math.max(0L, size)),
                Math.max(0, acceptedFiles - 1),
                createdAt, expiresAt, enabled
        );
    }
}
