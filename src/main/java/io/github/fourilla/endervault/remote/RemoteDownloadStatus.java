package io.github.fourilla.endervault.remote;

public enum RemoteDownloadStatus {
    QUEUED("Queued"),
    RUNNING("Running"),
    PENDING("Needs review"),
    COMPLETE("Complete"),
    FAILED("Failed"),
    CANCELED("Canceled");

    private final String label;

    RemoteDownloadStatus(String label) {
        this.label = label;
    }

    public String label() {
        return label;
    }

    public boolean active() {
        return this == QUEUED || this == RUNNING;
    }
}
