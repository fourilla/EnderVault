package io.github.fourilla.endervault.bookmark;

import java.time.Instant;

public record BookmarkFaviconCacheEntry(
        String sourceUrl,
        String fileName,
        String contentType,
        Instant fetchedAt
) {
}
