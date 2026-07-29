package io.github.fourilla.endervault.outbound.vpn;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Component;

@Component
public class VpnTunnelHealthProbe {

    private final Map<Integer, HttpClient> clients = new ConcurrentHashMap<>();

    public Result probe(String endpoint, int timeoutMillis) {
        URI uri;
        try {
            uri = VpnTunnelHealthEndpoint.parse(endpoint);
        } catch (IllegalArgumentException ex) {
            return Result.unhealthy("VPN tunnel health URL is invalid.");
        }

        HttpRequest request = HttpRequest.newBuilder(uri)
                .timeout(Duration.ofMillis(timeoutMillis))
                .header("Accept", "text/plain")
                .GET()
                .build();
        try {
            HttpResponse<Void> response = client(timeoutMillis).send(
                    request,
                    HttpResponse.BodyHandlers.discarding()
            );
            if (response.statusCode() == 200) {
                return Result.healthy("VPN tunnel health endpoint returned HTTP 200.");
            }
            return Result.unhealthy(
                    "VPN tunnel health endpoint returned HTTP " + response.statusCode() + "."
            );
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            return Result.unhealthy("VPN tunnel health check was interrupted.");
        } catch (Exception ex) {
            return Result.unhealthy(
                    "VPN tunnel health endpoint is unreachable (" + ex.getClass().getSimpleName() + ")."
            );
        }
    }

    private HttpClient client(int timeoutMillis) {
        return clients.computeIfAbsent(timeoutMillis, ignored -> HttpClient.newBuilder()
                .connectTimeout(Duration.ofMillis(timeoutMillis))
                .followRedirects(HttpClient.Redirect.NEVER)
                .proxy(HttpClient.Builder.NO_PROXY)
                .build());
    }

    public record Result(boolean healthy, String detail) {

        private static Result healthy(String detail) {
            return new Result(true, detail);
        }

        private static Result unhealthy(String detail) {
            return new Result(false, detail);
        }
    }
}
