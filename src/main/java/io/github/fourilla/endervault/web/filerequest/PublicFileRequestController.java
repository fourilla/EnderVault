package io.github.fourilla.endervault.web.filerequest;

import io.github.fourilla.endervault.activity.ActivityLogService;
import io.github.fourilla.endervault.common.ByteSizeFormatter;
import io.github.fourilla.endervault.config.NasProperties;
import io.github.fourilla.endervault.filerequest.FileRequest;
import io.github.fourilla.endervault.filerequest.FileRequestService;
import io.github.fourilla.endervault.filerequest.FileRequestUploadRejectedException;
import io.github.fourilla.endervault.filerequest.FileRequestUploadService;
import io.github.fourilla.endervault.filerequest.FileRequestUploadService.UploadReceipt;
import io.github.fourilla.endervault.filerequest.FileRequestUploadService.UploadTicket;
import io.github.fourilla.endervault.filerequest.UploaderNamePolicy;
import io.github.fourilla.endervault.publiclink.PublicLinkTokenService;
import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseBody;

@Controller
public class PublicFileRequestController {

    private final FileRequestService fileRequestService;
    private final FileRequestUploadService fileRequestUploadService;
    private final PublicLinkTokenService publicLinkTokenService;
    private final ActivityLogService activityLogService;
    private final NasProperties.FileRequest properties;

    public PublicFileRequestController(
            FileRequestService fileRequestService,
            FileRequestUploadService fileRequestUploadService,
            PublicLinkTokenService publicLinkTokenService,
            ActivityLogService activityLogService,
            NasProperties nasProperties
    ) {
        this.fileRequestService = fileRequestService;
        this.fileRequestUploadService = fileRequestUploadService;
        this.publicLinkTokenService = publicLinkTokenService;
        this.activityLogService = activityLogService;
        this.properties = nasProperties.getFileRequest();
    }

    @GetMapping("/r/{token}")
    public String requestPage(@PathVariable String token, Model model, HttpServletRequest servletRequest)
            throws IOException {
        FileRequest request = fileRequestService.requireUsable(token);
        model.addAttribute("fileRequest", PublicFileRequestView.from(
                request,
                properties.getMaxConcurrentUploadsPerRequest()
        ));
        model.addAttribute("token", token);
        model.addAttribute("uploadBase", "/r/" + token + "/uploads/");
        activityLogService.record(
                "FILE_REQUEST_ACCESS",
                servletRequest,
                null,
                null,
                "File request page accessed",
                metadata(request.id(), token, null, null, null, null)
        );
        return "file-request-public";
    }

    @PostMapping(
            value = "/r/{token}/tickets",
            consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE
    )
    @ResponseBody
    public TicketResponse createTicket(
            @PathVariable String token,
            @RequestBody TicketRequest request
    ) throws IOException {
        UploadTicket ticket = fileRequestUploadService.issueTicket(
                token,
                request.filename(),
                request.size(),
                request.uploaderName()
        );
        return new TicketResponse(true, ticket.id(), ticket.expiresAt());
    }

    @PutMapping(
            value = "/r/{token}/uploads/{ticketId}",
            consumes = MediaType.APPLICATION_OCTET_STREAM_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE
    )
    @ResponseBody
    public UploadResponse upload(
            @PathVariable String token,
            @PathVariable String ticketId,
            HttpServletRequest servletRequest
    ) throws IOException {
        UploadReceipt receipt = fileRequestUploadService.receive(
                token,
                ticketId,
                servletRequest.getInputStream(),
                servletRequest.getContentLengthLong()
        );
        activityLogService.record(
                "FILE_REQUEST_UPLOAD",
                servletRequest,
                receipt.committedPath(),
                null,
                "File received through file request",
                metadata(
                        receipt.requestId(),
                        token,
                        receipt.uploaderName(),
                        receipt.originalFilename(),
                        receipt.size(),
                        receipt.pendingDecision()
                )
        );
        return new UploadResponse(true, "Upload received.");
    }

    @ExceptionHandler(FileRequestUploadRejectedException.class)
    @ResponseBody
    public ResponseEntity<ErrorResponse> uploadRejected(FileRequestUploadRejectedException exception) {
        ResponseEntity.BodyBuilder response = ResponseEntity.status(exception.status());
        if (exception.retryAfterSeconds() != null) {
            response.header(HttpHeaders.RETRY_AFTER, String.valueOf(exception.retryAfterSeconds()));
        }
        return response.body(new ErrorResponse(false, exception.getMessage(), exception.retryAfterSeconds()));
    }

    private Map<String, String> metadata(
            String requestId,
            String token,
            String uploaderName,
            String filename,
            Long size,
            Boolean pending
    ) {
        Map<String, String> metadata = new LinkedHashMap<>();
        metadata.put("requestId", requestId);
        metadata.put("tokenFingerprint", publicLinkTokenService.fingerprint(token));
        if (uploaderName != null && !uploaderName.isBlank()) {
            metadata.put("uploaderName", uploaderName);
        }
        if (filename != null && !filename.isBlank()) {
            metadata.put("filename", filename);
        }
        if (size != null) {
            metadata.put("size", String.valueOf(size));
        }
        if (pending != null) {
            metadata.put("pendingDecision", String.valueOf(pending));
        }
        return Map.copyOf(metadata);
    }

    public record TicketRequest(String filename, long size, String uploaderName) {
    }

    public record TicketResponse(boolean ok, String ticketId, Instant expiresAt) {
    }

    public record UploadResponse(boolean ok, String message) {
    }

    public record ErrorResponse(boolean ok, String message, Integer retryAfterSeconds) {
    }

    public record PublicFileRequestView(
            String title,
            UploaderNamePolicy uploaderNamePolicy,
            String maxFileSizeLabel,
            String remainingTotalLabel,
            int remainingFiles,
            String extensionsLabel,
            String expiresLabel,
            int parallelUploads
    ) {
        static PublicFileRequestView from(FileRequest request, int parallelUploads) {
            return new PublicFileRequestView(
                    request.title(),
                    request.uploaderNamePolicy(),
                    ByteSizeFormatter.humanSize(request.maxFileSizeBytes()),
                    ByteSizeFormatter.humanSize(Math.max(0L, request.maxTotalBytes() - request.acceptedBytes())),
                    Math.max(0, request.maxFiles() - request.acceptedFiles()),
                    request.allowedExtensions().isEmpty()
                            ? "Any file type"
                            : request.allowedExtensions().stream().map(extension -> "." + extension).reduce(
                                    (left, right) -> left + ", " + right
                            ).orElse("Any file type"),
                    request.expiresLabel(),
                    parallelUploads
            );
        }
    }
}
