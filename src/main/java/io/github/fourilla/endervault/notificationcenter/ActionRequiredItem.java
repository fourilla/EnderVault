package io.github.fourilla.endervault.notificationcenter;

import java.time.Instant;

public record ActionRequiredItem(
        String id,
        String type,
        String title,
        String detail,
        Instant createdAt,
        String href,
        Target target
) {
    public ActionRequiredItem(String id, String type, String title, String detail, Instant createdAt, String href) {
        this(id, type, title, detail, createdAt, href, null);
    }

    public enum TargetKind { DIRECTORY_MERGE, PENDING_FILE_DECISION }
    public record Target(TargetKind kind, String id) {}
}
