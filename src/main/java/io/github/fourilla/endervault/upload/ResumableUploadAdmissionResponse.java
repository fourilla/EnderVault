package io.github.fourilla.endervault.upload;

import java.time.Instant;

public record ResumableUploadAdmissionResponse(
        boolean ok,
        String sessionId,
        String endpoint,
        String uploadUrl,
        String statusUrl,
        long chunkSizeBytes,
        Instant expiresAt,
        boolean ready
) {

    public static ResumableUploadAdmissionResponse from(
            ResumableUploadSession session,
            ResumableUploadService service
    ) {
        String endpoint = service.endpoint(session.id());
        return new ResumableUploadAdmissionResponse(
                true,
                session.id(),
                endpoint,
                session.protocolUploadUri(),
                endpoint + "/status",
                service.chunkSizeBytes(),
                session.expiresAt(),
                session.status() == ResumableUploadStatus.DIRECTORY_READY
        );
    }
}
