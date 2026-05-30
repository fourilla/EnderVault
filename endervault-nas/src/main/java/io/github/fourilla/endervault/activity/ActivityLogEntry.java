package io.github.fourilla.endervault.activity;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Map;

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
        return LABEL_FORMATTER.format(timestamp);
    }

    public String typeLabel() {
        return type == null ? "UNKNOWN" : type.replace('_', ' ');
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
}
