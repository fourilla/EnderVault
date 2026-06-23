package io.github.fourilla.endervault.activity;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Map;
import java.util.stream.Collectors;

public record ActivityLogEntry(
        String id,
        Instant timestamp,
        String type,
        String actor,
        String ip,
        String path,
        String targetPath,
        boolean success,
        String message,
        Map<String, String> metadata
) {
    private static final DateTimeFormatter LABEL_FORMATTER =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss").withZone(ZoneId.systemDefault());

    public String timestampLabel() {
        return timestamp == null ? "-" : LABEL_FORMATTER.format(timestamp);
    }

    public String typeLabel() {
        return safeType().replace('_', ' ');
    }

    public String statusLabel() {
        return success ? "Success" : "Failed";
    }

    public String statusClass() {
        return success ? "active" : "revoked";
    }

    public String displayMessage() {
        String detail = message == null || message.isBlank() ? "-" : message;
        return "[%s] %s : %s".formatted(timestampLabel(), typeLabel(), detail);
    }

    public Instant timestampForSort() {
        return timestamp == null ? Instant.EPOCH : timestamp;
    }

    public String safeType() {
        return type == null || type.isBlank() ? "UNKNOWN" : type;
    }

    public String actorLabel() {
        return blankToDash(actor);
    }

    public String ipLabel() {
        return blankToDash(ip);
    }

    public String pathLabel() {
        return blankToDash(path);
    }

    public String targetPathLabel() {
        return blankToDash(targetPath);
    }

    public String messageLabel() {
        return blankToDash(message);
    }

    public Map<String, String> metadataView() {
        return metadata == null ? Map.of() : metadata;
    }

    public boolean hasMetadata() {
        return !metadataView().isEmpty();
    }

    public String metadataLabel() {
        if (!hasMetadata()) {
            return "-";
        }
        return metadataView().entrySet().stream()
                .map(entry -> entry.getKey() + "=" + entry.getValue())
                .collect(Collectors.joining(", "));
    }

    public String detailLine() {
        return "[%s] %s %s actor=%s ip=%s path=%s target=%s message=%s metadata={%s} id=%s"
                .formatted(
                        timestampLabel(),
                        statusLabel(),
                        safeType(),
                        actorLabel(),
                        ipLabel(),
                        pathLabel(),
                        targetPathLabel(),
                        messageLabel(),
                        metadataLabel(),
                        blankToDash(id)
                );
    }

    public String searchText() {
        return String.join(" ",
                blankToDash(id),
                timestampLabel(),
                safeType(),
                statusLabel(),
                actorLabel(),
                ipLabel(),
                pathLabel(),
                targetPathLabel(),
                messageLabel(),
                metadataLabel()
        );
    }

    private static String blankToDash(String value) {
        return value == null || value.isBlank() ? "-" : value;
    }
}
