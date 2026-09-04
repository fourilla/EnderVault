package io.github.fourilla.endervault.upload;

import java.time.Instant;

public record ResumableUploadSession(
        String id,
        ResumableUploadSource source,
        String sourceReference,
        String destinationPath,
        String originalFilename,
        String contentType,
        long size,
        String submittedBy,
        String fingerprint,
        Instant createdAt,
        Instant expiresAt,
        ResumableUploadStatus status,
        String protocolUploadUri,
        String stagingFilename,
        String committedPath,
        String pendingDecisionId,
        String failureMessage
) {

    public ResumableUploadSession withProtocolUpload(String uploadUri) {
        return copy(ResumableUploadStatus.UPLOADING, uploadUri, stagingFilename,
                committedPath, pendingDecisionId, failureMessage);
    }

    public ResumableUploadSession withStagingFile(String filename) {
        return copy(ResumableUploadStatus.STAGED, protocolUploadUri, filename,
                committedPath, pendingDecisionId, failureMessage);
    }

    public ResumableUploadSession finalizing() {
        return copy(ResumableUploadStatus.FINALIZING, protocolUploadUri, stagingFilename,
                committedPath, pendingDecisionId, failureMessage);
    }

    public ResumableUploadSession withCommittedTarget(String path) {
        return copy(ResumableUploadStatus.FINALIZING, protocolUploadUri, stagingFilename,
                path, pendingDecisionId, failureMessage);
    }

    public ResumableUploadSession completed(String path) {
        return copy(ResumableUploadStatus.COMPLETED, protocolUploadUri, null,
                path, null, null);
    }

    public ResumableUploadSession pending(String decisionId) {
        return copy(ResumableUploadStatus.PENDING, protocolUploadUri, null,
                null, decisionId, null);
    }

    public ResumableUploadSession canceled() {
        return copy(ResumableUploadStatus.CANCELED, protocolUploadUri, null,
                null, null, null);
    }

    public ResumableUploadSession failed(String message) {
        return copy(ResumableUploadStatus.FAILED, protocolUploadUri, stagingFilename,
                committedPath, pendingDecisionId, cleanMessage(message));
    }

    public boolean expired(Instant now) {
        return expiresAt != null && !expiresAt.isAfter(now);
    }

    private ResumableUploadSession copy(
            ResumableUploadStatus nextStatus,
            String nextProtocolUploadUri,
            String nextStagingFilename,
            String nextCommittedPath,
            String nextPendingDecisionId,
            String nextFailureMessage
    ) {
        return new ResumableUploadSession(
                id, source, sourceReference, destinationPath, originalFilename, contentType,
                size, submittedBy, fingerprint, createdAt, expiresAt, nextStatus,
                nextProtocolUploadUri, nextStagingFilename, nextCommittedPath,
                nextPendingDecisionId, nextFailureMessage
        );
    }

    private String cleanMessage(String message) {
        if (message == null || message.isBlank()) {
            return "Upload finalization failed.";
        }
        String cleaned = message.replace('\r', ' ').replace('\n', ' ').trim();
        return cleaned.length() <= 240 ? cleaned : cleaned.substring(0, 240);
    }
}
