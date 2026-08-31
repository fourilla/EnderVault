package io.github.fourilla.endervault.web.dashboard;

import io.github.fourilla.endervault.storage.StorageUsage;
import io.github.fourilla.endervault.task.TaskSummary;
import io.github.fourilla.endervault.web.support.VpnRuntimeStatusView;
import java.util.List;

public record DashboardView(
        StorageUsage storage,
        TrashSummary trash,
        ShareSummary shares,
        ThumbnailSummary thumbnails,
        RemoteSummary remoteDownloads,
        TaskSummary appTasks,
        List<DashboardTaskView> recentTasks,
        int activeSessions,
        VpnRuntimeStatusView vpn
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

    public record RemoteSummary(
            long total,
            long running,
            long complete,
            long failed
    ) {
    }
}
