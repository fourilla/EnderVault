package io.github.fourilla.endervault.web.support;

import io.github.fourilla.endervault.config.NasProperties;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

@Component
public class PublicUrlBuilder {

    private final NasProperties nasProperties;

    public PublicUrlBuilder(NasProperties nasProperties) {
        this.nasProperties = nasProperties;
    }

    public String baseUrl(String pathPrefix) {
        String normalizedPrefix = normalizePrefix(pathPrefix);
        String publicBaseUrl = nasProperties.getServer().getPublicBaseUrl();
        if (publicBaseUrl != null && !publicBaseUrl.isBlank()) {
            return trimTrailingSlash(publicBaseUrl.trim()) + normalizedPrefix;
        }
        return ServletUriComponentsBuilder.fromCurrentContextPath()
                .path(normalizedPrefix)
                .toUriString();
    }

    private String normalizePrefix(String pathPrefix) {
        String normalized = pathPrefix == null ? "" : pathPrefix.trim();
        if (!normalized.startsWith("/")) {
            normalized = "/" + normalized;
        }
        return normalized.endsWith("/") ? normalized : normalized + "/";
    }

    private String trimTrailingSlash(String value) {
        String trimmed = value;
        while (trimmed.endsWith("/")) {
            trimmed = trimmed.substring(0, trimmed.length() - 1);
        }
        return trimmed;
    }
}
