package io.github.fourilla.endervault.bookmark;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;

public record BookmarkItem(
        String id,
        BookmarkItemType type,
        String parentId,
        String title,
        String url,
        String note,
        BookmarkTitleSource titleSource,
        String faviconFileName,
        String faviconContentType,
        Instant metadataFetchedAt,
        String metadataFetchStatus,
        Instant createdAt,
        Instant updatedAt,
        Instant lastOpenedAt
) {
    private static final DateTimeFormatter LABEL_FORMATTER =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss").withZone(ZoneId.systemDefault());

    public boolean directory() {
        return type == BookmarkItemType.DIRECTORY;
    }

    public boolean link() {
        return type == BookmarkItemType.LINK;
    }

    public boolean externalLink() {
        return link() && url != null && !url.isBlank() && !url.startsWith("/");
    }

    public String typeLabel() {
        return directory() ? "Directory" : "Link";
    }

    public String iconClass() {
        return directory() ? "fas fa-folder" : "fas fa-link";
    }

    public String safeUrl() {
        return url == null || url.isBlank() ? "-" : url;
    }

    public String safeNote() {
        return note == null || note.isBlank() ? "" : note;
    }

    public BookmarkTitleSource effectiveTitleSource() {
        return titleSource == null ? BookmarkTitleSource.MANUAL : titleSource;
    }

    public boolean faviconAvailable() {
        return faviconFileName != null && !faviconFileName.isBlank();
    }

    public String createdLabel() {
        return format(createdAt);
    }

    public String updatedLabel() {
        return format(updatedAt);
    }

    public String lastOpenedLabel() {
        return format(lastOpenedAt);
    }

    public String metadataFetchedLabel() {
        return format(metadataFetchedAt);
    }

    public BookmarkItem withTitle(String newTitle, Instant updatedAt) {
        return new BookmarkItem(
                id,
                type,
                parentId,
                newTitle,
                url,
                note,
                BookmarkTitleSource.MANUAL,
                faviconFileName,
                faviconContentType,
                metadataFetchedAt,
                metadataFetchStatus,
                createdAt,
                updatedAt,
                lastOpenedAt
        );
    }

    public BookmarkItem withLink(
            String newTitle,
            String newUrl,
            String newNote,
            BookmarkTitleSource newTitleSource,
            Instant updatedAt
    ) {
        return new BookmarkItem(
                id,
                type,
                parentId,
                newTitle,
                newUrl,
                newNote,
                newTitleSource,
                faviconFileName,
                faviconContentType,
                metadataFetchedAt,
                metadataFetchStatus,
                createdAt,
                updatedAt,
                lastOpenedAt
        );
    }

    public BookmarkItem withRemoteMetadata(
            String newTitle,
            BookmarkTitleSource newTitleSource,
            String newFaviconFileName,
            String newFaviconContentType,
            Instant fetchedAt,
            String status,
            Instant updatedAt
    ) {
        return new BookmarkItem(
                id,
                type,
                parentId,
                newTitle,
                url,
                note,
                newTitleSource,
                newFaviconFileName,
                newFaviconContentType,
                fetchedAt,
                status,
                createdAt,
                updatedAt,
                lastOpenedAt
        );
    }

    public BookmarkItem withLastOpenedAt(Instant openedAt) {
        return new BookmarkItem(
                id,
                type,
                parentId,
                title,
                url,
                note,
                titleSource,
                faviconFileName,
                faviconContentType,
                metadataFetchedAt,
                metadataFetchStatus,
                createdAt,
                updatedAt,
                openedAt
        );
    }

    public BookmarkItem withParentId(String newParentId, Instant updatedAt) {
        return new BookmarkItem(
                id,
                type,
                newParentId,
                title,
                url,
                note,
                titleSource,
                faviconFileName,
                faviconContentType,
                metadataFetchedAt,
                metadataFetchStatus,
                createdAt,
                updatedAt,
                lastOpenedAt
        );
    }

    private String format(Instant value) {
        return value == null ? "-" : LABEL_FORMATTER.format(value);
    }
}
