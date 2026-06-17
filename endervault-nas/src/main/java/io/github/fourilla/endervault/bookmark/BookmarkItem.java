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

    public String createdLabel() {
        return format(createdAt);
    }

    public String updatedLabel() {
        return format(updatedAt);
    }

    public String lastOpenedLabel() {
        return format(lastOpenedAt);
    }

    public BookmarkItem withTitle(String newTitle, Instant updatedAt) {
        return new BookmarkItem(id, type, parentId, newTitle, url, note, createdAt, updatedAt, lastOpenedAt);
    }

    public BookmarkItem withLink(String newTitle, String newUrl, String newNote, Instant updatedAt) {
        return new BookmarkItem(id, type, parentId, newTitle, newUrl, newNote, createdAt, updatedAt, lastOpenedAt);
    }

    public BookmarkItem withLastOpenedAt(Instant openedAt) {
        return new BookmarkItem(id, type, parentId, title, url, note, createdAt, updatedAt, openedAt);
    }

    private String format(Instant value) {
        return value == null ? "-" : LABEL_FORMATTER.format(value);
    }
}
