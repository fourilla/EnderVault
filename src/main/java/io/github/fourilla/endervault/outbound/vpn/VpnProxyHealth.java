package io.github.fourilla.endervault.outbound.vpn;

import java.time.Instant;

public record VpnProxyHealth(
        VpnProxyHealthState state,
        String proxyHost,
        int proxyPort,
        String tunnelHealthUrl,
        Instant checkedAt,
        long latencyMillis,
        String detail
) {

    public boolean isProxyReachable() {
        return state.isProxyReachable();
    }

    public boolean isRouteReady() {
        return state.isRouteReady();
    }
}
