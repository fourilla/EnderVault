package io.github.fourilla.endervault.web.remote;

public record RemoteDownloadInspectResponse(
        boolean ok,
        String requestId,
        RemoteDownloadProbePayload probe
) {

    public static RemoteDownloadInspectResponse ok(String requestId, RemoteDownloadProbePayload probe) {
        return new RemoteDownloadInspectResponse(true, requestId, probe);
    }
}
