package io.github.fourilla.endervault.notificationcenter;

import java.time.Instant;

public record ActionRequiredItem(
        String id,
        String type,
        String title,
        String detail,
        Instant createdAt,
        String href
) {
}
