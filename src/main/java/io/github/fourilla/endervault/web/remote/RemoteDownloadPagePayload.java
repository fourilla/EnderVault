package io.github.fourilla.endervault.web.remote;

import java.util.List;

public record RemoteDownloadPagePayload(
        List<RemoteDownloadTaskPayload> tasks,
        boolean skipInspectByDefault,
        String defaultTargetDirectory
) {
}
