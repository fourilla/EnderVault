package io.github.fourilla.endervault.share;

import java.time.Instant;

public record ShareLink(
        String token,
        String path,
        ShareTargetType type,
        Instant createdAt,
        Instant expiresAt,
        boolean enabled
) {
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

    public ShareLink revoke() {
        return new ShareLink(token, path, type, createdAt, expiresAt, false);
    }

    public ShareLink withPath(String path) {
        return new ShareLink(token, path, type, createdAt, expiresAt, enabled);
    }
}
