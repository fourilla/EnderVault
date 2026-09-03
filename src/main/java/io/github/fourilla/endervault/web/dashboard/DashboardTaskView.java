package io.github.fourilla.endervault.web.dashboard;

import io.github.fourilla.endervault.task.AppTask;
import java.time.Instant;
import java.util.Locale;

public record DashboardTaskView(
        String id,
        boolean active,
        String status,
        String title,
        String detail,
        int progressPercent,
        String progressLabel,
        Instant createdAt
) {

    public static DashboardTaskView from(AppTask task) {
        return new DashboardTaskView(
                "server-" + task.id(),
                task.active(),
                task.status().name().toLowerCase(Locale.ROOT),
                task.title(),
                task.type().label() + " #" + task.shortId(),
                task.progressPercent(),
                task.progressLabel(),
                task.createdAt()
        );
    }
}
