package io.github.fourilla.endervault.trash;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;

public record TrashRecord(
        String id,
        String originalPath,
        String originalParentPath,
        String originalName,
        String trashName,
        boolean directory,
        long size,
        String sizeLabel,
        String typeLabel,
        Instant deletedAt,
        Instant expiresAt
) {

    private static final DateTimeFormatter LABEL_FORMATTER =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss").withZone(ZoneId.systemDefault());

    public boolean expired(Instant now) {
        return expiresAt != null && !expiresAt.isAfter(now);
    }

    public String deletedLabel() {
        return LABEL_FORMATTER.format(deletedAt);
    }

    public String expiresLabel() {
        return expiresAt == null ? "Never" : LABEL_FORMATTER.format(expiresAt);
    }
}
