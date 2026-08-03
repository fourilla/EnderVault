package io.github.fourilla.endervault.outbound;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

import io.github.fourilla.endervault.config.NasProperties;
import org.junit.jupiter.api.Test;

class OutboundRouteStateServiceTest {

    @Test
    void startsWithConfiguredInitialRoute() {
        NasProperties properties = new NasProperties();
        properties.getOutbound().setInitialRoute(NetworkRoute.VPN_REQUIRED);

        OutboundRouteStateService service = new OutboundRouteStateService(properties);

        assertThat(service.initialRoute()).isEqualTo(NetworkRoute.VPN_REQUIRED);
        assertThat(service.currentRoute()).isEqualTo(NetworkRoute.VPN_REQUIRED);
    }

    @Test
    void runtimeChangeDoesNotMutateConfiguredInitialRoute() {
        NasProperties properties = new NasProperties();
        OutboundRouteStateService service = new OutboundRouteStateService(properties);

        NetworkRoute previous = service.changeRoute(NetworkRoute.VPN_REQUIRED);

        assertThat(previous).isEqualTo(NetworkRoute.DIRECT);
        assertThat(service.currentRoute()).isEqualTo(NetworkRoute.VPN_REQUIRED);
        assertThat(service.initialRoute()).isEqualTo(NetworkRoute.DIRECT);
        assertThat(properties.getOutbound().getInitialRoute()).isEqualTo(NetworkRoute.DIRECT);
    }

    @Test
    void rejectsNullRuntimeRoute() {
        OutboundRouteStateService service = new OutboundRouteStateService(new NasProperties());

        assertThatNullPointerException()
                .isThrownBy(() -> service.changeRoute(null))
                .withMessage("route");
    }
}
