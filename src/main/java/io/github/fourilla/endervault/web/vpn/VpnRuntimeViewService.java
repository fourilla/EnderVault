package io.github.fourilla.endervault.web.vpn;

import io.github.fourilla.endervault.config.NasProperties;
import io.github.fourilla.endervault.outbound.NetworkRoute;
import io.github.fourilla.endervault.outbound.OutboundRouteStateService;
import io.github.fourilla.endervault.outbound.vpn.VpnProxyHealth;
import io.github.fourilla.endervault.outbound.vpn.VpnProxyHealthService;
import io.github.fourilla.endervault.outbound.vpn.control.VpnControlService;
import io.github.fourilla.endervault.outbound.vpn.control.VpnControlStatus;
import io.github.fourilla.endervault.remote.RemoteDownloadService;
import io.github.fourilla.endervault.web.support.VpnRuntimeStatusView;
import org.springframework.stereotype.Service;

@Service
public class VpnRuntimeViewService {

    private final VpnControlService vpnControlService;
    private final VpnProxyHealthService vpnProxyHealthService;
    private final OutboundRouteStateService outboundRouteStateService;
    private final RemoteDownloadService remoteDownloadService;
    private final NasProperties nasProperties;

    public VpnRuntimeViewService(
            VpnControlService vpnControlService,
            VpnProxyHealthService vpnProxyHealthService,
            OutboundRouteStateService outboundRouteStateService,
            RemoteDownloadService remoteDownloadService,
            NasProperties nasProperties
    ) {
        this.vpnControlService = vpnControlService;
        this.vpnProxyHealthService = vpnProxyHealthService;
        this.outboundRouteStateService = outboundRouteStateService;
        this.remoteDownloadService = remoteDownloadService;
        this.nasProperties = nasProperties;
    }

    public VpnRuntimeStatusView current() {
        return from(vpnControlService.current(), vpnProxyHealthService.current());
    }

    public VpnRuntimeStatusView refresh() {
        return from(vpnControlService.refresh(), vpnProxyHealthService.refresh());
    }

    public VpnRuntimeStatusView from(VpnControlStatus control, VpnProxyHealth health) {
        return VpnRuntimeStatusView.from(
                control,
                health,
                outboundRouteStateService.currentRoute(),
                nasProperties.getOutbound().getVpn().getProfileName(),
                activeVpnTasks()
        );
    }

    public int activeVpnTasks() {
        return Math.toIntExact(remoteDownloadService.listTasks().stream()
                .filter(task -> task.active() && task.networkRoute() == NetworkRoute.VPN_REQUIRED)
                .count());
    }
}
