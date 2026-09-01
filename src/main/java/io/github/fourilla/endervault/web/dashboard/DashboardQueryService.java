package io.github.fourilla.endervault.web.dashboard;

import io.github.fourilla.endervault.common.ByteSizeFormatter;
import io.github.fourilla.endervault.remote.RemoteDownloadService;
import io.github.fourilla.endervault.remote.RemoteDownloadSummary;
import io.github.fourilla.endervault.session.SessionManagementService;
import io.github.fourilla.endervault.share.ShareLink;
import io.github.fourilla.endervault.share.ShareLinkService;
import io.github.fourilla.endervault.storage.StorageService;
import io.github.fourilla.endervault.storage.StorageUsage;
import io.github.fourilla.endervault.task.TaskManagerService;
import io.github.fourilla.endervault.thumbnail.ThumbnailCacheStats;
import io.github.fourilla.endervault.thumbnail.ThumbnailService;
import io.github.fourilla.endervault.trash.TrashRecord;
import io.github.fourilla.endervault.trash.TrashService;
import io.github.fourilla.endervault.web.vpn.VpnRuntimeViewService;
import java.io.IOException;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;
import org.springframework.stereotype.Service;

@Service
public class DashboardQueryService {

    private final StorageService storageService;
    private final TrashService trashService;
    private final ShareLinkService shareLinkService;
    private final ThumbnailService thumbnailService;
    private final RemoteDownloadService remoteDownloadService;
    private final TaskManagerService taskManagerService;
    private final SessionManagementService sessionManagementService;
    private final VpnRuntimeViewService vpnRuntimeViewService;

    public DashboardQueryService(
            StorageService storageService,
            TrashService trashService,
            ShareLinkService shareLinkService,
            ThumbnailService thumbnailService,
            RemoteDownloadService remoteDownloadService,
            TaskManagerService taskManagerService,
            SessionManagementService sessionManagementService,
            VpnRuntimeViewService vpnRuntimeViewService
    ) {
        this.storageService = storageService;
        this.trashService = trashService;
        this.shareLinkService = shareLinkService;
        this.thumbnailService = thumbnailService;
        this.remoteDownloadService = remoteDownloadService;
        this.taskManagerService = taskManagerService;
        this.sessionManagementService = sessionManagementService;
        this.vpnRuntimeViewService = vpnRuntimeViewService;
    }

    public DashboardView query() throws IOException {
        StorageUsage storageUsage = storageService.storageUsage();
        List<TrashRecord> trashRecords = trashService.list();
        List<ShareLink> shareLinks = shareLinkService.list();
        ThumbnailCacheStats thumbnailStats = thumbnailService.cacheStats();
        RemoteDownloadSummary remoteSummary = remoteDownloadService.summary(0);

        return new DashboardView(
                storageUsage,
                trashSummary(trashRecords),
                shareSummary(shareLinks),
                thumbnailSummary(thumbnailStats),
                new DashboardView.RemoteSummary(
                        remoteSummary.total(),
                        remoteSummary.running(),
                        remoteSummary.complete(),
                        remoteSummary.failed()
                ),
                taskManagerService.summary(),
                recentTasks(6),
                sessionManagementService.activeCount(),
                vpnRuntimeViewService.current()
        );
    }

    private DashboardView.TrashSummary trashSummary(List<TrashRecord> records) {
        long sizeBytes = records.stream().mapToLong(TrashRecord::size).sum();
        return new DashboardView.TrashSummary(
                records.size(),
                sizeBytes,
                ByteSizeFormatter.humanSize(sizeBytes)
        );
    }

    private DashboardView.ShareSummary shareSummary(List<ShareLink> links) {
        Instant now = Instant.now();
        int active = 0;
        int expired = 0;
        int revoked = 0;

        for (ShareLink link : links) {
            if (!link.enabled()) {
                revoked++;
            } else if (link.expired(now)) {
                expired++;
            } else {
                active++;
            }
        }
        return new DashboardView.ShareSummary(links.size(), active, expired, revoked);
    }

    private DashboardView.ThumbnailSummary thumbnailSummary(ThumbnailCacheStats stats) {
        return new DashboardView.ThumbnailSummary(
                stats.videoEnabled(),
                stats.comicEnabled(),
                stats.pdfEnabled(),
                stats.cachedFiles(),
                stats.sizeBytes(),
                stats.sizeLabel(),
                stats.inProgressCount()
        );
    }

    private List<DashboardTaskView> recentTasks(int limit) {
        Stream<DashboardTaskView> appTasks = taskManagerService.listTasks().stream()
                .map(DashboardTaskView::from);
        Stream<DashboardTaskView> remoteTasks = remoteDownloadService.listTasks().stream()
                .map(DashboardTaskView::from);
        return Stream.concat(appTasks, remoteTasks)
                .sorted(Comparator.comparing(DashboardTaskView::createdAt).reversed())
                .limit(Math.max(0, limit))
                .toList();
    }
}
