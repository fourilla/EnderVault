package io.github.fourilla.endervault.web.support;

import io.github.fourilla.endervault.outbound.vpn.VpnProxyHealth;
import io.github.fourilla.endervault.outbound.vpn.VpnProxyHealthState;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;

public record VpnStatusView(
        String state,
        String label,
        String statusClass,
        boolean proxyReachable,
        boolean routeReady,
        String proxyEndpoint,
        String tunnelHealthEndpoint,
        String checkedAtLabel,
        String latencyLabel,
        String detail
) {

    private static final DateTimeFormatter CHECKED_AT_FORMATTER =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss").withZone(ZoneId.systemDefault());

    public static VpnStatusView from(VpnProxyHealth health) {
        VpnProxyHealthState state = health.state();
        return new VpnStatusView(
                state.name(),
                label(state),
                statusClass(state),
                health.isProxyReachable(),
                health.isRouteReady(),
                endpoint(health.proxyHost(), health.proxyPort()),
                blankLabel(health.tunnelHealthUrl()),
                CHECKED_AT_FORMATTER.format(health.checkedAt()),
                health.latencyMillis() < 0L ? "-" : health.latencyMillis() + " ms",
                health.detail()
        );
    }

    private static String label(VpnProxyHealthState state) {
        return switch (state) {
            case DISABLED -> "Disabled";
            case UNCONFIGURED -> "Not configured";
            case PROXY_REACHABLE -> "Proxy reachable";
            case PROXY_UNREACHABLE -> "Proxy unreachable";
            case TUNNEL_HEALTHY -> "Tunnel healthy";
            case TUNNEL_UNHEALTHY -> "Tunnel unhealthy";
        };
    }

    private static String statusClass(VpnProxyHealthState state) {
        return switch (state) {
            case TUNNEL_HEALTHY -> "active";
            case PROXY_REACHABLE -> "info";
            case PROXY_UNREACHABLE, TUNNEL_UNHEALTHY -> "revoked";
            case DISABLED, UNCONFIGURED -> "expired";
        };
    }

    private static String endpoint(String host, int port) {
        return host == null || host.isBlank() ? "Not configured" : host + ":" + port;
    }

    private static String blankLabel(String value) {
        return value == null || value.isBlank() ? "Not configured" : value;
    }
}
