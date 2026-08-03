package io.github.fourilla.endervault.web.dashboard;

import io.github.fourilla.endervault.activity.ActivityLogService;
import io.github.fourilla.endervault.outbound.NetworkRoute;
import io.github.fourilla.endervault.outbound.OutboundRouteStateService;
import io.github.fourilla.endervault.outbound.vpn.VpnProxyHealthService;
import io.github.fourilla.endervault.web.support.ActionResponseSupport;
import io.github.fourilla.endervault.web.support.FlashNotification;
import io.github.fourilla.endervault.web.support.OutboundRouteResponse;
import io.github.fourilla.endervault.web.support.OutboundRouteView;
import jakarta.servlet.http.HttpServletRequest;
import java.net.URI;
import java.util.Map;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

@Controller
public class AdminOutboundRouteController {

    private final OutboundRouteStateService outboundRouteStateService;
    private final VpnProxyHealthService vpnProxyHealthService;
    private final ActivityLogService activityLogService;

    public AdminOutboundRouteController(
            OutboundRouteStateService outboundRouteStateService,
            VpnProxyHealthService vpnProxyHealthService,
            ActivityLogService activityLogService
    ) {
        this.outboundRouteStateService = outboundRouteStateService;
        this.vpnProxyHealthService = vpnProxyHealthService;
        this.activityLogService = activityLogService;
    }

    @PostMapping("/admin/outbound/route")
    public Object changeRoute(
            @RequestParam String route,
            HttpServletRequest request,
            RedirectAttributes redirectAttributes
    ) {
        NetworkRoute requestedRoute;
        try {
            requestedRoute = NetworkRoute.fromSetting(route);
        } catch (IllegalArgumentException ex) {
            return ActionResponseSupport.badRequest(
                    request,
                    redirectAttributes,
                    FlashNotification.error(ex.getMessage()),
                    redirectBack(request)
            );
        }

        NetworkRoute previousRoute = outboundRouteStateService.changeRoute(requestedRoute);
        OutboundRouteView routeView =
                OutboundRouteView.from(requestedRoute, vpnProxyHealthService.current());
        FlashNotification notification = routeNotification(routeView);
        if (previousRoute != requestedRoute) {
            activityLogService.record(
                    "OUTBOUND_ROUTE_CHANGE",
                    request,
                    null,
                    null,
                    notification.message(),
                    Map.of(
                            "previousRoute", previousRoute.settingValue(),
                            "route", requestedRoute.settingValue(),
                            "vpnReady", Boolean.toString(routeView.vpnReady())
                    )
            );
        }
        return ActionResponseSupport.ok(
                request,
                redirectAttributes,
                notification,
                redirectBack(request),
                OutboundRouteResponse.ok(notification, routeView)
        );
    }

    private FlashNotification routeNotification(OutboundRouteView route) {
        if (!route.vpnSelected()) {
            return FlashNotification.success("Outbound requests will use the direct connection.");
        }
        if (route.vpnReady()) {
            return FlashNotification.success("Outbound requests will use the VPN route.");
        }
        return FlashNotification.warning(
                "VPN route selected, but it is unavailable. Managed outbound requests will fail closed."
        );
    }

    private String redirectBack(HttpServletRequest request) {
        String referer = request.getHeader(HttpHeaders.REFERER);
        if (referer == null || referer.isBlank()) {
            return "redirect:/files";
        }
        try {
            URI uri = URI.create(referer);
            String path = uri.getRawPath();
            if (!safeAdminPath(path)) {
                return "redirect:/files";
            }
            String query = uri.getRawQuery();
            return "redirect:" + path + (query == null ? "" : "?" + query);
        } catch (IllegalArgumentException ex) {
            return "redirect:/files";
        }
    }

    private boolean safeAdminPath(String path) {
        return path != null
                && (path.equals("/files")
                || path.startsWith("/files/")
                || path.equals("/admin")
                || path.startsWith("/admin/"));
    }
}
