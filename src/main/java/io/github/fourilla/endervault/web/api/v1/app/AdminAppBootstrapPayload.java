package io.github.fourilla.endervault.web.api.v1.app;

import io.github.fourilla.endervault.favorite.FavoriteDisplayItem;
import io.github.fourilla.endervault.storage.StorageUsage;
import io.github.fourilla.endervault.web.support.AdminShellStateService;
import io.github.fourilla.endervault.web.support.FavoritePayload;
import io.github.fourilla.endervault.web.support.OutboundRouteView;
import java.util.List;

public record AdminAppBootstrapPayload(
        String username,
        CapabilitiesPayload capabilities,
        StoragePayload storage,
        List<FavoritePayload> favorites,
        AdminShellStateService.TaskUiConfig tasks,
        AdminShellStateService.UploadUiConfig uploads,
        SessionPayload sessions,
        OutboundRoutePayload outboundRoute,
        AdminShellStateService.StickyNoteThemeView stickyNoteTheme,
        io.github.fourilla.endervault.config.AppearanceProperties appearance
) {

    static AdminAppBootstrapPayload from(
            String username,
            AdminShellStateService shellStateService,
            List<FavoriteDisplayItem> favorites
    ) {
        String bookmarkLinkClickAction = shellStateService.bookmarkLinkClickAction();
        return new AdminAppBootstrapPayload(
                username,
                CapabilitiesPayload.from(shellStateService.capabilities()),
                StoragePayload.from(shellStateService.storageUsage()),
                favorites.stream()
                        .map(favorite -> FavoritePayload.from(
                                favorite.item(),
                                bookmarkLinkClickAction,
                                favorite.hidden()
                        ))
                        .toList(),
                shellStateService.taskUiConfig(),
                shellStateService.uploadUiConfig(),
                new SessionPayload(shellStateService.activeSessionCount()),
                OutboundRoutePayload.from(shellStateService.outboundRoute()),
                shellStateService.stickyNoteTheme(),
                shellStateService.appearance()
        );
    }

    public record CapabilitiesPayload(
            boolean remoteDownloads,
            boolean fileRequests,
            boolean vpn,
            boolean metadataInspector
    ) {
        static CapabilitiesPayload from(AdminShellStateService.Capabilities capabilities) {
            return new CapabilitiesPayload(
                    capabilities.remoteDownloads(),
                    capabilities.fileRequests(),
                    capabilities.vpn(),
                    capabilities.metadataInspector()
            );
        }
    }

    public record SessionPayload(int activeCount) {
    }

    public record StoragePayload(
            long usedBytes,
            long totalBytes,
            long usableBytes,
            String usedLabel,
            String totalLabel,
            String usableLabel,
            int usedPercent
    ) {
        static StoragePayload from(StorageUsage storage) {
            return new StoragePayload(
                    storage.usedBytes(),
                    storage.totalBytes(),
                    storage.usableBytes(),
                    storage.usedLabel(),
                    storage.totalLabel(),
                    storage.usableLabel(),
                    storage.usedPercent()
            );
        }
    }

    public record OutboundRoutePayload(
            String route,
            String label,
            String nextRoute,
            String iconClass,
            String statusClass,
            String title,
            boolean vpnSelected,
            boolean vpnReady
    ) {
        static OutboundRoutePayload from(OutboundRouteView route) {
            return new OutboundRoutePayload(
                    route.route(),
                    route.label(),
                    route.nextRoute(),
                    route.iconClass(),
                    route.statusClass(),
                    route.title(),
                    route.vpnSelected(),
                    route.vpnReady()
            );
        }
    }
}
