package io.github.fourilla.endervault.outbound.vpn;

import java.net.URI;
import java.util.Locale;

public final class VpnTunnelHealthEndpoint {

    private VpnTunnelHealthEndpoint() {
    }

    public static String normalizeOptional(String endpoint) {
        String value = endpoint == null ? "" : endpoint.trim();
        return value.isBlank() ? "" : parse(value).toString();
    }

    public static URI parse(String endpoint) {
        URI uri = URI.create(endpoint);
        String scheme = uri.getScheme() == null
                ? ""
                : uri.getScheme().toLowerCase(Locale.ROOT);
        if ((!"http".equals(scheme) && !"https".equals(scheme))
                || uri.getHost() == null
                || uri.getHost().isBlank()
                || uri.getUserInfo() != null
                || uri.getFragment() != null) {
            throw new IllegalArgumentException("Unsupported tunnel health URL.");
        }
        return uri;
    }
}
