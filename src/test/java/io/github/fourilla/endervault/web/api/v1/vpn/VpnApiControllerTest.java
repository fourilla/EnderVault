package io.github.fourilla.endervault.web.api.v1.vpn;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.github.fourilla.endervault.activity.ActivityLogService;
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
import io.github.fourilla.endervault.web.vpn.VpnRuntimeViewService;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;

class VpnApiControllerTest {

    private VpnControlService controlService;
    private VpnProxyHealthService healthService;
    private OutboundRouteStateService routeStateService;
    private RemoteDownloadService remoteDownloadService;
    private VpnApiController controller;

    @BeforeEach
    void setUp() {
        NasProperties properties = new NasProperties();
        properties.getOutbound().getVpn().setProfileName("custom.ovpn");
        controlService = mock(VpnControlService.class);
        healthService = mock(VpnProxyHealthService.class);
        routeStateService = new OutboundRouteStateService(properties);
        remoteDownloadService = mock(RemoteDownloadService.class);
        VpnRuntimeViewService runtimeViewService = new VpnRuntimeViewService(
                controlService,
                healthService,
                routeStateService,
                remoteDownloadService,
                properties
        );
        controller = new VpnApiController(
                controlService,
                healthService,
                runtimeViewService,
                mock(ActivityLogService.class)
        );

        when(controlService.refresh()).thenReturn(runningControl());
        when(healthService.refresh()).thenReturn(healthyProxy());
        when(remoteDownloadService.listTasks()).thenReturn(List.of());
    }

    @Test
    void statusCombinesControlHealthRouteAndActiveVpnTasks() {
        routeStateService.changeRoute(NetworkRoute.VPN_REQUIRED);
        when(remoteDownloadService.listTasks()).thenReturn(List.of(activeVpnTask(), activeDirectTask()));

        VpnRuntimeStatusView view = controller.status();

        assertThat(view.running()).isTrue();
        assertThat(view.publicIp()).isEqualTo("203.0.113.8");
        assertThat(view.profileName()).isEqualTo("custom.ovpn");
        assertThat(view.vpnRouteSelected()).isTrue();
        assertThat(view.activeVpnTasks()).isEqualTo(1);
        assertThat(view.health().routeReady()).isTrue();
    }

    @Test
    void disconnectRequiresExplicitConfirmationWhileVpnTaskIsActive() throws Exception {
        when(remoteDownloadService.listTasks()).thenReturn(List.of(activeVpnTask()));

        ResponseEntity<VpnControlActionResponse> response = controller.disconnect(
                false,
                new MockHttpServletRequest()
        );

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().ok()).isFalse();
        assertThat(response.getBody().vpn()).isNull();
        verify(controlService, never()).disconnect();
    }

    @Test
    void forcedDisconnectExecutesControlCommand() throws Exception {
        when(remoteDownloadService.listTasks()).thenReturn(List.of(activeVpnTask()));
        when(controlService.disconnect()).thenReturn(stoppedControl());

        ResponseEntity<VpnControlActionResponse> response = controller.disconnect(
                true,
                new MockHttpServletRequest()
        );

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().vpn().running()).isFalse();
        verify(controlService).disconnect();
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

    private RemoteDownloadTask activeDirectTask() {
        return new RemoteDownloadTask(
                "direct-task",
                "https://example.com/direct.bin",
                "",
                NetworkRoute.DIRECT,
                "admin",
                "127.0.0.1"
        );
    }

    private VpnControlStatus runningControl() {
        return new VpnControlStatus(
                VpnControlState.RUNNING,
                "203.0.113.8",
                Instant.now(),
                4L,
                "running"
        );
    }

    private VpnControlStatus stoppedControl() {
        return new VpnControlStatus(
                VpnControlState.STOPPED,
                "",
                Instant.now(),
                3L,
                "stopped"
        );
    }

    private VpnProxyHealth healthyProxy() {
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
}
