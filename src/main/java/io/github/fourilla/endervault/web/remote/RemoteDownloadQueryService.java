package io.github.fourilla.endervault.web.remote;

import io.github.fourilla.endervault.config.NasProperties;
import org.springframework.stereotype.Service;

@Service
public class RemoteDownloadQueryService {

    private final NasProperties nasProperties;

    public RemoteDownloadQueryService(NasProperties nasProperties) {
        this.nasProperties = nasProperties;
    }

    public RemoteDownloadPagePayload load() {
        return new RemoteDownloadPagePayload(
                nasProperties.getRemoteDownload().isSkipInspectByDefault(),
                nasProperties.getRemoteDownload().getDefaultTargetDirectory()
        );
    }
}
