package io.github.fourilla.endervault.filetool.text;

import java.time.Instant;

public record TextDraftStatus(
        boolean exists,
        boolean active,
        boolean owned,
        boolean sourceChanged,
        Instant updatedAt,
        Instant leaseExpiresAt
) {

    public static TextDraftStatus missing() {
        return new TextDraftStatus(false, false, false, false, null, null);
    }
}
