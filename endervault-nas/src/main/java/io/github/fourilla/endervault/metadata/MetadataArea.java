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
    UPLOAD_TEMP("Upload temp", "Temporary files left by staged uploads", "fas fa-upload"),
    BOOKMARKS("Bookmarks", "Saved web links and bookmark directories", "fas fa-bookmark");

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
