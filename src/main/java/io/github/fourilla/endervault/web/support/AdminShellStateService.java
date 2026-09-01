package io.github.fourilla.endervault.web.support;

import io.github.fourilla.endervault.config.NasProperties;
import io.github.fourilla.endervault.favorite.FavoriteDisplayItem;
import io.github.fourilla.endervault.favorite.FavoriteService;
import io.github.fourilla.endervault.outbound.OutboundRouteStateService;
import io.github.fourilla.endervault.outbound.vpn.VpnProxyHealthService;
import io.github.fourilla.endervault.session.SessionManagementService;
import io.github.fourilla.endervault.storage.StorageService;
import io.github.fourilla.endervault.storage.StorageUsage;
import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import org.springframework.stereotype.Service;

@Service
public class AdminShellStateService {

    private final StorageService storageService;
    private final FavoriteService favoriteService;
    private final NasProperties nasProperties;
    private final OutboundRouteStateService outboundRouteStateService;
    private final VpnProxyHealthService vpnProxyHealthService;
    private final SessionManagementService sessionManagementService;

    public AdminShellStateService(
            StorageService storageService,
            FavoriteService favoriteService,
            NasProperties nasProperties,
            OutboundRouteStateService outboundRouteStateService,
            VpnProxyHealthService vpnProxyHealthService,
            SessionManagementService sessionManagementService
    ) {
        this.storageService = storageService;
        this.favoriteService = favoriteService;
        this.nasProperties = nasProperties;
        this.outboundRouteStateService = outboundRouteStateService;
        this.vpnProxyHealthService = vpnProxyHealthService;
        this.sessionManagementService = sessionManagementService;
    }

    public StorageUsage storageUsage() {
        return storageService.storageUsage();
    }

    public List<FavoriteDisplayItem> favorites(HttpServletRequest request) {
        try {
            return favoriteService.listExistingDisplay(showHiddenFavorites(request));
        } catch (Exception ex) {
            return List.of();
        }
    }

    public TaskUiConfig taskUiConfig() {
        NasProperties.Tasks tasks = nasProperties.getTasks();
        return new TaskUiConfig(
                tasks.isActivityPanelEnabled(),
                tasks.getCompletedDisplayMs(),
                tasks.getFailedDisplayMs()
        );
    }

    public UploadUiConfig uploadUiConfig() {
        return new UploadUiConfig(Math.min(nasProperties.getUpload().getMaxConcurrentChunks(), 8));
    }

    public String bookmarkLinkClickAction() {
        return BookmarkLinkClickAction.from(nasProperties);
    }

    public Capabilities capabilities() {
        return new Capabilities(
                nasProperties.getRemoteDownload().isEnabled(),
                nasProperties.getFileRequest().isEnabled(),
                nasProperties.getOutbound().getVpn().isEnabled(),
                nasProperties.getMetadataInspector().isEnabled()
        );
    }

    public OutboundRouteView outboundRoute() {
        return OutboundRouteView.from(
                outboundRouteStateService.currentRoute(),
                vpnProxyHealthService.current()
        );
    }

    public int activeSessionCount() {
        return sessionManagementService.activeCount();
    }

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

    public record UploadUiConfig(int maxConcurrentUploads) {
    }

    public record Capabilities(
            boolean remoteDownloads,
            boolean fileRequests,
            boolean vpn,
            boolean metadataInspector
    ) {
    }

    public record StickyNoteThemeView(
            String backgroundColor,
            String borderColor,
            String textColor
    ) {
    }
}
