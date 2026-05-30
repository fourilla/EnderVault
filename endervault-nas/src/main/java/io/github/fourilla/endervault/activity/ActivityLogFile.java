package io.github.fourilla.endervault.activity;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;

public record ActivityLogFile(
        String name,
        boolean current,
        long sizeBytes,
        String sizeLabel,
        Instant modifiedAt
) {
    private static final DateTimeFormatter LABEL_FORMATTER =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss").withZone(ZoneId.systemDefault());

    public String label() {
        return current ? "Current log" : name;
    }

    public String modifiedLabel() {
        return modifiedAt == null ? "-" : LABEL_FORMATTER.format(modifiedAt);
    }

    public boolean deletable() {
        return !current;
    }
}
