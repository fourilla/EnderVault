package io.github.fourilla.endervault.web.support;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.fourilla.endervault.outbound.NetworkRoute;
import io.github.fourilla.endervault.outbound.vpn.VpnProxyHealth;
import io.github.fourilla.endervault.outbound.vpn.VpnProxyHealthState;
import java.time.Instant;
import org.junit.jupiter.api.Test;

class OutboundRouteViewTest {

    @Test
    void marksUnavailableVpnSelectionAsFailClosed() {
        OutboundRouteView view = OutboundRouteView.from(
                NetworkRoute.VPN_REQUIRED,
                health(VpnProxyHealthState.PROXY_UNREACHABLE)
        );

        assertThat(view.vpnSelected()).isTrue();
        assertThat(view.vpnReady()).isFalse();
        assertThat(view.statusClass()).isEqualTo("vpn-unavailable");
        assertThat(view.nextRoute()).isEqualTo("direct");
        assertThat(view.title()).contains("fail closed");
    }

    @Test
    void directSelectionCanSwitchToVpnRegardlessOfCurrentVpnHealth() {
        OutboundRouteView view = OutboundRouteView.from(
                NetworkRoute.DIRECT,
                health(VpnProxyHealthState.DISABLED)
        );

        assertThat(view.label()).isEqualTo("Direct");
        assertThat(view.nextRoute()).isEqualTo("vpn-required");
        assertThat(view.statusClass()).isEqualTo("direct");
    }

    private VpnProxyHealth health(VpnProxyHealthState state) {
        return new VpnProxyHealth(state, "vpn", 8888, "", Instant.now(), 1L, state.name());
    }
}
