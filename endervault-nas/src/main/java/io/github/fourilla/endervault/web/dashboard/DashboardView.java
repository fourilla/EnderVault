package io.github.fourilla.endervault.web.dashboard;

import io.github.fourilla.endervault.remote.RemoteDownloadSummary;
import io.github.fourilla.endervault.storage.StorageUsage;

public record DashboardView(
        StorageUsage storage,
        TrashSummary trash,
        ShareSummary shares,
        ThumbnailSummary thumbnails,
        RemoteDownloadSummary remoteDownloads
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
            long cachedFiles,
            long sizeBytes,
            String sizeLabel,
            int inProgressCount
    ) {
        public String enabledLabel() {
            return videoEnabled || comicEnabled ? "Enabled" : "Disabled";
        }
    }
}
