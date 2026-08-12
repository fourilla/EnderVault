package io.github.fourilla.endervault.remote;

public enum RemoteDownloadProbeStatus {
    VERIFIED("Verified"),
    LIMITED("Limited"),
    SKIPPED("Skipped"),
    UNAVAILABLE("Unavailable");

    private final String label;

    RemoteDownloadProbeStatus(String label) {
        this.label = label;
    }

    public String label() {
        return label;
    }
}
