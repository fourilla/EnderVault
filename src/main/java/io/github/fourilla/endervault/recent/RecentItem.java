package io.github.fourilla.endervault.recent;

import java.time.Instant;

public record RecentItem(
        String path,
        RecentTargetType type,
        Instant lastAccessedAt
) {
    public boolean directory() {
        return type == RecentTargetType.DIRECTORY;
    }

    public RecentItem accessedNow() {
        return new RecentItem(path, type, Instant.now());
    }

    public RecentItem withPath(String newPath) {
        return new RecentItem(newPath, type, lastAccessedAt);
    }
}
