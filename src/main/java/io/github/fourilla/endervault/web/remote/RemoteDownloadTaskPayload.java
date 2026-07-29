package io.github.fourilla.endervault.web.remote;

import io.github.fourilla.endervault.remote.RemoteDownloadTask;

public record RemoteDownloadTaskPayload(
        String id,
        String shortId,
        String sourceUrl,
        String targetDirectory,
        String targetPath,
        String networkRoute,
        String networkRouteLabel,
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

    public static RemoteDownloadTaskPayload from(RemoteDownloadTask task) {
        return new RemoteDownloadTaskPayload(
                task.id(),
                task.shortId(),
                task.sourceUrl(),
                task.targetDirectory(),
                task.targetPath(),
                task.networkRoute().settingValue(),
                task.networkRouteLabel(),
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
