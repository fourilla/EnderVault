package io.github.fourilla.endervault.remote;

import io.github.fourilla.endervault.common.ExternalUrlValidator;
import io.github.fourilla.endervault.config.NasProperties;
import java.net.URI;
import org.springframework.stereotype.Component;

@Component
public class RemoteDownloadValidator {

    private final NasProperties nasProperties;

    public RemoteDownloadValidator(NasProperties nasProperties) {
        this.nasProperties = nasProperties;
    }

    public URI validate(String rawUrl) {
        return ExternalUrlValidator.validate(rawUrl, policy());
    }

    public URI validate(URI uri) {
        return ExternalUrlValidator.validate(uri, policy());
    }

    public URI validateRedirect(URI currentUri, String location) {
        return ExternalUrlValidator.validateRedirect(currentUri, location, policy());
    }

    private ExternalUrlValidator.Policy policy() {
        NasProperties.RemoteDownload remoteDownload = nasProperties.getRemoteDownload();
        return new ExternalUrlValidator.Policy(
                "Remote URL",
                "remote downloads",
                "Remote download port",
                "Remote host",
                "Remote server",
                remoteDownload.getAllowedPorts(),
                remoteDownload.isBlockPrivateNetworks()
        );
    }
}
