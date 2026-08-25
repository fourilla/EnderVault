package io.github.fourilla.endervault.web.api.v1.outbound;

import io.github.fourilla.endervault.activity.ActivityLogService;
import io.github.fourilla.endervault.outbound.NetworkRoute;
import io.github.fourilla.endervault.outbound.OutboundRouteStateService;
import io.github.fourilla.endervault.outbound.vpn.VpnProxyHealthService;
import io.github.fourilla.endervault.web.support.FlashNotification;
import io.github.fourilla.endervault.web.support.OutboundRouteView;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Map;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/outbound-route")
public class OutboundRouteApiController {

    private final OutboundRouteStateService outboundRouteStateService;
    private final VpnProxyHealthService vpnProxyHealthService;
    private final ActivityLogService activityLogService;

    public OutboundRouteApiController(
            OutboundRouteStateService outboundRouteStateService,
            VpnProxyHealthService vpnProxyHealthService,
            ActivityLogService activityLogService
    ) {
        this.outboundRouteStateService = outboundRouteStateService;
        this.vpnProxyHealthService = vpnProxyHealthService;
        this.activityLogService = activityLogService;
    }

    @PostMapping
    public ResponseEntity<OutboundRouteResponse> changeRoute(
            @RequestParam String route,
            HttpServletRequest request
    ) {
        NetworkRoute requestedRoute;
        try {
            requestedRoute = NetworkRoute.fromSetting(route);
        } catch (IllegalArgumentException ex) {
            return ResponseEntity.badRequest().body(OutboundRouteResponse.error(ex.getMessage()));
        }

        NetworkRoute previousRoute = outboundRouteStateService.changeRoute(requestedRoute);
        OutboundRouteView routeView = OutboundRouteView.from(requestedRoute, vpnProxyHealthService.current());
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
        return ResponseEntity.ok(OutboundRouteResponse.ok(notification, routeView));
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
}
