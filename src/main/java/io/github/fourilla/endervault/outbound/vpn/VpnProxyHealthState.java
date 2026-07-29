package io.github.fourilla.endervault.outbound.vpn;

public enum VpnProxyHealthState {
    DISABLED(false),
    UNCONFIGURED(false),
    PROXY_REACHABLE(true),
    PROXY_UNREACHABLE(false);

    private final boolean proxyReachable;

    VpnProxyHealthState(boolean proxyReachable) {
        this.proxyReachable = proxyReachable;
    }

    public boolean isProxyReachable() {
        return proxyReachable;
    }
}
