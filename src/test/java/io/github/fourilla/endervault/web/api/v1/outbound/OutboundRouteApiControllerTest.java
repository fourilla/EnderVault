package io.github.fourilla.endervault.web.api.v1.outbound;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.github.fourilla.endervault.activity.ActivityLogService;
import io.github.fourilla.endervault.config.NasProperties;
import io.github.fourilla.endervault.outbound.NetworkRoute;
import io.github.fourilla.endervault.outbound.OutboundRouteStateService;
import io.github.fourilla.endervault.outbound.vpn.VpnProxyHealth;
import io.github.fourilla.endervault.outbound.vpn.VpnProxyHealthService;
import io.github.fourilla.endervault.outbound.vpn.VpnProxyHealthState;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;

class OutboundRouteApiControllerTest {

    @Test
    void changesGlobalRouteAndReturnsUpdatedJsonState() {
        OutboundRouteStateService routeState = new OutboundRouteStateService(new NasProperties());
        VpnProxyHealthService healthService = mock(VpnProxyHealthService.class);
        when(healthService.current()).thenReturn(new VpnProxyHealth(
                VpnProxyHealthState.TUNNEL_HEALTHY,
                "vpn",
                8888,
                "http://vpn:9999/",
                Instant.now(),
                1L,
                "healthy"
        ));
        OutboundRouteApiController controller = new OutboundRouteApiController(
                routeState,
                healthService,
                mock(ActivityLogService.class)
        );

        ResponseEntity<OutboundRouteResponse> response = controller.changeRoute(
                "vpn-required",
                new MockHttpServletRequest()
        );

        assertThat(routeState.currentRoute()).isEqualTo(NetworkRoute.VPN_REQUIRED);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().route().vpnReady()).isTrue();
        assertThat(response.getBody().route().nextRoute()).isEqualTo("direct");
    }

    @Test
    void rejectsUnknownRouteWithJsonError() {
        OutboundRouteApiController controller = new OutboundRouteApiController(
                new OutboundRouteStateService(new NasProperties()),
                mock(VpnProxyHealthService.class),
                mock(ActivityLogService.class)
        );

        ResponseEntity<OutboundRouteResponse> response = controller.changeRoute(
                "unknown",
                new MockHttpServletRequest()
        );

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().ok()).isFalse();
        assertThat(response.getBody().route()).isNull();
    }
}
