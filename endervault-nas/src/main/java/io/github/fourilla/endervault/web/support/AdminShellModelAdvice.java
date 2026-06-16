package io.github.fourilla.endervault.web.support;

import io.github.fourilla.endervault.favorite.FavoriteItem;
import io.github.fourilla.endervault.favorite.FavoriteService;
import io.github.fourilla.endervault.storage.StorageService;
import io.github.fourilla.endervault.storage.StorageUsage;
import io.github.fourilla.endervault.web.dashboard.AdminDashboardController;
import io.github.fourilla.endervault.web.dashboard.AdminLogController;
import io.github.fourilla.endervault.web.auth.AdminPasskeyController;
import io.github.fourilla.endervault.web.file.AdminBookmarkController;
import io.github.fourilla.endervault.web.file.AdminFavoriteController;
import io.github.fourilla.endervault.web.file.AdminFileController;
import io.github.fourilla.endervault.web.file.AdminRecentController;
import io.github.fourilla.endervault.web.remote.AdminRemoteDownloadController;
import io.github.fourilla.endervault.web.share.AdminShareController;
import io.github.fourilla.endervault.web.trash.AdminTrashController;
import java.util.List;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ModelAttribute;

@ControllerAdvice(assignableTypes = {
        AdminDashboardController.class,
        AdminPasskeyController.class,
        AdminBookmarkController.class,
        AdminFileController.class,
        AdminFavoriteController.class,
        AdminRecentController.class,
        AdminLogController.class,
        AdminRemoteDownloadController.class,
        AdminShareController.class,
        AdminTrashController.class
})
public class AdminShellModelAdvice {

    private final StorageService storageService;
    private final FavoriteService favoriteService;
    private final FilePreviewSupport filePreviewSupport;

    public AdminShellModelAdvice(
            StorageService storageService,
            FavoriteService favoriteService,
            FilePreviewSupport filePreviewSupport
    ) {
        this.storageService = storageService;
        this.favoriteService = favoriteService;
        this.filePreviewSupport = filePreviewSupport;
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
}
