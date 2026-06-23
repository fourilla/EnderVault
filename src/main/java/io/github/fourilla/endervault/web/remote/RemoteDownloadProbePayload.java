package io.github.fourilla.endervault.web.remote;

import io.github.fourilla.endervault.remote.RemoteDownloadProbe;

public record RemoteDownloadProbePayload(
        String sourceUrl,
        String finalUrl,
        String targetDirectory,
        String fileName,
        String targetPath,
        String contentType,
        long contentLength,
        String contentLengthLabel,
        String contentTypeLabel,
        String warningLabel
) {

    public static RemoteDownloadProbePayload from(RemoteDownloadProbe probe) {
        return new RemoteDownloadProbePayload(
                probe.sourceUrl(),
                probe.finalUrl(),
                probe.targetDirectory(),
                probe.fileName(),
                probe.targetPath(),
                probe.contentType(),
                probe.contentLength(),
                probe.contentLengthLabel(),
                probe.contentTypeLabel(),
                probe.warningLabel()
        );
    }
}
