package io.github.fourilla.endervault.outbound.vpn;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.fourilla.endervault.config.NasProperties;
import java.io.IOException;
import java.net.InetAddress;
import java.net.ServerSocket;
import org.junit.jupiter.api.Test;

class VpnProxyHealthServiceTest {

    @Test
    void reportsDisabledWhenVpnProxyIsOff() {
        NasProperties properties = new NasProperties();

        VpnProxyHealth health = new VpnProxyHealthService(properties).refresh();

        assertThat(health.state()).isEqualTo(VpnProxyHealthState.DISABLED);
        assertThat(health.isProxyReachable()).isFalse();
    }

    @Test
    void reportsUnconfiguredWhenHostIsMissing() {
        NasProperties properties = new NasProperties();
        properties.getOutbound().getVpn().setEnabled(true);

        VpnProxyHealth health = new VpnProxyHealthService(properties).refresh();

        assertThat(health.state()).isEqualTo(VpnProxyHealthState.UNCONFIGURED);
        assertThat(health.isProxyReachable()).isFalse();
    }

    @Test
    void currentRefreshesWhenRuntimeConfigurationChanges() {
        NasProperties properties = new NasProperties();
        VpnProxyHealthService service = new VpnProxyHealthService(properties);
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

            VpnProxyHealth health = new VpnProxyHealthService(properties).refresh();

            assertThat(health.state()).isEqualTo(VpnProxyHealthState.PROXY_REACHABLE);
            assertThat(health.isProxyReachable()).isTrue();
            assertThat(health.latencyMillis()).isGreaterThanOrEqualTo(0L);
        }
    }
}
