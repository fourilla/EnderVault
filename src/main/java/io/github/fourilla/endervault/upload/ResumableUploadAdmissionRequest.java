package io.github.fourilla.endervault.upload;

public record ResumableUploadAdmissionRequest(
        String filename,
        String contentType,
        long size,
        long lastModified,
        String fingerprint,
        String resumeSessionId,
        String uploaderName
) {
}
