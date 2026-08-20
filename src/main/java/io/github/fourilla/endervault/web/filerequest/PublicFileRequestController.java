package io.github.fourilla.endervault.web.filerequest;

import io.github.fourilla.endervault.activity.ActivityLogService;
import io.github.fourilla.endervault.common.ByteSizeFormatter;
import io.github.fourilla.endervault.config.NasProperties;
import io.github.fourilla.endervault.filerequest.FileRequest;
import io.github.fourilla.endervault.filerequest.FileRequestService;
import io.github.fourilla.endervault.filerequest.UploaderNamePolicy;
import io.github.fourilla.endervault.publiclink.PublicLinkTokenService;
import io.github.fourilla.endervault.upload.ResumableUploadAdmissionRequest;
import io.github.fourilla.endervault.upload.ResumableUploadAdmissionResponse;
import io.github.fourilla.endervault.upload.ResumableUploadService;
import io.github.fourilla.endervault.upload.ResumableUploadSession;
import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseBody;

@Controller
public class PublicFileRequestController {

    private final FileRequestService fileRequestService;
    private final ResumableUploadService resumableUploadService;
    private final PublicLinkTokenService publicLinkTokenService;
    private final ActivityLogService activityLogService;
    private final NasProperties.FileRequest properties;

    public PublicFileRequestController(
            FileRequestService fileRequestService,
            ResumableUploadService resumableUploadService,
            PublicLinkTokenService publicLinkTokenService,
            ActivityLogService activityLogService,
            NasProperties nasProperties
    ) {
        this.fileRequestService = fileRequestService;
        this.resumableUploadService = resumableUploadService;
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
        model.addAttribute("uploadAdmissionUrl", "/r/" + token + "/upload-sessions");
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
            value = "/r/{token}/upload-sessions",
            consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE
    )
    @ResponseBody
    public ResumableUploadAdmissionResponse createUploadSession(
            @PathVariable String token,
            @RequestBody ResumableUploadAdmissionRequest request
    ) throws IOException {
        ResumableUploadSession session = resumableUploadService.admitFileRequest(
                token,
                request.filename(),
                request.contentType(),
                request.size(),
                request.uploaderName(),
                request.fingerprint(),
                request.resumeSessionId()
        );
        return ResumableUploadAdmissionResponse.from(session, resumableUploadService);
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
