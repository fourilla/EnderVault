package io.github.fourilla.endervault.filetool.text;

import java.time.Instant;
import java.util.UUID;

public record TextDraftStatus(
        boolean exists,
        UUID id,
        long revision,
        boolean active,
        boolean owned,
        boolean sourceMissing,
        boolean sourceChanged,
        Instant updatedAt,
        Instant leaseExpiresAt
) {

    public static TextDraftStatus missing() {
        return new TextDraftStatus(false, null, 0L, false, false, false, false, null, null);
    }
}
