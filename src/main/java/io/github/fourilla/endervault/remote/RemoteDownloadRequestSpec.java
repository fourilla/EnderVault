package io.github.fourilla.endervault.remote;

import io.github.fourilla.endervault.outbound.NetworkRoute;
import java.net.URI;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

public record RemoteDownloadRequestSpec(
        URI sourceUri,
        String targetDirectory,
        NetworkRoute networkRoute,
        int requestedConnections,
        boolean inspectionSkipped,
        Map<String, String> headers
) {

    public RemoteDownloadRequestSpec {
        targetDirectory = targetDirectory == null ? "" : targetDirectory;
        headers = Map.copyOf(new LinkedHashMap<>(headers == null ? Map.of() : headers));
    }

    public int headerCount() {
        return headers.size();
    }

    public boolean cookieIncluded() {
        return headers.keySet().stream().anyMatch("cookie"::equalsIgnoreCase);
    }

    public String sourceLabel() {
        return safeUriLabel(sourceUri);
    }

    public static String safeUriLabel(URI uri) {
        StringBuilder label = new StringBuilder()
                .append(uri.getScheme())
                .append("://")
                .append(uri.getRawAuthority());
        String path = uri.getRawPath();
        label.append(path == null || path.isBlank() ? "/" : path);
        if (uri.getRawQuery() != null) {
            label.append("?...");
        }
        return label.toString();
    }

    public Map<String, String> headersForRedirect(Map<String, String> currentHeaders, URI previous, URI next) {
        if (sameOrigin(previous, next)) {
            return currentHeaders;
        }
        Map<String, String> filtered = new LinkedHashMap<>();
        currentHeaders.forEach((name, value) -> {
            String normalized = name.toLowerCase(Locale.ROOT);
            if (!"authorization".equals(normalized) && !"cookie".equals(normalized)) {
                filtered.put(name, value);
            }
        });
        return Map.copyOf(filtered);
    }

    private boolean sameOrigin(URI left, URI right) {
        return left.getScheme().equalsIgnoreCase(right.getScheme())
                && left.getHost().equalsIgnoreCase(right.getHost())
                && effectivePort(left) == effectivePort(right);
    }

    private int effectivePort(URI uri) {
        if (uri.getPort() >= 0) {
            return uri.getPort();
        }
        return "https".equalsIgnoreCase(uri.getScheme()) ? 443 : 80;
    }
}
