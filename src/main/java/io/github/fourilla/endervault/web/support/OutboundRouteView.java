package io.github.fourilla.endervault.web.support;

import io.github.fourilla.endervault.outbound.NetworkRoute;
import io.github.fourilla.endervault.outbound.vpn.VpnProxyHealth;

public record OutboundRouteView(
        String route,
        String label,
        String nextRoute,
        String iconClass,
        String statusClass,
        String title,
        boolean vpnSelected,
        boolean vpnReady
) {

    public static OutboundRouteView from(NetworkRoute route, VpnProxyHealth vpnHealth) {
        boolean vpnSelected = route == NetworkRoute.VPN_REQUIRED;
        boolean vpnReady = vpnHealth != null && vpnHealth.isRouteReady();
        String title;
        if (!vpnSelected) {
            title = "Outbound route: Direct. Switch to VPN.";
        } else if (vpnReady) {
            title = "Outbound route: VPN. Switch to Direct.";
        } else {
            title = "Outbound route: VPN unavailable. Requests fail closed. Switch to Direct.";
        }
        return new OutboundRouteView(
                route.settingValue(),
                vpnSelected ? "VPN" : "Direct",
                vpnSelected ? NetworkRoute.DIRECT.settingValue() : NetworkRoute.VPN_REQUIRED.settingValue(),
                vpnSelected ? "fas fa-shield-halved" : "fas fa-globe",
                vpnSelected ? (vpnReady ? "vpn-ready" : "vpn-unavailable") : "direct",
                title,
                vpnSelected,
                vpnReady
        );
    }
}
