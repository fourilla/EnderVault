package io.github.fourilla.endervault.web;

import io.github.fourilla.endervault.activity.ActivityLogEntry;
import io.github.fourilla.endervault.storage.StorageUsage;
import java.util.List;

public record DashboardView(
        StorageUsage storage,
        TrashSummary trash,
        ShareSummary shares,
        ThumbnailSummary thumbnails,
        List<ActivityLogEntry> recentActivities
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
            long cachedFiles,
            long sizeBytes,
            String sizeLabel,
            int inProgressCount
    ) {
        public String enabledLabel() {
            return videoEnabled ? "Enabled" : "Disabled";
        }
    }
}
