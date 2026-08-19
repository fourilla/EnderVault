package io.github.fourilla.endervault.web.filerequest;

import io.github.fourilla.endervault.web.support.PublicUrlBuilder;
import org.springframework.stereotype.Component;

@Component
public class FileRequestUrlBuilder {

    private final PublicUrlBuilder publicUrlBuilder;

    public FileRequestUrlBuilder(PublicUrlBuilder publicUrlBuilder) {
        this.publicUrlBuilder = publicUrlBuilder;
    }

    public String baseUrl() {
        return publicUrlBuilder.baseUrl("/r/");
    }

    public String url(String token) {
        return baseUrl() + token;
    }
}
