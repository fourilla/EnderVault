package io.github.fourilla.endervault.web.dashboard;

import io.github.fourilla.endervault.remote.RemoteDownloadSummary;
import io.github.fourilla.endervault.storage.StorageUsage;
import io.github.fourilla.endervault.task.TaskSummary;

public record DashboardView(
        StorageUsage storage,
        TrashSummary trash,
        ShareSummary shares,
        ThumbnailSummary thumbnails,
        RemoteDownloadSummary remoteDownloads,
        boolean remoteDownloadEnabled,
        TaskSummary fileTasks
) {

    public record TrashSummary(
            int count,
            long sizeBytes,
            String sizeLabel
    ) {
    }

    public record ShareSummary(
            int total,
            int active,
            int expired,
            int revoked
    ) {
    }

    public record ThumbnailSummary(
            boolean videoEnabled,
            boolean comicEnabled,
            boolean pdfEnabled,
            long cachedFiles,
            long sizeBytes,
            String sizeLabel,
            int inProgressCount
    ) {
        public String enabledLabel() {
            return videoEnabled || comicEnabled || pdfEnabled ? "Enabled" : "Disabled";
        }
    }
}
