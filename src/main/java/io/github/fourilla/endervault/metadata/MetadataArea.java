package io.github.fourilla.endervault.metadata;

import java.util.Collection;
import java.util.List;
import java.util.Objects;

public enum MetadataArea {
    FAVORITES("Favorites", "Saved vault shortcuts", "fas fa-star"),
    RECENT("Recent", "Recently accessed vault items", "fas fa-clock-rotate-left"),
    SHARE_LINKS("Shared links", "Issued shared file and directory links", "fas fa-link"),
    TRASH("Trash", "Trash records and files stored under .trash", "fas fa-trash-can"),
    THUMBNAILS("Thumbnails", "Generated video and comic thumbnail cache", "fas fa-image"),
    FILE_STAGING("File staging", "Temporary files used by upload and file operations", "fas fa-file-circle-check"),
    ARCHIVE_STAGING("Archive staging", "Temporary workspaces used to create and extract archives", "fas fa-box-archive"),
    TEXT_DRAFTS("Text drafts", "Recoverable text editor drafts and leases", "fas fa-file-pen"),
    BOOKMARKS("Bookmarks", "Saved web links and bookmark directories", "fas fa-bookmark"),
    STICKY_NOTES("Sticky notes", "Page and item notes whose targets may have moved or disappeared", "fas fa-note-sticky"),
    FILE_REQUESTS("File requests", "Public upload request records and destination directories", "fas fa-inbox"),
    PENDING_FILE_DECISIONS("Pending decisions", "Fully received files waiting for an administrator decision", "fas fa-bell");

    private final String label;
    private final String description;
    private final String iconClass;

    MetadataArea(String label, String description, String iconClass) {
        this.label = label;
        this.description = description;
        this.iconClass = iconClass;
    }

    public String label() {
        return label;
    }

    public String description() {
        return description;
    }

    public String iconClass() {
        return iconClass;
    }

    public static List<MetadataArea> selected(Collection<String> values) {
        if (values == null || values.isEmpty()) {
            return List.of(values());
        }
        return values.stream()
                .map(MetadataArea::fromOrNull)
                .filter(Objects::nonNull)
                .distinct()
                .toList();
    }

    private static MetadataArea fromOrNull(String value) {
        for (MetadataArea area : values()) {
            if (area.name().equalsIgnoreCase(value)) {
                return area;
            }
        }
        return null;
    }
}
