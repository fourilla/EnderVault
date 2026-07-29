package io.github.fourilla.endervault.outbound.vpn;

import java.time.Instant;

public record VpnProxyHealth(
        VpnProxyHealthState state,
        String proxyHost,
        int proxyPort,
        Instant checkedAt,
        long latencyMillis,
        String detail
) {

    public boolean isProxyReachable() {
        return state.isProxyReachable();
    }
}
