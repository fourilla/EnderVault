package io.github.fourilla.endervault.temporary;

public enum TemporaryArtifactType {
    DIRECTORY_UPLOAD("Directory upload"),
    PENDING_FILE_DECISION("Pending file decision"),
    REMOTE_DOWNLOAD("Remote download"),
    FILE_COPY("File copy"),
    TEXT_RECOVERY("Text draft recovery"),
    ARCHIVE_EXTRACTION("Archive extraction"),
    ARCHIVE_CREATION("Archive creation"),
    THUMBNAIL("Thumbnail generation"),
    BOOKMARK_FAVICON("Bookmark favicon");

    private final String label;

    TemporaryArtifactType(String label) {
        this.label = label;
    }

    public String label() {
        return label;
    }
}
