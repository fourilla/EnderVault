package io.github.fourilla.endervault.outbound.vpn;

import static org.assertj.core.api.Assertions.assertThat;

import com.sun.net.httpserver.HttpServer;
import io.github.fourilla.endervault.config.NasProperties;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import org.junit.jupiter.api.Test;

class VpnProxyHealthServiceTest {

    @Test
    void reportsDisabledWhenVpnProxyIsOff() {
        NasProperties properties = new NasProperties();

        VpnProxyHealth health = service(properties).refresh();

        assertThat(health.state()).isEqualTo(VpnProxyHealthState.DISABLED);
        assertThat(health.isProxyReachable()).isFalse();
    }

    @Test
    void reportsUnconfiguredWhenHostIsMissing() {
        NasProperties properties = new NasProperties();
        properties.getOutbound().getVpn().setEnabled(true);

        VpnProxyHealth health = service(properties).refresh();

        assertThat(health.state()).isEqualTo(VpnProxyHealthState.UNCONFIGURED);
        assertThat(health.isProxyReachable()).isFalse();
    }

    @Test
    void currentRefreshesWhenRuntimeConfigurationChanges() {
        NasProperties properties = new NasProperties();
        VpnProxyHealthService service = service(properties);
        assertThat(service.refresh().state()).isEqualTo(VpnProxyHealthState.DISABLED);

        properties.getOutbound().getVpn().setEnabled(true);

        assertThat(service.current().state()).isEqualTo(VpnProxyHealthState.UNCONFIGURED);
    }

    @Test
    void reportsReachableWhenTcpEndpointAcceptsConnections() throws IOException {
        try (ServerSocket serverSocket = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
            NasProperties properties = new NasProperties();
            properties.getOutbound().getVpn().setEnabled(true);
            properties.getOutbound().getVpn().setProxyHost(InetAddress.getLoopbackAddress().getHostAddress());
            properties.getOutbound().getVpn().setProxyPort(serverSocket.getLocalPort());
            properties.getOutbound().getVpn().setHealthConnectTimeoutMs(500);

            VpnProxyHealth health = service(properties).refresh();

            assertThat(health.state()).isEqualTo(VpnProxyHealthState.PROXY_REACHABLE);
            assertThat(health.isProxyReachable()).isTrue();
            assertThat(health.isRouteReady()).isTrue();
            assertThat(health.latencyMillis()).isGreaterThanOrEqualTo(0L);
        }
    }

    @Test
    void requiresHealthyTunnelEndpointWhenConfigured() throws IOException {
        try (ServerSocket proxySocket = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
            HttpServer healthServer = healthServer(200);
            try {
                NasProperties properties = configuredProperties(proxySocket);
                properties.getOutbound().getVpn().setTunnelHealthUrl(
                        "http://localhost:" + healthServer.getAddress().getPort() + "/"
                );

                VpnProxyHealth health = service(properties).refresh();

                assertThat(health.state()).isEqualTo(VpnProxyHealthState.TUNNEL_HEALTHY);
                assertThat(health.isProxyReachable()).isTrue();
                assertThat(health.isRouteReady()).isTrue();
            } finally {
                healthServer.stop(0);
            }
        }
    }

    @Test
    void failsClosedWhenTunnelEndpointReportsUnhealthy() throws IOException {
        try (ServerSocket proxySocket = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
            HttpServer healthServer = healthServer(500);
            try {
                NasProperties properties = configuredProperties(proxySocket);
                properties.getOutbound().getVpn().setTunnelHealthUrl(
                        "http://localhost:" + healthServer.getAddress().getPort() + "/"
                );

                VpnProxyHealth health = service(properties).refresh();

                assertThat(health.state()).isEqualTo(VpnProxyHealthState.TUNNEL_UNHEALTHY);
                assertThat(health.isProxyReachable()).isTrue();
                assertThat(health.isRouteReady()).isFalse();
            } finally {
                healthServer.stop(0);
            }
        }
    }

    @Test
    void failsClosedWhenTunnelHealthUrlIsInvalid() throws IOException {
        try (ServerSocket proxySocket = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
            NasProperties properties = configuredProperties(proxySocket);
            properties.getOutbound().getVpn().setTunnelHealthUrl("file:///tmp/health");

            VpnProxyHealth health = service(properties).refresh();

            assertThat(health.state()).isEqualTo(VpnProxyHealthState.TUNNEL_UNHEALTHY);
            assertThat(health.isRouteReady()).isFalse();
        }
    }

    private VpnProxyHealthService service(NasProperties properties) {
        return new VpnProxyHealthService(properties, new VpnTunnelHealthProbe());
    }

    private NasProperties configuredProperties(ServerSocket proxySocket) {
        NasProperties properties = new NasProperties();
        properties.getOutbound().getVpn().setEnabled(true);
        properties.getOutbound().getVpn().setProxyHost(
                InetAddress.getLoopbackAddress().getHostAddress()
        );
        properties.getOutbound().getVpn().setProxyPort(proxySocket.getLocalPort());
        properties.getOutbound().getVpn().setHealthConnectTimeoutMs(500);
        properties.getOutbound().getVpn().setHealthRequestTimeoutMs(500);
        return properties;
    }

    private HttpServer healthServer(int status) throws IOException {
        HttpServer server = HttpServer.create(
                new InetSocketAddress(InetAddress.getLoopbackAddress(), 0),
                0
        );
        server.createContext("/", exchange -> {
            exchange.sendResponseHeaders(status, -1);
            exchange.close();
        });
        server.start();
        return server;
    }
}
