package io.github.fourilla.endervault.favorite;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;

public record FavoriteItem(
        String path,
        FavoriteTargetType type,
        Instant createdAt
) {
    private static final DateTimeFormatter LABEL_FORMATTER =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss").withZone(ZoneId.systemDefault());

    public boolean directory() {
        return type == FavoriteTargetType.DIRECTORY;
    }

    public String name() {
        if (path == null || path.isBlank()) {
            return "Root";
        }
        int index = path.lastIndexOf('/');
        return index < 0 ? path : path.substring(index + 1);
    }

    public String typeLabel() {
        return directory() ? "Directory" : "File";
    }

    public String iconClass() {
        return directory() ? "fas fa-folder" : "fas fa-file";
    }

    public String createdLabel() {
        return LABEL_FORMATTER.format(createdAt);
    }

    public FavoriteItem withPath(String newPath) {
        return new FavoriteItem(newPath, type, createdAt);
    }
}
