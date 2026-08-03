package io.github.fourilla.endervault.web.support;

import io.github.fourilla.endervault.outbound.NetworkRoute;
import io.github.fourilla.endervault.outbound.vpn.VpnProxyHealth;
import io.github.fourilla.endervault.outbound.vpn.control.VpnControlState;
import io.github.fourilla.endervault.outbound.vpn.control.VpnControlStatus;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;

public record VpnRuntimeStatusView(
        String state,
        String label,
        String statusClass,
        boolean controllable,
        boolean running,
        String publicIp,
        String checkedAtLabel,
        String latencyLabel,
        String detail,
        String profileName,
        String routeLabel,
        boolean vpnRouteSelected,
        int activeVpnTasks,
        VpnStatusView health
) {

    private static final DateTimeFormatter CHECKED_AT_FORMATTER =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss").withZone(ZoneId.systemDefault());

    public static VpnRuntimeStatusView from(
            VpnControlStatus control,
            VpnProxyHealth health,
            NetworkRoute route,
            String profileName,
            int activeVpnTasks
    ) {
        return new VpnRuntimeStatusView(
                control.state().name(),
                label(control.state()),
                statusClass(control.state()),
                control.controllable(),
                control.running(),
                blankLabel(control.publicIp(), "Unavailable"),
                CHECKED_AT_FORMATTER.format(control.checkedAt()),
                control.latencyMillis() < 0L ? "-" : control.latencyMillis() + " ms",
                control.detail(),
                blankLabel(profileName, "Not configured"),
                route.label(),
                route == NetworkRoute.VPN_REQUIRED,
                activeVpnTasks,
                VpnStatusView.from(health)
        );
    }

    private static String label(VpnControlState state) {
        return switch (state) {
            case DISABLED -> "Disabled";
            case UNCONFIGURED -> "Control unavailable";
            case RUNNING -> "Running";
            case STOPPED -> "Stopped";
            case UNAVAILABLE -> "Unreachable";
        };
    }

    private static String statusClass(VpnControlState state) {
        return switch (state) {
            case RUNNING -> "active";
            case STOPPED -> "warning";
            case UNAVAILABLE -> "revoked";
            case DISABLED, UNCONFIGURED -> "expired";
        };
    }

    private static String blankLabel(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }
}
