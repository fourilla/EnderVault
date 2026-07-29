package io.github.fourilla.endervault.outbound;

import java.net.http.HttpClient;
import java.time.Duration;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Component;

@Component
public class OutboundHttpClientRegistry {

    private final Map<ClientKey, HttpClient> clients = new ConcurrentHashMap<>();

    public HttpClient client(NetworkRoute route, Duration connectTimeout) {
        Objects.requireNonNull(route, "route");
        Objects.requireNonNull(connectTimeout, "connectTimeout");
        if (connectTimeout.isZero() || connectTimeout.isNegative()) {
            throw new IllegalArgumentException("Connect timeout must be positive.");
        }
        if (route != NetworkRoute.DIRECT) {
            throw new OutboundRouteUnavailableException(route);
        }
        return clients.computeIfAbsent(new ClientKey(route, connectTimeout), this::createClient);
    }

    private HttpClient createClient(ClientKey key) {
        return HttpClient.newBuilder()
                .connectTimeout(key.connectTimeout())
                .followRedirects(HttpClient.Redirect.NEVER)
                .proxy(HttpClient.Builder.NO_PROXY)
                .build();
    }

    private record ClientKey(NetworkRoute route, Duration connectTimeout) {
    }
}
