package io.github.fourilla.endervault.bookmark;

public record BookmarkMetadataFetchResult(
        String title,
        Favicon favicon
) {

    public boolean hasTitle() {
        return title != null && !title.isBlank();
    }

    public boolean hasFavicon() {
        return favicon != null && favicon.bytes() != null && favicon.bytes().length > 0;
    }

    public record Favicon(
            byte[] bytes,
            String contentType,
            String extension,
            String sourceUrl
    ) {
    }
}
