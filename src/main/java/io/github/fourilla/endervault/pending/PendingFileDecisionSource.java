package io.github.fourilla.endervault.pending;

public enum PendingFileDecisionSource {
    ADMIN_UPLOAD("Admin upload"),
    DIRECTORY_UPLOAD("Directory upload"),
    FILE_REQUEST("File request"),
    REMOTE_DOWNLOAD("Remote download"),
    ARCHIVE_OUTPUT("Archive output");

    private final String label;

    PendingFileDecisionSource(String label) {
        this.label = label;
    }

    public String label() {
        return label;
    }
}
