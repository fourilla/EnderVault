package io.github.fourilla.endervault.web.bookmark;

import java.util.List;
import org.springframework.http.MediaType;
import org.springframework.web.util.UriComponentsBuilder;

public final class BookmarkRoutes {

    private BookmarkRoutes() {
    }

    public static String bookmarksUrl(String directoryId, String query) {
        UriComponentsBuilder builder = UriComponentsBuilder.fromPath("/files/bookmarks");
        String normalizedDirectoryId = normalizeId(directoryId);
        if (normalizedDirectoryId != null) {
            builder.queryParam("directory", normalizedDirectoryId);
        }
        String normalizedQuery = normalizeQuery(query);
        if (!normalizedQuery.isBlank()) {
            builder.queryParam("q", normalizedQuery);
        }
        return builder.build().encode().toUriString();
    }

    public static String redirectToBookmarks(String directoryId, String query) {
        return "redirect:" + bookmarksUrl(directoryId, query);
    }

    public static String bookmarkDetailUrl(String id) {
        return UriComponentsBuilder.fromPath("/files/bookmarks/detail")
                .queryParam("id", id)
                .build()
                .encode()
                .toUriString();
    }

    public static String redirectToBookmarkDetail(String id) {
        return "redirect:" + bookmarkDetailUrl(id);
    }

    public static String normalizeId(String id) {
        return id == null || id.isBlank() ? null : id.trim();
    }

    public static String normalizeQuery(String query) {
        return query == null ? "" : query.trim();
    }

    public static List<String> safeIds(List<String> ids) {
        if (ids == null) {
            return List.of();
        }
        return ids.stream()
                .filter(id -> id != null && !id.isBlank())
                .map(String::trim)
                .distinct()
                .toList();
    }

    static MediaType mediaType(String contentType) {
        try {
            return contentType == null || contentType.isBlank()
                    ? MediaType.APPLICATION_OCTET_STREAM
                    : MediaType.parseMediaType(contentType);
        } catch (IllegalArgumentException ex) {
            return MediaType.APPLICATION_OCTET_STREAM;
        }
    }
}
