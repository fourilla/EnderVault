package io.github.fourilla.endervault.web.dashboard;

import io.github.fourilla.endervault.common.ByteSizeFormatter;
import io.github.fourilla.endervault.remote.RemoteDownloadService;
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
import java.util.List;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;

@Controller
public class AdminDashboardController {

    private final StorageService storageService;
    private final TrashService trashService;
    private final ShareLinkService shareLinkService;
    private final ThumbnailService thumbnailService;
    private final RemoteDownloadService remoteDownloadService;
    private final TaskManagerService taskManagerService;

    public AdminDashboardController(
            StorageService storageService,
            TrashService trashService,
            ShareLinkService shareLinkService,
            ThumbnailService thumbnailService,
            RemoteDownloadService remoteDownloadService,
            TaskManagerService taskManagerService
    ) {
        this.storageService = storageService;
        this.trashService = trashService;
        this.shareLinkService = shareLinkService;
        this.thumbnailService = thumbnailService;
        this.remoteDownloadService = remoteDownloadService;
        this.taskManagerService = taskManagerService;
    }

    @GetMapping("/files/dashboard")
    public String dashboard(Model model) throws IOException {
        StorageUsage storageUsage = storageService.storageUsage();
        List<TrashRecord> trashRecords = trashService.list();
        List<ShareLink> shareLinks = shareLinkService.list();
        ThumbnailCacheStats thumbnailStats = thumbnailService.cacheStats();

        model.addAttribute("dashboard", new DashboardView(
                storageUsage,
                trashSummary(trashRecords),
                shareSummary(shareLinks),
                thumbnailSummary(thumbnailStats),
                remoteDownloadService.summary(3),
                taskManagerService.summary()
        ));
        return "dashboard";
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
                stats.cachedFiles(),
                stats.sizeBytes(),
                stats.sizeLabel(),
                stats.inProgressCount()
        );
    }
}
