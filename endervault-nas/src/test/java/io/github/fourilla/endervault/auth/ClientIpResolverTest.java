package io.github.fourilla.endervault.auth;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.fourilla.endervault.config.NasProperties;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

class ClientIpResolverTest {

    @Test
    void usesForwardedForOnlyWhenRemoteAddressIsTrustedProxy() {
        ClientIpResolver resolver = new ClientIpResolver(new NasProperties());
        MockHttpServletRequest request = request("127.0.0.1", "203.0.113.20");

        assertThat(resolver.resolve(request)).isEqualTo("203.0.113.20");
    }

    @Test
    void ignoresForwardedForWhenRemoteAddressIsNotTrustedProxy() {
        ClientIpResolver resolver = new ClientIpResolver(new NasProperties());
        MockHttpServletRequest request = request("198.51.100.10", "203.0.113.20");

        assertThat(resolver.resolve(request)).isEqualTo("198.51.100.10");
    }

    @Test
    void prefersNearestUntrustedAddressFromForwardedChain() {
        ClientIpResolver resolver = new ClientIpResolver(new NasProperties());
        MockHttpServletRequest request = request("127.0.0.1", "198.51.100.99, 203.0.113.20");

        assertThat(resolver.resolve(request)).isEqualTo("203.0.113.20");
    }

    @Test
    void supportsTrustedProxyCidrRanges() {
        NasProperties properties = new NasProperties();
        properties.getSecurity().setTrustedProxies(List.of("10.0.0.0/8"));
        ClientIpResolver resolver = new ClientIpResolver(properties);
        MockHttpServletRequest request = request("10.12.0.5", "203.0.113.20");

        assertThat(resolver.resolve(request)).isEqualTo("203.0.113.20");
    }

    private MockHttpServletRequest request(String remoteAddress, String forwardedFor) {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRemoteAddr(remoteAddress);
        request.addHeader("X-Forwarded-For", forwardedFor);
        return request;
    }
}
