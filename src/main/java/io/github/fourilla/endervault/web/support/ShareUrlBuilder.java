package io.github.fourilla.endervault.web.support;

import io.github.fourilla.endervault.config.NasProperties;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

@Component
public class ShareUrlBuilder {

    private final NasProperties nasProperties;

    public ShareUrlBuilder(NasProperties nasProperties) {
        this.nasProperties = nasProperties;
    }

    public String shareBaseUrl() {
        String publicBaseUrl = nasProperties.getServer().getPublicBaseUrl();
        if (publicBaseUrl != null && !publicBaseUrl.isBlank()) {
            return trimTrailingSlash(publicBaseUrl.trim()) + "/s/";
        }
        return ServletUriComponentsBuilder.fromCurrentContextPath()
                .path("/s/")
                .toUriString();
    }

    public boolean directDownloadLinkEnabled() {
        return nasProperties.getShare().isDirectDownloadLinkEnabled();
    }

    private String trimTrailingSlash(String value) {
        String trimmed = value;
        while (trimmed.endsWith("/")) {
            trimmed = trimmed.substring(0, trimmed.length() - 1);
        }
        return trimmed;
    }
}
