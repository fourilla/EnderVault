package io.github.fourilla.endervault.web.remote;

public record RemoteDownloadInspectResponse(
        boolean ok,
        RemoteDownloadProbePayload probe
) {

    public static RemoteDownloadInspectResponse ok(RemoteDownloadProbePayload probe) {
        return new RemoteDownloadInspectResponse(true, probe);
    }
}
