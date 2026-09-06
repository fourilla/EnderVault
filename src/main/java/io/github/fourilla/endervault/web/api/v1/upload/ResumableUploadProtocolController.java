package io.github.fourilla.endervault.web.api.v1.upload;

import io.github.fourilla.endervault.upload.ResumableUploadCoordinator;
import io.github.fourilla.endervault.upload.ResumableUploadService;
import io.github.fourilla.endervault.upload.ResumableUploadSession;
import io.github.fourilla.endervault.upload.ResumableUploadSource;
import io.github.fourilla.endervault.upload.ResumableUploadStatus;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class ResumableUploadProtocolController {

    private final ResumableUploadCoordinator coordinator;
    private final ResumableUploadService uploadService;

    public ResumableUploadProtocolController(
            ResumableUploadCoordinator coordinator,
            ResumableUploadService uploadService
    ) {
        this.coordinator = coordinator;
        this.uploadService = uploadService;
    }

    @GetMapping(value = "/api/v1/uploads/{sessionId}/status", produces = MediaType.APPLICATION_JSON_VALUE)
    public UploadStatusResponse status(
            @PathVariable String sessionId,
            HttpServletRequest request
    ) throws IOException {
        ResumableUploadSession session = uploadService.authorizeProtocolAccess(
                sessionId,
                request.isUserInRole("ADMIN")
        );
        boolean admin = session.source() == ResumableUploadSource.ADMIN;
        return new UploadStatusResponse(
                true,
                clientStatus(session),
                statusMessage(session),
                admin ? session.committedPath() : null,
                admin ? session.pendingDecisionId() : null,
                admin ? uploadService.defaultConflictPolicy() : null
        );
    }

    @DeleteMapping(value = "/api/v1/uploads/{sessionId}/status", produces = MediaType.APPLICATION_JSON_VALUE)
    public UploadStatusResponse cancel(
            @PathVariable String sessionId,
            HttpServletRequest request
    ) throws IOException {
        ResumableUploadSession session = uploadService.authorizeCancellation(
                sessionId,
                request.isUserInRole("ADMIN")
        );
        coordinator.deleteProtocolDataIfPresent(session);
        ResumableUploadSession canceled = uploadService.cancel(session.id());
        return new UploadStatusResponse(
                true,
                clientStatus(canceled),
                statusMessage(canceled),
                null,
                null,
                null
        );
    }

    @RequestMapping({
            "/api/v1/uploads/{sessionId}",
            "/api/v1/uploads/{sessionId}/{uploadId}"
    })
    public void protocol(
            @PathVariable String sessionId,
            HttpServletRequest request,
            HttpServletResponse response
    ) throws IOException {
        coordinator.process(sessionId, request, response);
    }

    private String clientStatus(ResumableUploadSession session) {
        if (session.source() == ResumableUploadSource.FILE_REQUEST
                && (session.status() == ResumableUploadStatus.PENDING
                || session.status() == ResumableUploadStatus.COMPLETED)) {
            return "RECEIVED";
        }
        return session.status().name();
    }

    private String statusMessage(ResumableUploadSession session) {
        if (session.source() == ResumableUploadSource.FILE_REQUEST
                && (session.status() == ResumableUploadStatus.PENDING
                || session.status() == ResumableUploadStatus.COMPLETED)) {
            return "Upload received.";
        }
        return switch (session.status()) {
            case ADMITTED -> "Upload is ready to start.";
            case UPLOADING -> "Upload is in progress.";
            case STAGED, FINALIZING -> "Upload is being finalized.";
            case DIRECTORY_READY -> "File received for directory upload.";
            case PENDING -> "Upload was received and is waiting for an administrator decision.";
            case COMPLETED -> "Upload completed.";
            case CANCELED -> "Upload was canceled.";
            case FAILED -> "Upload failed during finalization.";
        };
    }

    public record UploadStatusResponse(
            boolean ok,
            String status,
            String message,
            String committedPath,
            String pendingDecisionId,
            String defaultConflictPolicy
    ) {
    }
}
