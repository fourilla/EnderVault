package io.github.fourilla.endervault.web.remote;

import io.github.fourilla.endervault.config.NasProperties;
import io.github.fourilla.endervault.remote.RemoteDownloadService;
import org.springframework.stereotype.Service;

@Service
public class RemoteDownloadQueryService {

    private final RemoteDownloadService remoteDownloadService;
    private final NasProperties nasProperties;

    public RemoteDownloadQueryService(
            RemoteDownloadService remoteDownloadService,
            NasProperties nasProperties
    ) {
        this.remoteDownloadService = remoteDownloadService;
        this.nasProperties = nasProperties;
    }

    public RemoteDownloadPagePayload load() {
        return new RemoteDownloadPagePayload(
                remoteDownloadService.listTasks().stream()
                        .map(RemoteDownloadTaskPayload::from)
                        .toList(),
                nasProperties.getRemoteDownload().isSkipInspectByDefault(),
                nasProperties.getRemoteDownload().getDefaultTargetDirectory()
        );
    }
}
