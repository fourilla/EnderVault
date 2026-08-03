package io.github.fourilla.endervault.web.vpn;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.github.fourilla.endervault.config.NasProperties;
import io.github.fourilla.endervault.outbound.NetworkRoute;
import io.github.fourilla.endervault.outbound.OutboundRouteStateService;
import io.github.fourilla.endervault.outbound.vpn.VpnProxyHealth;
import io.github.fourilla.endervault.outbound.vpn.VpnProxyHealthService;
import io.github.fourilla.endervault.outbound.vpn.VpnProxyHealthState;
import io.github.fourilla.endervault.outbound.vpn.control.VpnControlService;
import io.github.fourilla.endervault.outbound.vpn.control.VpnControlState;
import io.github.fourilla.endervault.outbound.vpn.control.VpnControlStatus;
import io.github.fourilla.endervault.remote.RemoteDownloadService;
import io.github.fourilla.endervault.remote.RemoteDownloadTask;
import io.github.fourilla.endervault.web.support.VpnRuntimeStatusView;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

class VpnRuntimeViewServiceTest {

    @Test
    void currentViewUsesCachedStatusWithoutRefreshingControlApi() {
        NasProperties properties = new NasProperties();
        properties.getOutbound().getVpn().setProfileName("custom.ovpn");
        OutboundRouteStateService routeStateService = new OutboundRouteStateService(properties);
        routeStateService.changeRoute(NetworkRoute.VPN_REQUIRED);
        VpnControlService controlService = mock(VpnControlService.class);
        VpnProxyHealthService healthService = mock(VpnProxyHealthService.class);
        RemoteDownloadService remoteDownloadService = mock(RemoteDownloadService.class);
        when(controlService.current()).thenReturn(controlStatus());
        when(healthService.current()).thenReturn(healthStatus());
        when(remoteDownloadService.listTasks()).thenReturn(List.of(activeVpnTask()));
        VpnRuntimeViewService service = new VpnRuntimeViewService(
                controlService,
                healthService,
                routeStateService,
                remoteDownloadService,
                properties
        );

        VpnRuntimeStatusView view = service.current();

        assertThat(view.running()).isTrue();
        assertThat(view.vpnRouteSelected()).isTrue();
        assertThat(view.activeVpnTasks()).isEqualTo(1);
        verify(controlService).current();
        verify(healthService).current();
        verify(controlService, never()).refresh();
        verify(healthService, never()).refresh();
    }

    private VpnControlStatus controlStatus() {
        return new VpnControlStatus(
                VpnControlState.RUNNING,
                "203.0.113.8",
                Instant.now(),
                4L,
                "running"
        );
    }

    private VpnProxyHealth healthStatus() {
        return new VpnProxyHealth(
                VpnProxyHealthState.TUNNEL_HEALTHY,
                "vpn",
                8888,
                "http://vpn:9999/",
                Instant.now(),
                2L,
                "healthy"
        );
    }

    private RemoteDownloadTask activeVpnTask() {
        return new RemoteDownloadTask(
                "vpn-task",
                "https://example.com/vpn.bin",
                "",
                NetworkRoute.VPN_REQUIRED,
                "admin",
                "127.0.0.1"
        );
    }
}
