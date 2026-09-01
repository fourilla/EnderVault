package io.github.fourilla.endervault.web.remote;

public record RemoteDownloadPagePayload(
        boolean skipInspectByDefault,
        String defaultTargetDirectory
) {
}
