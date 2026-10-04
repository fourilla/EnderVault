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
import java.nio.file.NoSuchFileException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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

    private static final Logger logger = LoggerFactory.getLogger(FileRequestApiController.class);
    static final int MAX_BULK_ITEMS = 200;
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
            @RequestParam(value = "copyFrom", required = false) String copyFrom,
            @RequestParam(value = "q", required = false) String query
    ) throws IOException {
        return adminQueryService.list(destinationPath, copyFrom, query);
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

    @PostMapping("/selected/resolve")
    public ResponseEntity<?> resolveSelected(
            @RequestParam(value = "ids", required = false) List<String> ids,
            @RequestParam(value = "action", required = false) BulkAction action,
            @RequestParam(value = "confirmed", defaultValue = "false") boolean confirmed,
            HttpServletRequest servletRequest
    ) {
        List<String> selected;
        try {
            selected = validateSelection(ids, action, confirmed);
        } catch (StorageAccessException ex) {
            return ResponseEntity.badRequest().body(ActionResponse.error(ex.getMessage()));
        }
        var results = new java.util.ArrayList<BulkItemResponse>();
        for (String id : selected) {
            try {
                // Keep the single-item dependency checks and activity records.
                ActionResponse result = action == BulkAction.REVOKE
                        ? revoke(id, servletRequest) : delete(id, servletRequest);
                results.add(new BulkItemResponse(id, BulkStatus.APPLIED, result.notification().message()));
            } catch (NoSuchFileException ex) {
                results.add(new BulkItemResponse(id, BulkStatus.NOT_FOUND,
                        "This file request is no longer available. Refresh the list."));
            } catch (StorageAccessException ex) {
                results.add(new BulkItemResponse(id, BulkStatus.REJECTED, ex.getMessage()));
            } catch (IOException | RuntimeException ex) {
                logger.warn("Selected file request {} action failed for {}.", action, id, ex);
                results.add(new BulkItemResponse(id, BulkStatus.FAILED,
                        "Could not complete this action. Refresh file requests before retrying."));
            }
        }
        int succeeded = (int) results.stream().filter(item -> item.status() == BulkStatus.APPLIED).count();
        int failed = results.size() - succeeded;
        String message = "File requests processed: " + succeeded + " succeeded, " + failed + " unsuccessful.";
        FlashNotification notification = failed == 0 ? FlashNotification.success(message)
                : succeeded == 0 ? FlashNotification.error(message) : FlashNotification.warning(message);
        return ResponseEntity.ok(new BulkResponse(true, notification, succeeded, failed, List.copyOf(results)));
    }

    private static List<String> validateSelection(List<String> ids, BulkAction action, boolean confirmed) {
        if (action == null || !confirmed) {
            throw new StorageAccessException("Select a supported action and confirm the selected file requests.");
        }
        if (ids == null || ids.isEmpty() || ids.size() > MAX_BULK_ITEMS) {
            throw new StorageAccessException("Select between 1 and " + MAX_BULK_ITEMS + " file requests.");
        }
        var selected = new java.util.ArrayList<String>();
        var unique = new HashSet<String>();
        for (String id : ids) {
            String clean = id == null ? "" : id.trim();
            String canonical;
            try {
                canonical = UUID.fromString(clean).toString();
            } catch (IllegalArgumentException ex) {
                throw new StorageAccessException("Selected file request IDs must be canonical UUIDs.");
            }
            if (clean.length() != 36 || !canonical.equalsIgnoreCase(clean)) {
                throw new StorageAccessException("Selected file request IDs must be canonical UUIDs.");
            }
            if (!unique.add(canonical)) {
                throw new StorageAccessException("Selected file request IDs must not contain duplicates.");
            }
            selected.add(canonical);
        }
        return List.copyOf(selected);
    }

    public enum BulkAction { REVOKE, DELETE }
    public enum BulkStatus { APPLIED, NOT_FOUND, REJECTED, FAILED }
    public record BulkItemResponse(String id, BulkStatus status, String message) { }
    public record BulkResponse(
            boolean ok, FlashNotification notification, int succeededCount, int failedCount, List<BulkItemResponse> results
    ) { }

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
