package io.github.fourilla.endervault.web.api.v1.filerequest;

import io.github.fourilla.endervault.activity.ActivityLogService;
import io.github.fourilla.endervault.common.StorageAccessException;
import io.github.fourilla.endervault.filerequest.FileRequest;
import io.github.fourilla.endervault.filerequest.FileRequestDependencyException;
import io.github.fourilla.endervault.filerequest.FileRequestOperationsService;
import io.github.fourilla.endervault.filerequest.FileRequestOperationsService.CancelUploadsResult;
import io.github.fourilla.endervault.filerequest.FileRequestOperationsService.ExpiredDeletionResult;
import io.github.fourilla.endervault.filerequest.FileRequestService;
import io.github.fourilla.endervault.filerequest.UploaderNamePolicy;
import io.github.fourilla.endervault.publiclink.PublicLinkTokenService;
import io.github.fourilla.endervault.web.filerequest.FileRequestUrlBuilder;
import io.github.fourilla.endervault.web.support.ActionResponse;
import io.github.fourilla.endervault.web.support.FlashNotification;
import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/file-requests")
public class FileRequestApiController {

    private static final BigDecimal BYTES_PER_GIB = BigDecimal.valueOf(1024L * 1024 * 1024);

    private final FileRequestService fileRequestService;
    private final FileRequestOperationsService operationsService;
    private final FileRequestUrlBuilder fileRequestUrlBuilder;
    private final PublicLinkTokenService publicLinkTokenService;
    private final ActivityLogService activityLogService;
    private final FileRequestAdminQueryService adminQueryService;

    public FileRequestApiController(
            FileRequestService fileRequestService,
            FileRequestOperationsService operationsService,
            FileRequestUrlBuilder fileRequestUrlBuilder,
            PublicLinkTokenService publicLinkTokenService,
            ActivityLogService activityLogService,
            FileRequestAdminQueryService adminQueryService
    ) {
        this.fileRequestService = fileRequestService;
        this.operationsService = operationsService;
        this.fileRequestUrlBuilder = fileRequestUrlBuilder;
        this.publicLinkTokenService = publicLinkTokenService;
        this.activityLogService = activityLogService;
        this.adminQueryService = adminQueryService;
    }

    @GetMapping
    public FileRequestAdminPayloads.ListPayload list(
            @RequestParam(value = "destinationPath", required = false) String destinationPath,
            @RequestParam(value = "copyFrom", required = false) String copyFrom
    ) throws IOException {
        return adminQueryService.list(destinationPath, copyFrom);
    }

    @GetMapping("/{id}")
    public FileRequestAdminPayloads.DetailPayload detail(@PathVariable String id) throws IOException {
        return adminQueryService.detail(id);
    }

    @PostMapping
    public ActionResponse create(
            @RequestParam String title,
            @RequestParam(value = "description", required = false) String description,
            @RequestParam(value = "destinationPath", required = false) String destinationPath,
            @RequestParam UploaderNamePolicy uploaderNamePolicy,
            @RequestParam BigDecimal maxFileSizeGb,
            @RequestParam BigDecimal maxTotalGb,
            @RequestParam int maxFiles,
            @RequestParam(value = "allowedExtensions", required = false) String allowedExtensions,
            @RequestParam int expirationDays,
            @RequestParam(value = "customToken", required = false) String customToken,
            HttpServletRequest servletRequest
    ) throws IOException {
        FileRequest request = fileRequestService.create(
                title,
                description,
                destinationPath,
                uploaderNamePolicy,
                bytes(maxFileSizeGb, "Maximum file size"),
                bytes(maxTotalGb, "Total quota"),
                maxFiles,
                allowedExtensions == null ? List.of() : List.of(allowedExtensions),
                expirationDays,
                customToken
        );
        activityLogService.record(
                "FILE_REQUEST_CREATE",
                servletRequest,
                request.destinationPath(),
                null,
                "File request created",
                metadata(request)
        );
        String url = fileRequestUrlBuilder.url(request.token());
        return ActionResponse.redirect(
                FlashNotification.info("File request created.", "Copy link", url),
                "/admin/file-requests/" + request.id()
        );
    }

    @PostMapping("/{id}/revoke")
    public ActionResponse revoke(@PathVariable String id, HttpServletRequest servletRequest) throws IOException {
        FileRequest request = operationsService.revoke(id);
        activityLogService.record(
                "FILE_REQUEST_REVOKE", servletRequest, request.destinationPath(), null,
                "File request revoked", metadata(request)
        );
        return ActionResponse.ok(FlashNotification.success("File request revoked."));
    }

    @PostMapping("/{id}/delete")
    public ActionResponse delete(@PathVariable String id, HttpServletRequest servletRequest) throws IOException {
        FileRequest request = operationsService.delete(id);
        activityLogService.record(
                "FILE_REQUEST_DELETE", servletRequest, request.destinationPath(), null,
                "File request deleted", metadata(request)
        );
        return ActionResponse.ok(FlashNotification.success("File request deleted."));
    }

    @PostMapping("/{id}/active-uploads/cancel")
    public ActionResponse cancelActiveUploads(
            @PathVariable String id,
            HttpServletRequest servletRequest
    ) throws IOException {
        CancelUploadsResult result = operationsService.cancelActiveUploads(id);
        String message = "Canceled " + result.canceled() + " active upload(s).";
        activityLogService.record(
                "FILE_REQUEST_UPLOAD", servletRequest, result.request().destinationPath(), null,
                message, metadata(result.request())
        );
        return ActionResponse.redirect(
                FlashNotification.success(message),
                "/admin/file-requests/" + result.request().id()
        );
    }

    @PostMapping("/expired/delete")
    public ActionResponse deleteExpired(HttpServletRequest servletRequest) throws IOException {
        ExpiredDeletionResult result = operationsService.deleteExpired(Instant.now());
        String message = "Deleted " + result.deleted() + " expired file request(s).";
        if (result.skipped() > 0) {
            message += " Skipped " + result.skipped() + " with active uploads or pending files.";
        }
        activityLogService.record(
                "FILE_REQUEST_DELETE", servletRequest, null, null, message,
                Map.of("deleted", String.valueOf(result.deleted()), "skipped", String.valueOf(result.skipped()))
        );
        FlashNotification notification = result.skipped() > 0
                ? FlashNotification.warning(message)
                : FlashNotification.success(message);
        return ActionResponse.redirect(notification, "/admin/file-requests");
    }

    @ExceptionHandler(FileRequestDependencyException.class)
    public ResponseEntity<ActionResponse> dependencyConflict(FileRequestDependencyException exception) {
        return ResponseEntity.status(HttpStatus.CONFLICT).body(ActionResponse.error(exception.getMessage()));
    }

    @ExceptionHandler(StorageAccessException.class)
    public ResponseEntity<ActionResponse> invalidRequest(StorageAccessException exception) {
        return ResponseEntity.badRequest().body(ActionResponse.error(exception.getMessage()));
    }

    private long bytes(BigDecimal gibibytes, String label) {
        if (gibibytes == null || gibibytes.signum() <= 0) {
            throw new StorageAccessException(label + " must be greater than zero.");
        }
        try {
            return gibibytes.multiply(BYTES_PER_GIB).setScale(0, RoundingMode.UNNECESSARY).longValueExact();
        } catch (ArithmeticException ex) {
            throw new StorageAccessException(label + " must resolve to a whole number of bytes.", ex);
        }
    }

    private Map<String, String> metadata(FileRequest request) {
        Map<String, String> metadata = new LinkedHashMap<>();
        metadata.put("requestId", request.id());
        metadata.put("tokenFingerprint", publicLinkTokenService.fingerprint(request.token()));
        return Map.copyOf(metadata);
    }
}
