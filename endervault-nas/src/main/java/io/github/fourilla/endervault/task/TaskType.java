package io.github.fourilla.endervault.task;

public enum TaskType {
    FILE_COPY("File copy", "fas fa-copy"),
    FILE_MOVE("File move", "fas fa-file-import"),
    FILE_TRASH("Move to trash", "fas fa-trash-can"),
    TRASH_DELETE("Trash delete", "fas fa-trash-can"),
    TRASH_EMPTY("Empty trash", "fas fa-broom"),
    BOOKMARK_BULK_CREATE("Bookmark bulk add", "fas fa-list-ul"),
    REMOTE_DOWNLOAD("Remote download", "fas fa-cloud-arrow-down"),
    THUMBNAIL("Thumbnail", "fas fa-image"),
    METADATA_INSPECTION("Metadata inspection", "fas fa-magnifying-glass-chart");

    private final String label;
    private final String iconClass;

    TaskType(String label, String iconClass) {
        this.label = label;
        this.iconClass = iconClass;
    }

    public String label() {
        return label;
    }

    public String iconClass() {
        return iconClass;
    }
}
