package io.github.fourilla.endervault.web.task;

import io.github.fourilla.endervault.task.AppTask;

public record TaskPayload(
        String id,
        String shortId,
        String type,
        String typeLabel,
        String iconClass,
        String title,
        String targetPath,
        String status,
        String statusLabel,
        String statusClass,
        int progressPercent,
        String progressLabel,
        String createdLabel,
        String finishedLabel,
        String message,
        boolean active,
        boolean cancelRequested
) {

    public static TaskPayload from(AppTask task) {
        return new TaskPayload(
                task.id(),
                task.shortId(),
                task.type().name(),
                task.type().label(),
                task.type().iconClass(),
                task.title(),
                task.targetPath(),
                task.status().name(),
                task.statusLabel(),
                task.statusClass(),
                task.progressPercent(),
                task.progressLabel(),
                task.createdLabel(),
                task.finishedLabel(),
                task.message(),
                task.active(),
                task.cancelRequested()
        );
    }
}
