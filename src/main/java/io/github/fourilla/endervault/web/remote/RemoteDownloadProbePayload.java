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
        String warningLabel,
        String networkRoute,
        String networkRouteLabel,
        String status,
        String statusLabel,
        String rangeCapability,
        String rangeCapabilityLabel,
        int requestedConnections,
        boolean inspectionSkipped,
        String requestOptionsLabel,
        boolean startAllowed,
        String detail
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
                probe.warningLabel(),
                probe.networkRoute().settingValue(),
                probe.networkRouteLabel(),
                probe.status().name(),
                probe.statusLabel(),
                probe.rangeCapability().name(),
                probe.rangeCapabilityLabel(),
                probe.requestedConnections(),
                probe.inspectionSkipped(),
                probe.requestOptionsLabel(),
                probe.startAllowed(),
                probe.detail()
        );
    }
}
