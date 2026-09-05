package io.github.fourilla.endervault.outbound.vpn.control;

import com.fasterxml.jackson.annotation.JsonProperty;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;
import io.github.fourilla.endervault.config.NasProperties;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

@Component
public class GluetunControlClient {

    private static final Pattern API_KEY_PATTERN = Pattern.compile("[A-Za-z0-9_-]{22,128}");
    private static final int MAX_API_KEY_BYTES = 512;
    private static final int MAX_RESPONSE_CHARS = 4096;

    private final NasProperties nasProperties;
    private final ObjectMapper objectMapper;
    private final Map<Integer, HttpClient> clients = new ConcurrentHashMap<>();

    public GluetunControlClient(NasProperties nasProperties, ObjectMapper objectMapper) {
        this.nasProperties = nasProperties;
        this.objectMapper = objectMapper;
    }

    public Inspection inspect() throws VpnControlException {
        ControlConfiguration configuration = configuration();
        VpnApiStatus vpnStatus = get(configuration, "/v1/vpn/status", VpnApiStatus.class);
        VpnControlState state = parseState(vpnStatus.status());
        if (state != VpnControlState.RUNNING) {
            return new Inspection(state, "", "Gluetun reports that the VPN is stopped.");
        }

        try {
            PublicIpResponse publicIp = get(configuration, "/v1/publicip/ip", PublicIpResponse.class);
            String normalizedIp = cleanPublicIp(publicIp.publicIp());
            return new Inspection(state, normalizedIp, normalizedIp.isBlank()
                    ? "The VPN is running, but Gluetun did not report a public IP."
                    : "The VPN is running and Gluetun reported its public IP.");
        } catch (VpnControlException ex) {
            return new Inspection(state, "", "The VPN is running, but its public IP is unavailable.");
        }
    }

    public void setRunning(boolean running) throws VpnControlException {
        ControlConfiguration configuration = configuration();
        byte[] body;
        try {
            body = objectMapper.writeValueAsBytes(Map.of("status", running ? "running" : "stopped"));
        } catch (JacksonException ex) {
            throw new VpnControlException("The VPN control command could not be encoded.", ex);
        }

        HttpRequest request = requestBuilder(configuration, "/v1/vpn/status")
                .header("Content-Type", "application/json")
                .PUT(HttpRequest.BodyPublishers.ofByteArray(body))
                .build();
        send(configuration, request);
    }

    public boolean configured() {
        NasProperties.Vpn vpn = nasProperties.getOutbound().getVpn();
        return !vpn.getControlUrl().isBlank() && !vpn.getControlApiKeyFile().isBlank();
    }

    private <T> T get(ControlConfiguration configuration, String path, Class<T> responseType)
            throws VpnControlException {
        HttpRequest request = requestBuilder(configuration, path).GET().build();
        String body = send(configuration, request);
        try {
            return objectMapper.readValue(body, responseType);
        } catch (JacksonException ex) {
            throw new VpnControlException("Gluetun returned an invalid Control API response.", ex);
        }
    }

    private String send(ControlConfiguration configuration, HttpRequest request) throws VpnControlException {
        try {
            HttpResponse<String> response = client(configuration.timeoutMillis()).send(
                    request,
                    HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8)
            );
            if (response.statusCode() == 401 || response.statusCode() == 403) {
                throw new VpnControlException("Gluetun rejected the Control API credentials.");
            }
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                throw new VpnControlException("Gluetun Control API returned HTTP " + response.statusCode() + ".");
            }
            if (response.body().length() > MAX_RESPONSE_CHARS) {
                throw new VpnControlException("Gluetun returned an unexpectedly large Control API response.");
            }
            return response.body();
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new VpnControlException("The VPN control request was interrupted.", ex);
        } catch (IOException ex) {
            throw new VpnControlException(
                    "Gluetun Control API is unreachable (" + ex.getClass().getSimpleName() + ").",
                    ex
            );
        }
    }

    private HttpRequest.Builder requestBuilder(ControlConfiguration configuration, String path) {
        return HttpRequest.newBuilder(configuration.baseUri().resolve(path))
                .timeout(Duration.ofMillis(configuration.timeoutMillis()))
                .header("Accept", "application/json")
                .header("X-API-Key", configuration.apiKey());
    }

    private HttpClient client(int timeoutMillis) {
        return clients.computeIfAbsent(timeoutMillis, ignored -> HttpClient.newBuilder()
                .connectTimeout(Duration.ofMillis(timeoutMillis))
                .followRedirects(HttpClient.Redirect.NEVER)
                .proxy(HttpClient.Builder.NO_PROXY)
                .build());
    }

    private ControlConfiguration configuration() throws VpnControlException {
        NasProperties.Vpn vpn = nasProperties.getOutbound().getVpn();
        if (vpn.getControlUrl().isBlank() || vpn.getControlApiKeyFile().isBlank()) {
            throw new VpnControlException("Gluetun Control API is not configured.");
        }
        return new ControlConfiguration(
                controlBaseUri(vpn.getControlUrl()),
                readApiKey(vpn.getControlApiKeyFile()),
                vpn.getControlRequestTimeoutMs()
        );
    }

    private URI controlBaseUri(String rawUrl) throws VpnControlException {
        try {
            URI uri = URI.create(rawUrl.trim());
            String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
            String path = uri.getPath();
            if (!(scheme.equals("http") || scheme.equals("https"))
                    || uri.getHost() == null
                    || uri.getUserInfo() != null
                    || uri.getQuery() != null
                    || uri.getFragment() != null
                    || !(path == null || path.isBlank() || path.equals("/"))) {
                throw new IllegalArgumentException();
            }
            String normalized = uri.toString();
            return URI.create(normalized.endsWith("/") ? normalized : normalized + "/");
        } catch (IllegalArgumentException ex) {
            throw new VpnControlException("Gluetun Control API URL is invalid.");
        }
    }

    private String readApiKey(String rawPath) throws VpnControlException {
        try {
            Path path = Path.of(rawPath).toAbsolutePath().normalize();
            if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS) || Files.isSymbolicLink(path)) {
                throw new VpnControlException("Gluetun Control API key file is unavailable.");
            }
            if (Files.size(path) > MAX_API_KEY_BYTES) {
                throw new VpnControlException("Gluetun Control API key file is invalid.");
            }
            String apiKey = Files.readString(path, StandardCharsets.UTF_8).trim();
            if (!API_KEY_PATTERN.matcher(apiKey).matches()) {
                throw new VpnControlException("Gluetun Control API key file is invalid.");
            }
            return apiKey;
        } catch (InvalidPathException | IOException ex) {
            throw new VpnControlException("Gluetun Control API key file is unavailable.", ex);
        }
    }

    private VpnControlState parseState(String rawState) throws VpnControlException {
        if (rawState == null) {
            throw new VpnControlException("Gluetun returned an invalid VPN state.");
        }
        return switch (rawState.toLowerCase(Locale.ROOT)) {
            case "running" -> VpnControlState.RUNNING;
            case "stopped" -> VpnControlState.STOPPED;
            default -> throw new VpnControlException("Gluetun returned an unknown VPN state.");
        };
    }

    private String cleanPublicIp(String value) {
        if (value == null) {
            return "";
        }
        String cleaned = value.replace("\r", "").replace("\n", "").trim();
        if (cleaned.length() > 64 || cleaned.chars().anyMatch(Character::isISOControl)) {
            return "";
        }
        return cleaned;
    }

    public record Inspection(VpnControlState state, String publicIp, String detail) {
    }

    private record ControlConfiguration(URI baseUri, String apiKey, int timeoutMillis) {
    }

    private record VpnApiStatus(String status) {
    }

    private record PublicIpResponse(@JsonProperty("public_ip") String publicIp) {
    }
}
