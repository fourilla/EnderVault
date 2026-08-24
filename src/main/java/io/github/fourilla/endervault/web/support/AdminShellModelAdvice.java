package io.github.fourilla.endervault.web.support;

import io.github.fourilla.endervault.config.NasProperties;
import io.github.fourilla.endervault.favorite.FavoriteDisplayItem;
import io.github.fourilla.endervault.favorite.FavoriteService;
import io.github.fourilla.endervault.outbound.OutboundRouteStateService;
import io.github.fourilla.endervault.outbound.vpn.VpnProxyHealthService;
import io.github.fourilla.endervault.storage.StorageService;
import io.github.fourilla.endervault.storage.StorageUsage;
import io.github.fourilla.endervault.web.dashboard.AdminDashboardController;
import io.github.fourilla.endervault.web.dashboard.AdminLogController;
import io.github.fourilla.endervault.web.dashboard.AdminSessionController;
import io.github.fourilla.endervault.web.dashboard.AdminTelegramSettingsController;
import io.github.fourilla.endervault.web.auth.AdminPasskeyController;
import io.github.fourilla.endervault.web.bookmark.AdminBookmarkController;
import io.github.fourilla.endervault.web.file.AdminFavoriteController;
import io.github.fourilla.endervault.web.file.AdminFileController;
import io.github.fourilla.endervault.web.file.AdminFileDetailController;
import io.github.fourilla.endervault.web.file.AdminFileMutationController;
import io.github.fourilla.endervault.web.file.AdminFileShareController;
import io.github.fourilla.endervault.web.file.AdminFileTransferController;
import io.github.fourilla.endervault.web.file.AdminRecentController;
import io.github.fourilla.endervault.web.filerequest.AdminFileRequestController;
import io.github.fourilla.endervault.web.metadata.AdminMetadataController;
import io.github.fourilla.endervault.web.pending.AdminPendingFileDecisionController;
import io.github.fourilla.endervault.web.remote.AdminRemoteDownloadController;
import io.github.fourilla.endervault.web.settings.AdminAccountSettingsController;
import io.github.fourilla.endervault.web.settings.AdminBookmarkSettingsController;
import io.github.fourilla.endervault.web.settings.AdminFileRequestSettingsController;
import io.github.fourilla.endervault.web.settings.AdminGeneralSettingsController;
import io.github.fourilla.endervault.web.settings.AdminSettingsController;
import io.github.fourilla.endervault.web.settings.AdminSessionSettingsController;
import io.github.fourilla.endervault.web.settings.AdminVpnSettingsController;
import io.github.fourilla.endervault.web.share.AdminShareController;
import io.github.fourilla.endervault.web.stickynote.AdminStickyNoteController;
import io.github.fourilla.endervault.web.trash.AdminTrashController;
import io.github.fourilla.endervault.web.vpn.AdminVpnController;
import jakarta.servlet.http.HttpServletRequest;
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
        AdminFileRequestController.class,
        AdminLogController.class,
        AdminSessionController.class,
        AdminRemoteDownloadController.class,
        AdminShareController.class,
        AdminTrashController.class,
        AdminSettingsController.class,
        AdminAccountSettingsController.class,
        AdminBookmarkSettingsController.class,
        AdminFileRequestSettingsController.class,
        AdminGeneralSettingsController.class,
        AdminSessionSettingsController.class,
        AdminVpnSettingsController.class,
        AdminVpnController.class,
        AdminMetadataController.class,
        AdminPendingFileDecisionController.class,
        AdminStickyNoteController.class
})
public class AdminShellModelAdvice {

    private final StorageService storageService;
    private final FavoriteService favoriteService;
    private final FilePreviewSupport filePreviewSupport;
    private final FileActionViewSupport fileActionViewSupport;
    private final NasProperties nasProperties;
    private final OutboundRouteStateService outboundRouteStateService;
    private final VpnProxyHealthService vpnProxyHealthService;
    private final StickyNoteContextResolver stickyNoteContextResolver;

    public AdminShellModelAdvice(
            StorageService storageService,
            FavoriteService favoriteService,
            FilePreviewSupport filePreviewSupport,
            FileActionViewSupport fileActionViewSupport,
            NasProperties nasProperties,
            OutboundRouteStateService outboundRouteStateService,
            VpnProxyHealthService vpnProxyHealthService,
            StickyNoteContextResolver stickyNoteContextResolver
    ) {
        this.storageService = storageService;
        this.favoriteService = favoriteService;
        this.filePreviewSupport = filePreviewSupport;
        this.fileActionViewSupport = fileActionViewSupport;
        this.nasProperties = nasProperties;
        this.outboundRouteStateService = outboundRouteStateService;
        this.vpnProxyHealthService = vpnProxyHealthService;
        this.stickyNoteContextResolver = stickyNoteContextResolver;
    }

    @ModelAttribute("storageUsage")
    public StorageUsage storageUsage() {
        return storageService.storageUsage();
    }

    @ModelAttribute("favorites")
    public List<FavoriteDisplayItem> favorites(HttpServletRequest request) {
        try {
            return favoriteService.listExistingDisplay(showHiddenFavorites(request));
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
                upload.isDirectoryUploadEnabled(),
                Math.min(upload.getMaxConcurrentChunks(), 8)
        );
    }

    @ModelAttribute("bookmarkLinkClickAction")
    public String bookmarkLinkClickAction() {
        return BookmarkLinkClickAction.from(nasProperties);
    }

    @ModelAttribute("fileRequestsEnabled")
    public boolean fileRequestsEnabled() {
        return nasProperties.getFileRequest().isEnabled();
    }

    @ModelAttribute("outboundRoute")
    public OutboundRouteView outboundRoute() {
        return OutboundRouteView.from(
                outboundRouteStateService.currentRoute(),
                vpnProxyHealthService.current()
        );
    }

    @ModelAttribute("stickyNoteContext")
    public StickyNotePageContext stickyNoteContext(HttpServletRequest request) {
        return stickyNoteContextResolver.resolve(request);
    }

    @ModelAttribute("stickyNoteTheme")
    public StickyNoteThemeView stickyNoteTheme() {
        NasProperties.StickyNotes stickyNotes = nasProperties.getStickyNotes();
        return new StickyNoteThemeView(
                stickyNotes.getBackgroundColor(),
                stickyNotes.getBorderColor(),
                stickyNotes.getTextColor()
        );
    }

    private boolean showHiddenFavorites(HttpServletRequest request) {
        String requestedHidden = request.getParameter("hidden");
        String hidden = requestedHidden == null
                ? BrowserPreferenceCookies.value(
                        request,
                        BrowserPreferenceCookies.FILES.hiddenCookie(),
                        this::normalizeHiddenMode
                )
                : normalizeHiddenMode(requestedHidden);
        return "show".equals(hidden);
    }

    private String normalizeHiddenMode(String hidden) {
        if (hidden == null || hidden.isBlank()) {
            return "hide";
        }
        return "show".equalsIgnoreCase(hidden) || "true".equalsIgnoreCase(hidden) ? "show" : "hide";
    }

    public record TaskUiConfig(
            boolean activityPanelEnabled,
            int completedDisplayMs,
            int failedDisplayMs
    ) {
    }

    public record UploadUiConfig(
            int maxFilesPerRequest,
            boolean directoryUploadEnabled,
            int maxConcurrentUploads
    ) {
    }

    public record StickyNoteThemeView(
            String backgroundColor,
            String borderColor,
            String textColor
    ) {
    }
}
