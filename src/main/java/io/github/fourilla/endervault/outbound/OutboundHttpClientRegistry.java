package io.github.fourilla.endervault.outbound;

import io.github.fourilla.endervault.outbound.vpn.VpnProxyHealth;
import io.github.fourilla.endervault.outbound.vpn.VpnProxyHealthService;
import java.net.InetSocketAddress;
import java.net.ProxySelector;
import java.net.http.HttpClient;
import java.time.Duration;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Component;

@Component
public class OutboundHttpClientRegistry {

    private final VpnProxyHealthService vpnProxyHealthService;
    private final Map<ClientKey, HttpClient> clients = new ConcurrentHashMap<>();

    public OutboundHttpClientRegistry(VpnProxyHealthService vpnProxyHealthService) {
        this.vpnProxyHealthService = vpnProxyHealthService;
    }

    public HttpClient client(NetworkRoute route, Duration connectTimeout) {
        Objects.requireNonNull(route, "route");
        Objects.requireNonNull(connectTimeout, "connectTimeout");
        if (connectTimeout.isZero() || connectTimeout.isNegative()) {
            throw new IllegalArgumentException("Connect timeout must be positive.");
        }
        ClientKey key = switch (route) {
            case DIRECT -> ClientKey.direct(connectTimeout);
            case VPN_REQUIRED -> vpnClientKey(connectTimeout);
        };
        return clients.computeIfAbsent(key, this::createClient);
    }

    private ClientKey vpnClientKey(Duration connectTimeout) {
        VpnProxyHealth health = vpnProxyHealthService.current();
        if (!health.isRouteReady()) {
            throw new OutboundRouteUnavailableException(NetworkRoute.VPN_REQUIRED, health.detail());
        }
        discardStaleVpnClients(health);
        return ClientKey.vpn(connectTimeout, health.proxyHost(), health.proxyPort());
    }

    private void discardStaleVpnClients(VpnProxyHealth health) {
        clients.keySet().removeIf(key -> key.route() == NetworkRoute.VPN_REQUIRED
                && (!Objects.equals(key.proxyHost(), health.proxyHost())
                || key.proxyPort() != health.proxyPort()));
    }

    private HttpClient createClient(ClientKey key) {
        HttpClient.Builder builder = HttpClient.newBuilder()
                .connectTimeout(key.connectTimeout())
                .followRedirects(HttpClient.Redirect.NEVER);
        if (key.route() == NetworkRoute.DIRECT) {
            builder.proxy(HttpClient.Builder.NO_PROXY);
        } else {
            builder.proxy(ProxySelector.of(new InetSocketAddress(key.proxyHost(), key.proxyPort())));
        }
        return builder.build();
    }

    private record ClientKey(
            NetworkRoute route,
            Duration connectTimeout,
            String proxyHost,
            int proxyPort
    ) {

        private static ClientKey direct(Duration connectTimeout) {
            return new ClientKey(NetworkRoute.DIRECT, connectTimeout, "", 0);
        }

        private static ClientKey vpn(Duration connectTimeout, String proxyHost, int proxyPort) {
            return new ClientKey(NetworkRoute.VPN_REQUIRED, connectTimeout, proxyHost, proxyPort);
        }
    }
}
