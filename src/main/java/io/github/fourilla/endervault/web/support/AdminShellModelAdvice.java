package io.github.fourilla.endervault.web.support;

import io.github.fourilla.endervault.favorite.FavoriteDisplayItem;
import io.github.fourilla.endervault.storage.StorageUsage;
import io.github.fourilla.endervault.web.dashboard.AdminDashboardController;
import io.github.fourilla.endervault.web.dashboard.AdminLogController;
import io.github.fourilla.endervault.web.dashboard.AdminSessionController;
import io.github.fourilla.endervault.web.bookmark.AdminBookmarkController;
import io.github.fourilla.endervault.web.file.AdminFavoriteController;
import io.github.fourilla.endervault.web.file.AdminFileController;
import io.github.fourilla.endervault.web.file.AdminFileDetailController;
import io.github.fourilla.endervault.web.file.AdminFileTransferController;
import io.github.fourilla.endervault.web.file.AdminRecentController;
import io.github.fourilla.endervault.web.filerequest.AdminFileRequestController;
import io.github.fourilla.endervault.web.metadata.AdminMetadataController;
import io.github.fourilla.endervault.web.pending.AdminPendingFileDecisionController;
import io.github.fourilla.endervault.web.remote.AdminRemoteDownloadController;
import io.github.fourilla.endervault.web.settings.AdminSettingsController;
import io.github.fourilla.endervault.web.share.AdminShareController;
import io.github.fourilla.endervault.web.stickynote.AdminStickyNoteController;
import io.github.fourilla.endervault.web.trash.AdminTrashController;
import io.github.fourilla.endervault.web.vpn.AdminVpnController;
import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ModelAttribute;

@ControllerAdvice(basePackageClasses = AdminSettingsController.class, assignableTypes = {
        AdminDashboardController.class,
        AdminBookmarkController.class,
        AdminFileController.class,
        AdminFileDetailController.class,
        AdminFileTransferController.class,
        AdminFavoriteController.class,
        AdminRecentController.class,
        AdminFileRequestController.class,
        AdminLogController.class,
        AdminSessionController.class,
        AdminRemoteDownloadController.class,
        AdminShareController.class,
        AdminTrashController.class,
        AdminVpnController.class,
        AdminMetadataController.class,
        AdminPendingFileDecisionController.class,
        AdminStickyNoteController.class
})
public class AdminShellModelAdvice {

    private final AdminShellStateService shellStateService;
    private final FilePreviewSupport filePreviewSupport;
    private final FileActionViewSupport fileActionViewSupport;
    private final StickyNoteContextResolver stickyNoteContextResolver;

    public AdminShellModelAdvice(
            AdminShellStateService shellStateService,
            FilePreviewSupport filePreviewSupport,
            FileActionViewSupport fileActionViewSupport,
            StickyNoteContextResolver stickyNoteContextResolver
    ) {
        this.shellStateService = shellStateService;
        this.filePreviewSupport = filePreviewSupport;
        this.fileActionViewSupport = fileActionViewSupport;
        this.stickyNoteContextResolver = stickyNoteContextResolver;
    }

    @ModelAttribute("storageUsage")
    public StorageUsage storageUsage() {
        return shellStateService.storageUsage();
    }

    @ModelAttribute("favorites")
    public List<FavoriteDisplayItem> favorites(HttpServletRequest request) {
        return shellStateService.favorites(request);
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
    public AdminShellStateService.TaskUiConfig taskUiConfig() {
        return shellStateService.taskUiConfig();
    }

    @ModelAttribute("uploadUiConfig")
    public AdminShellStateService.UploadUiConfig uploadUiConfig() {
        return shellStateService.uploadUiConfig();
    }

    @ModelAttribute("bookmarkLinkClickAction")
    public String bookmarkLinkClickAction() {
        return shellStateService.bookmarkLinkClickAction();
    }

    @ModelAttribute("fileRequestsEnabled")
    public boolean fileRequestsEnabled() {
        return shellStateService.capabilities().fileRequests();
    }

    @ModelAttribute("outboundRoute")
    public OutboundRouteView outboundRoute() {
        return shellStateService.outboundRoute();
    }

    @ModelAttribute("stickyNoteContext")
    public StickyNotePageContext stickyNoteContext(HttpServletRequest request) {
        return stickyNoteContextResolver.resolve(request);
    }

    @ModelAttribute("stickyNoteTheme")
    public AdminShellStateService.StickyNoteThemeView stickyNoteTheme() {
        return shellStateService.stickyNoteTheme();
    }
}
