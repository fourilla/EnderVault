package io.github.fourilla.endervault.web.dashboard;

import io.github.fourilla.endervault.common.ByteSizeFormatter;
import io.github.fourilla.endervault.filerequest.FileRequest;
import io.github.fourilla.endervault.filerequest.FileRequestService;
import io.github.fourilla.endervault.metadata.MetadataInspectionReport;
import io.github.fourilla.endervault.metadata.MetadataInspectionReportStore;
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
import java.io.IOException;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import org.springframework.stereotype.Service;

@Service
public class DashboardQueryService {

    private final StorageService storageService;
    private final TrashService trashService;
    private final ShareLinkService shareLinkService;
    private final ThumbnailService thumbnailService;
    private final FileRequestService fileRequestService;
    private final MetadataInspectionReportStore reportStore;
    private final TaskManagerService taskManagerService;
    private final SessionManagementService sessionManagementService;
    private ThumbnailCacheStats cachedThumbnailStats;
    private long thumbnailSampleNanos;

    public DashboardQueryService(
            StorageService storageService,
            TrashService trashService,
            ShareLinkService shareLinkService,
            ThumbnailService thumbnailService,
            FileRequestService fileRequestService,
            MetadataInspectionReportStore reportStore,
            TaskManagerService taskManagerService,
            SessionManagementService sessionManagementService
    ) {
        this.storageService = storageService;
        this.trashService = trashService;
        this.shareLinkService = shareLinkService;
        this.thumbnailService = thumbnailService;
        this.fileRequestService = fileRequestService;
        this.reportStore = reportStore;
        this.taskManagerService = taskManagerService;
        this.sessionManagementService = sessionManagementService;
    }

    public DashboardView query() throws IOException {
        StorageUsage storageUsage = storageService.storageUsage();
        List<TrashRecord> trashRecords = trashService.list();
        List<ShareLink> shareLinks = shareLinkService.list();
        List<FileRequest> fileRequests = fileRequestService.list();
        MetadataInspectionReport report = reportStore.latest();
        Instant now = Instant.now();

        return new DashboardView(
                storageUsage,
                trashSummary(trashRecords),
                shareSummary(shareLinks),
                thumbnailSummary(thumbnailStats()),
                new DashboardView.FileRequestSummary(fileRequests.size(),
                        fileRequests.stream().filter(item -> item.usable(now)).count()),
                new DashboardView.InspectionSummary(report.present(),
                        report.present() ? report.scanReport().issueCount() : 0,
                        report.present() ? report.scanReport().scannedAt() : null),
                serverTasks(),
                sessionManagementService.activeCount(),
                now
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

    private List<DashboardTaskView> serverTasks() {
        List<DashboardTaskView> tasks = taskManagerService.listTasks().stream()
                .map(DashboardTaskView::from)
                .sorted(Comparator.comparing(DashboardTaskView::createdAt).reversed())
                .toList();
        return java.util.stream.Stream.concat(tasks.stream().filter(DashboardTaskView::active),
                tasks.stream().filter(task -> !task.active()).limit(6)).toList();
    }

    private synchronized ThumbnailCacheStats thumbnailStats() throws IOException {
        long now = System.nanoTime();
        if (cachedThumbnailStats == null || now - thumbnailSampleNanos >= 60_000_000_000L) {
            cachedThumbnailStats = thumbnailService.cacheStats();
            thumbnailSampleNanos = now;
        }
        return cachedThumbnailStats;
    }
}
