package io.github.fourilla.endervault.web.dashboard;

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
import io.github.fourilla.endervault.web.support.OutboundRouteResponse;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.servlet.mvc.support.RedirectAttributesModelMap;

class AdminOutboundRouteControllerTest {

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
        AdminOutboundRouteController controller = new AdminOutboundRouteController(
                routeState,
                healthService,
                mock(ActivityLogService.class)
        );
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader(HttpHeaders.ACCEPT, MediaType.APPLICATION_JSON_VALUE);

        Object result = controller.changeRoute(
                "vpn-required",
                request,
                new RedirectAttributesModelMap()
        );

        assertThat(routeState.currentRoute()).isEqualTo(NetworkRoute.VPN_REQUIRED);
        assertThat(result).isInstanceOf(ResponseEntity.class);
        Object body = ((ResponseEntity<?>) result).getBody();
        assertThat(body).isInstanceOf(OutboundRouteResponse.class);
        OutboundRouteResponse response = (OutboundRouteResponse) body;
        assertThat(response.route().vpnReady()).isTrue();
        assertThat(response.route().nextRoute()).isEqualTo("direct");
    }
}
