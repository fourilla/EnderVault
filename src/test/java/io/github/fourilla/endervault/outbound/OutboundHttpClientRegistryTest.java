package io.github.fourilla.endervault.outbound;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.net.http.HttpClient;
import java.time.Duration;
import org.junit.jupiter.api.Test;

class OutboundHttpClientRegistryTest {

    private final OutboundHttpClientRegistry registry = new OutboundHttpClientRegistry();

    @Test
    void reusesDirectClientForSameTimeout() {
        HttpClient first = registry.client(NetworkRoute.DIRECT, Duration.ofSeconds(5));
        HttpClient second = registry.client(NetworkRoute.DIRECT, Duration.ofSeconds(5));

        assertThat(second).isSameAs(first);
        assertThat(first.connectTimeout()).contains(Duration.ofSeconds(5));
        assertThat(first.followRedirects()).isEqualTo(HttpClient.Redirect.NEVER);
        assertThat(first.proxy()).contains(HttpClient.Builder.NO_PROXY);
    }

    @Test
    void keepsClientsWithDifferentTimeoutsSeparate() {
        HttpClient shortTimeout = registry.client(NetworkRoute.DIRECT, Duration.ofSeconds(5));
        HttpClient longTimeout = registry.client(NetworkRoute.DIRECT, Duration.ofSeconds(10));

        assertThat(longTimeout).isNotSameAs(shortTimeout);
    }

    @Test
    void failsClosedWhenVpnRouteIsNotConfigured() {
        assertThatThrownBy(() -> registry.client(NetworkRoute.VPN_REQUIRED, Duration.ofSeconds(5)))
                .isInstanceOf(OutboundRouteUnavailableException.class)
                .hasMessageContaining("VPN_REQUIRED");
    }

    @Test
    void rejectsInvalidTimeout() {
        assertThatThrownBy(() -> registry.client(NetworkRoute.DIRECT, Duration.ZERO))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("positive");
    }
}
