package io.github.fourilla.endervault.web.support;

import io.github.fourilla.endervault.config.NasProperties;
import io.github.fourilla.endervault.favorite.FavoriteItem;
import io.github.fourilla.endervault.favorite.FavoriteService;
import io.github.fourilla.endervault.storage.StorageService;
import io.github.fourilla.endervault.storage.StorageUsage;
import io.github.fourilla.endervault.web.dashboard.AdminDashboardController;
import io.github.fourilla.endervault.web.dashboard.AdminLogController;
import io.github.fourilla.endervault.web.dashboard.AdminTelegramSettingsController;
import io.github.fourilla.endervault.web.auth.AdminPasskeyController;
import io.github.fourilla.endervault.web.file.AdminBookmarkController;
import io.github.fourilla.endervault.web.file.AdminFavoriteController;
import io.github.fourilla.endervault.web.file.AdminFileController;
import io.github.fourilla.endervault.web.file.AdminFileDetailController;
import io.github.fourilla.endervault.web.file.AdminFileMutationController;
import io.github.fourilla.endervault.web.file.AdminFileShareController;
import io.github.fourilla.endervault.web.file.AdminFileTransferController;
import io.github.fourilla.endervault.web.file.AdminRecentController;
import io.github.fourilla.endervault.web.metadata.AdminMetadataController;
import io.github.fourilla.endervault.web.remote.AdminRemoteDownloadController;
import io.github.fourilla.endervault.web.settings.AdminGeneralSettingsController;
import io.github.fourilla.endervault.web.settings.AdminSettingsController;
import io.github.fourilla.endervault.web.share.AdminShareController;
import io.github.fourilla.endervault.web.trash.AdminTrashController;
import java.util.List;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ModelAttribute;

@ControllerAdvice(assignableTypes = {
        AdminDashboardController.class,
        AdminTelegramSettingsController.class,
        AdminPasskeyController.class,
        AdminBookmarkController.class,
        AdminFileController.class,
        AdminFileDetailController.class,
        AdminFileMutationController.class,
        AdminFileShareController.class,
        AdminFileTransferController.class,
        AdminFavoriteController.class,
        AdminRecentController.class,
        AdminLogController.class,
        AdminRemoteDownloadController.class,
        AdminShareController.class,
        AdminTrashController.class,
        AdminSettingsController.class,
        AdminGeneralSettingsController.class,
        AdminMetadataController.class
})
public class AdminShellModelAdvice {

    private final StorageService storageService;
    private final FavoriteService favoriteService;
    private final FilePreviewSupport filePreviewSupport;
    private final FileActionViewSupport fileActionViewSupport;
    private final NasProperties nasProperties;

    public AdminShellModelAdvice(
            StorageService storageService,
            FavoriteService favoriteService,
            FilePreviewSupport filePreviewSupport,
            FileActionViewSupport fileActionViewSupport,
            NasProperties nasProperties
    ) {
        this.storageService = storageService;
        this.favoriteService = favoriteService;
        this.filePreviewSupport = filePreviewSupport;
        this.fileActionViewSupport = fileActionViewSupport;
        this.nasProperties = nasProperties;
    }

    @ModelAttribute("storageUsage")
    public StorageUsage storageUsage() {
        return storageService.storageUsage();
    }

    @ModelAttribute("favorites")
    public List<FavoriteItem> favorites() {
        try {
            return favoriteService.listExisting();
        } catch (Exception ex) {
            return List.of();
        }
    }

    @ModelAttribute("filePreview")
    public FilePreviewSupport filePreview() {
        return filePreviewSupport;
    }

    @ModelAttribute("fileActions")
    public FileActionViewSupport fileActions() {
        return fileActionViewSupport;
    }

    @ModelAttribute("taskUiConfig")
    public TaskUiConfig taskUiConfig() {
        NasProperties.Tasks tasks = nasProperties.getTasks();
        return new TaskUiConfig(
                tasks.isActivityPanelEnabled(),
                tasks.getCompletedDisplayMs(),
                tasks.getFailedDisplayMs()
        );
    }

    @ModelAttribute("uploadUiConfig")
    public UploadUiConfig uploadUiConfig() {
        NasProperties.Upload upload = nasProperties.getUpload();
        return new UploadUiConfig(
                upload.getMaxFilesPerRequest(),
                upload.isDirectoryUploadEnabled()
        );
    }

    public record TaskUiConfig(
            boolean activityPanelEnabled,
            int completedDisplayMs,
            int failedDisplayMs
    ) {
    }

    public record UploadUiConfig(
            int maxFilesPerRequest,
            boolean directoryUploadEnabled
    ) {
    }
}
