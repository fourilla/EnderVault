package io.github.fourilla.endervault.outbound;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
import io.github.fourilla.endervault.config.NasProperties;
import io.github.fourilla.endervault.outbound.vpn.VpnProxyHealthService;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Proxy;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class OutboundHttpClientRegistryTest {

    private NasProperties properties;
    private VpnProxyHealthService vpnProxyHealthService;
    private OutboundHttpClientRegistry registry;
    private HttpServer proxyServer;
    private HttpServer replacementProxyServer;

    @BeforeEach
    void setUp() {
        properties = new NasProperties();
        vpnProxyHealthService = new VpnProxyHealthService(properties);
        registry = new OutboundHttpClientRegistry(vpnProxyHealthService);
    }

    @AfterEach
    void tearDown() {
        if (proxyServer != null) {
            proxyServer.stop(0);
        }
        if (replacementProxyServer != null) {
            replacementProxyServer.stop(0);
        }
    }

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
                .hasMessageContaining("VPN_REQUIRED")
                .hasMessageContaining("disabled");
    }

    @Test
    void createsHttpProxyClientWhenVpnEndpointIsReachable() throws IOException {
        startProxyServer();
        enableVpnProxy();

        HttpClient client = registry.client(NetworkRoute.VPN_REQUIRED, Duration.ofSeconds(5));

        Proxy proxy = client.proxy()
                .orElseThrow()
                .select(URI.create("https://example.com"))
                .getFirst();
        assertThat(proxy.type()).isEqualTo(Proxy.Type.HTTP);
        assertThat(proxy.address()).isEqualTo(
                new InetSocketAddress(InetAddress.getLoopbackAddress(), proxyServer.getAddress().getPort())
        );
    }

    @Test
    void sendsHttpRequestThroughConfiguredProxy() throws Exception {
        startProxyServer(exchange -> {
            byte[] response = "proxied".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, response.length);
            exchange.getResponseBody().write(response);
            exchange.close();
        });
        enableVpnProxy();

        HttpClient client = registry.client(NetworkRoute.VPN_REQUIRED, Duration.ofSeconds(5));
        HttpResponse<String> response = client.send(
                HttpRequest.newBuilder(URI.create("http://unresolvable.invalid/through-proxy"))
                        .GET()
                        .build(),
                HttpResponse.BodyHandlers.ofString()
        );

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.body()).isEqualTo("proxied");
    }

    @Test
    void createsNewClientAfterProxyEndpointChanges() throws IOException {
        startProxyServer();
        enableVpnProxy();
        HttpClient first = registry.client(NetworkRoute.VPN_REQUIRED, Duration.ofSeconds(5));

        replacementProxyServer = createAndStartServer(null);
        configureVpnProxy(replacementProxyServer);
        HttpClient second = registry.client(NetworkRoute.VPN_REQUIRED, Duration.ofSeconds(5));

        assertThat(second).isNotSameAs(first);
    }

    @Test
    void rejectsInvalidTimeout() {
        assertThatThrownBy(() -> registry.client(NetworkRoute.DIRECT, Duration.ZERO))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("positive");
    }

    private void startProxyServer() throws IOException {
        startProxyServer(null);
    }

    private void startProxyServer(HttpHandler handler) throws IOException {
        proxyServer = createAndStartServer(handler);
    }

    private HttpServer createAndStartServer(HttpHandler handler) throws IOException {
        HttpServer server = HttpServer.create(
                new InetSocketAddress(InetAddress.getLoopbackAddress(), 0),
                0
        );
        if (handler != null) {
            server.createContext("/", handler);
        }
        server.start();
        return server;
    }

    private void enableVpnProxy() {
        configureVpnProxy(proxyServer);
    }

    private void configureVpnProxy(HttpServer server) {
        properties.getOutbound().getVpn().setEnabled(true);
        properties.getOutbound().getVpn().setProxyHost(InetAddress.getLoopbackAddress().getHostAddress());
        properties.getOutbound().getVpn().setProxyPort(server.getAddress().getPort());
        vpnProxyHealthService.refresh();
    }
}
