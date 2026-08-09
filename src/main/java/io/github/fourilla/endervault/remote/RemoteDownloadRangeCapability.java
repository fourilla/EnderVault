package io.github.fourilla.endervault.remote;

public enum RemoteDownloadRangeCapability {
    SUPPORTED("Supported"),
    UNSUPPORTED("Not supported"),
    UNKNOWN("Unknown");

    private final String label;

    RemoteDownloadRangeCapability(String label) {
        this.label = label;
    }

    public String label() {
        return label;
    }
}
