package io.github.fourilla.endervault.web.dashboard;

import io.github.fourilla.endervault.storage.StorageUsage;
import java.time.Instant;
import java.util.List;

public record DashboardView(
        StorageUsage storage,
        TrashSummary trash,
        ShareSummary shares,
        ThumbnailSummary thumbnails,
        FileRequestSummary fileRequests,
        InspectionSummary inspection,
        List<DashboardTaskView> serverTasks,
        int activeSessions,
        Instant updatedAt
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
    }

    public record FileRequestSummary(int total, long active) {
    }

    public record InspectionSummary(
            boolean present,
            int issues,
            Instant scannedAt
    ) {
    }
}
