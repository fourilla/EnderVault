package io.github.fourilla.endervault.bookmark;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class BookmarkLogMetadata {

    private BookmarkLogMetadata() {
    }

    public static Map<String, String> single(BookmarkItem bookmark, String type) {
        Map<String, String> metadata = new LinkedHashMap<>();
        metadata.put("bookmarkId", bookmark.id());
        metadata.put("type", type);
        metadata.put("titleSource", String.valueOf(bookmark.effectiveTitleSource()));
        metadata.put("metadataFetchAttempted", String.valueOf(metadataFetchAttempted(bookmark)));
        metadata.put("metadataFetchStatus", metadataFetchStatus(bookmark));
        metadata.put("faviconAvailable", String.valueOf(bookmark.faviconAvailable()));
        return metadata;
    }

    public static Map<String, String> bulk(
            int requestedCount,
            List<BookmarkItem> bookmarks,
            int failedCount
    ) {
        List<BookmarkItem> safeBookmarks = bookmarks == null ? List.of() : bookmarks;
        Map<String, String> metadata = new LinkedHashMap<>();
        metadata.put("requestedCount", String.valueOf(requestedCount));
        metadata.put("count", String.valueOf(safeBookmarks.size()));
        metadata.put("failedCount", String.valueOf(failedCount));
        metadata.put("metadataFetchAttemptedCount", String.valueOf(safeBookmarks.stream()
                .filter(BookmarkLogMetadata::metadataFetchAttempted)
                .count()));
        metadata.put("metadataFetchOkCount", String.valueOf(safeBookmarks.stream()
                .filter(bookmark -> "OK".equals(metadataFetchStatus(bookmark)))
                .count()));
        metadata.put("faviconAvailableCount", String.valueOf(safeBookmarks.stream()
                .filter(BookmarkItem::faviconAvailable)
                .count()));
        metadata.put("manualTitleCount", String.valueOf(safeBookmarks.stream()
                .filter(bookmark -> "MANUAL".equals(String.valueOf(bookmark.effectiveTitleSource())))
                .count()));
        metadata.put("urlDerivedTitleCount", String.valueOf(safeBookmarks.stream()
                .filter(bookmark -> "URL_DERIVED".equals(String.valueOf(bookmark.effectiveTitleSource())))
                .count()));
        metadata.put("remoteTitleCount", String.valueOf(safeBookmarks.stream()
                .filter(bookmark -> "REMOTE_TITLE".equals(String.valueOf(bookmark.effectiveTitleSource())))
                .count()));
        return metadata;
    }

    public static boolean metadataFetchAttempted(BookmarkItem bookmark) {
        return bookmark != null
                && (bookmark.metadataFetchedAt() != null
                || (bookmark.metadataFetchStatus() != null && !bookmark.metadataFetchStatus().isBlank()));
    }

    public static String metadataFetchStatus(BookmarkItem bookmark) {
        if (!metadataFetchAttempted(bookmark)) {
            return "SKIPPED";
        }
        String status = bookmark.metadataFetchStatus();
        return status == null || status.isBlank() ? "UNKNOWN" : status;
    }
}
