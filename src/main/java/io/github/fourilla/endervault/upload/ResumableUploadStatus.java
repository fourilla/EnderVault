package io.github.fourilla.endervault.upload;

public enum ResumableUploadStatus {
    ADMITTED,
    UPLOADING,
    STAGED,
    DIRECTORY_READY,
    FINALIZING,
    PENDING,
    COMPLETED,
    CANCELED,
    FAILED;

    public boolean terminal() {
        return this == PENDING || this == COMPLETED || this == CANCELED || this == FAILED || this == DIRECTORY_READY;
    }

    public boolean reservesQuota() {
        return this == ADMITTED || this == UPLOADING || this == STAGED || this == FINALIZING;
    }
}
