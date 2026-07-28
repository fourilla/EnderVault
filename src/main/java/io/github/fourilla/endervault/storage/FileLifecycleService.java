package io.github.fourilla.endervault.storage;

import io.github.fourilla.endervault.activity.ActivityLogService;
import io.github.fourilla.endervault.favorite.FavoriteService;
import io.github.fourilla.endervault.filetool.text.TextDraftService;
import io.github.fourilla.endervault.recent.RecentService;
import io.github.fourilla.endervault.share.ShareLinkService;
import io.github.fourilla.endervault.thumbnail.ThumbnailService;
import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.util.Map;
import org.springframework.stereotype.Service;

@Service
public class FileLifecycleService {

    private final StorageService storageService;
    private final ShareLinkService shareLinkService;
    private final FavoriteService favoriteService;
    private final RecentService recentService;
    private final ThumbnailService thumbnailService;
    private final TextDraftService textDraftService;
    private final ActivityLogService activityLogService;

    public FileLifecycleService(
            StorageService storageService,
            ShareLinkService shareLinkService,
            FavoriteService favoriteService,
            RecentService recentService,
            ThumbnailService thumbnailService,
            TextDraftService textDraftService,
            ActivityLogService activityLogService
    ) {
        this.storageService = storageService;
        this.shareLinkService = shareLinkService;
        this.favoriteService = favoriteService;
        this.recentService = recentService;
        this.thumbnailService = thumbnailService;
        this.textDraftService = textDraftService;
        this.activityLogService = activityLogService;
    }

    public void recordRename(HttpServletRequest request, String oldPath, String newPath, String message)
            throws IOException {
        afterVaultPathMoved(oldPath, newPath);
        activityLogService.record("RENAME", request, oldPath, newPath, message);
    }

    public void recordMove(HttpServletRequest request, String oldPath, String newPath, String message)
            throws IOException {
        afterVaultPathMoved(oldPath, newPath);
        activityLogService.record("MOVE", request, oldPath, newPath, message);
    }

    public void recordHiddenChange(
            HttpServletRequest request,
            String oldPath,
            String newPath,
            boolean hidden,
            String message
    ) throws IOException {
        if (!oldPath.equals(newPath)) {
            afterVaultPathMoved(oldPath, newPath);
        }
        activityLogService.record(
                "HIDDEN_CHANGE",
                request,
                oldPath,
                newPath,
                message,
                Map.of("hidden", String.valueOf(hidden))
        );
    }

    public void recordMove(String actor, String ip, String oldPath, String newPath, String message)
            throws IOException {
        afterVaultPathMoved(oldPath, newPath);
        activityLogService.record("MOVE", actor, ip, oldPath, newPath, true, message, Map.of());
    }

    public void recordCopy(HttpServletRequest request, String oldPath, String newPath, String message) {
        activityLogService.record("COPY", request, oldPath, newPath, message);
    }

    public void recordCopy(String actor, String ip, String oldPath, String newPath, String message) {
        activityLogService.record("COPY", actor, ip, oldPath, newPath, true, message, Map.of());
    }

    public void recordFailedTransfer(
            String type,
            HttpServletRequest request,
            String path,
            String targetPath,
            String message,
            Exception failure
    ) {
        activityLogService.record(
                type,
                request,
                path,
                targetPath,
                false,
                message,
                Map.of("reason", failure.getClass().getSimpleName())
        );
    }

    public void recordFailedTransfer(
            String type,
            String actor,
            String ip,
            String path,
            String targetPath,
            String message,
            Exception failure
    ) {
        activityLogService.record(
                type,
                actor,
                ip,
                path,
                targetPath,
                false,
                message,
                Map.of("reason", failure.getClass().getSimpleName())
        );
    }

    private void afterVaultPathMoved(String oldPath, String newPath) throws IOException {
        thumbnailService.migrateThumbnails(storageService.resolveVaultPath(newPath), oldPath, newPath);
        shareLinkService.moveVaultPath(oldPath, newPath);
        favoriteService.moveVaultPath(oldPath, newPath);
        recentService.moveVaultPath(oldPath, newPath);
        textDraftService.moveVaultPath(oldPath, newPath);
    }
}
