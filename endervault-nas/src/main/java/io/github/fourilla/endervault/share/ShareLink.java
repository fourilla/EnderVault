package io.github.fourilla.endervault.share;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;

public record ShareLink(
        String token,
        String path,
        ShareTargetType type,
        Instant createdAt,
        Instant expiresAt,
        boolean enabled
) {
    private static final DateTimeFormatter LABEL_FORMATTER =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss").withZone(ZoneId.systemDefault());

    public boolean expired(Instant now) {
        return expiresAt != null && !expiresAt.isAfter(now);
    }

    public boolean usable(Instant now) {
        return enabled && !expired(now);
    }

    public boolean active() {
        return usable(Instant.now());
    }

    public String statusLabel() {
        if (!enabled) {
            return "Revoked";
        }
        if (expired(Instant.now())) {
            return "Expired";
        }
        return "Active";
    }

    public String statusClass() {
        if (!enabled) {
            return "revoked";
        }
        if (expired(Instant.now())) {
            return "expired";
        }
        return "active";
    }

    public String createdLabel() {
        return LABEL_FORMATTER.format(createdAt);
    }

    public String expiresLabel() {
        return expiresAt == null ? "Never" : LABEL_FORMATTER.format(expiresAt);
    }

    public ShareLink revoke() {
        return new ShareLink(token, path, type, createdAt, expiresAt, false);
    }

    public ShareLink withPath(String path) {
        return new ShareLink(token, path, type, createdAt, expiresAt, enabled);
    }
}
