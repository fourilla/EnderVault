package io.github.fourilla.endervault.recent;

import io.github.fourilla.endervault.storage.FileItem;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;

public record RecentListItem(
        FileItem item,
        Instant lastAccessedAt
) {
    private static final DateTimeFormatter LABEL_FORMATTER =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss").withZone(ZoneId.systemDefault());

    public String name() {
        return item.name();
    }

    public String path() {
        return item.path();
    }

    public boolean directory() {
        return item.directory();
    }

    public long size() {
        return item.size();
    }

    public String sizeLabel() {
        return item.sizeLabel();
    }

    public String modifiedLabel() {
        return item.modifiedLabel();
    }

    public Instant modifiedAt() {
        return item.modifiedAt();
    }

    public String mediaType() {
        return item.mediaType();
    }

    public boolean previewable() {
        return item.previewable();
    }

    public boolean streamable() {
        return item.streamable();
    }

    public boolean hidden() {
        return item.hidden();
    }

    public boolean image() {
        return item.image();
    }

    public boolean video() {
        return item.video();
    }

    public boolean comic() {
        return item.comic();
    }

    public boolean text() {
        return item.text();
    }

    public boolean pdf() {
        return item.pdf();
    }

    public String parentPath() {
        return item.parentPath();
    }

    public String typeLabel() {
        return item.typeLabel();
    }

    public String extensionLabel() {
        return item.extensionLabel();
    }

    public String accessedLabel() {
        return LABEL_FORMATTER.format(lastAccessedAt);
    }
}
