package io.github.fourilla.endervault.outbound.vpn;

public enum VpnProxyHealthState {
    DISABLED(false, false),
    UNCONFIGURED(false, false),
    PROXY_REACHABLE(true, true),
    PROXY_UNREACHABLE(false, false),
    TUNNEL_HEALTHY(true, true),
    TUNNEL_UNHEALTHY(true, false);

    private final boolean proxyReachable;
    private final boolean routeReady;

    VpnProxyHealthState(boolean proxyReachable, boolean routeReady) {
        this.proxyReachable = proxyReachable;
        this.routeReady = routeReady;
    }

    public boolean isProxyReachable() {
        return proxyReachable;
    }

    public boolean isRouteReady() {
        return routeReady;
    }
}
