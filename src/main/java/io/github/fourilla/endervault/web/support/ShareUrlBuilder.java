package io.github.fourilla.endervault.web.support;

import io.github.fourilla.endervault.config.NasProperties;
import org.springframework.stereotype.Component;

@Component
public class ShareUrlBuilder {

    private final NasProperties nasProperties;
    private final PublicUrlBuilder publicUrlBuilder;

    public ShareUrlBuilder(NasProperties nasProperties, PublicUrlBuilder publicUrlBuilder) {
        this.nasProperties = nasProperties;
        this.publicUrlBuilder = publicUrlBuilder;
    }

    public String shareBaseUrl() {
        return publicUrlBuilder.baseUrl("/s/");
    }

    public boolean directDownloadLinkEnabled() {
        return nasProperties.getShare().isDirectDownloadLinkEnabled();
    }
}
