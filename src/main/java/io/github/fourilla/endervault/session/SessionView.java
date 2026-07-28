package io.github.fourilla.endervault.session;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;

public record SessionView(
        String managementId,
        String username,
        String ip,
        String userAgent,
        String deviceLabel,
        String authMethod,
        Instant createdAt,
        Instant lastActiveAt,
        Instant expiresAt,
        boolean current
) {
    private static final DateTimeFormatter LABEL_FORMATTER =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss").withZone(ZoneId.systemDefault());

    public String createdLabel() {
        return format(createdAt);
    }

    public String lastActiveLabel() {
        return format(lastActiveAt);
    }

    public String expiresLabel() {
        return expiresAt == null ? "Never" : format(expiresAt);
    }

    public String authMethodLabel() {
        return "passkey".equalsIgnoreCase(authMethod) ? "Passkey" : "Password";
    }

    private String format(Instant value) {
        return value == null ? "-" : LABEL_FORMATTER.format(value);
    }
}
