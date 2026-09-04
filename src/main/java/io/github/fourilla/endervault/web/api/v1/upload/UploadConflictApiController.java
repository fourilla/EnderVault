package io.github.fourilla.endervault.web.api.v1.upload;

import static io.github.fourilla.endervault.web.file.FileRedirects.filesUrl;
import static io.github.fourilla.endervault.web.file.FileRedirects.targetPath;

import io.github.fourilla.endervault.activity.ActivityLogService;
import io.github.fourilla.endervault.pending.PendingFileDecision;
import io.github.fourilla.endervault.pending.PendingFileDecisionAction;
import io.github.fourilla.endervault.pending.PendingFileDecisionService;
import io.github.fourilla.endervault.pending.PendingFileDecisionService.PendingFileDecisionResult;
import io.github.fourilla.endervault.storage.ConflictPolicy;
import io.github.fourilla.endervault.storage.FileItem;
import io.github.fourilla.endervault.storage.StorageService;
import io.github.fourilla.endervault.web.support.FlashNotification;
import io.github.fourilla.endervault.web.support.UploadedFilePayload;
import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.util.Map;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/files/upload-conflicts")
public class UploadConflictApiController {

    private final StorageService storageService;
    private final ActivityLogService activityLogService;
    private final PendingFileDecisionService pendingFileDecisionService;

    public UploadConflictApiController(
            StorageService storageService,
            ActivityLogService activityLogService,
            PendingFileDecisionService pendingFileDecisionService
    ) {
        this.storageService = storageService;
        this.activityLogService = activityLogService;
        this.pendingFileDecisionService = pendingFileDecisionService;
    }

    @PostMapping("/resolve")
    public UploadConflictResolveResponse resolve(
            @RequestParam("id") String id,
            @RequestParam(value = "conflictPolicy", required = false) String conflictPolicy,
            HttpServletRequest request
    ) throws IOException {
        PendingFileDecision decision = pendingFileDecisionService.require(id);
        ConflictPolicy policy = effectiveUploadConflictPolicy(conflictPolicy);
        PendingFileDecisionAction action = switch (policy) {
            case RENAME -> PendingFileDecisionAction.KEEP_BOTH;
            case OVERWRITE -> PendingFileDecisionAction.REPLACE;
            case CANCEL -> PendingFileDecisionAction.DISCARD;
        };
        PendingFileDecisionResult result = pendingFileDecisionService.resolve(
                decision.id(),
                action,
                null,
                action == PendingFileDecisionAction.REPLACE
        );

        if (result.discarded()) {
            activityLogService.record(
                    "UPLOAD",
                    request,
                    targetPath(decision.destinationPath(), decision.originalFilename()),
                    null,
                    false,
                    "Upload canceled after file name conflict",
                    Map.of("reason", "conflict-canceled")
            );
            FlashNotification notification = FlashNotification.warning("Upload canceled.");
            return UploadConflictResolveResponse.ok(notification, null, filesUrl(decision.destinationPath()));
        }

        FileItem uploadedFile = result.committedFile();
        activityLogService.record(
                "UPLOAD",
                request,
                uploadedFile.path(),
                null,
                "Uploaded " + uploadedFile.name(),
                Map.of("size", uploadedFile.sizeLabel(), "conflictPolicy", policy.value())
        );
        FlashNotification notification = FlashNotification.success("Upload complete.");
        return UploadConflictResolveResponse.ok(
                notification,
                UploadedFilePayload.from(uploadedFile),
                filesUrl(decision.destinationPath())
        );
    }

    private ConflictPolicy effectiveUploadConflictPolicy(String conflictPolicy) {
        String value = conflictPolicy == null ? "" : conflictPolicy.trim();
        if ("default".equalsIgnoreCase(value)) {
            return storageService.defaultConflictPolicy();
        }
        ConflictPolicy policy = ConflictPolicy.from(conflictPolicy);
        return policy == null ? storageService.defaultConflictPolicy() : policy;
    }

    private record UploadConflictResolveResponse(
            boolean ok,
            FlashNotification notification,
            UploadedFilePayload uploadedFile,
            String redirectUrl
    ) {
        static UploadConflictResolveResponse ok(
                FlashNotification notification,
                UploadedFilePayload uploadedFile,
                String redirectUrl
        ) {
            return new UploadConflictResolveResponse(true, notification, uploadedFile, redirectUrl);
        }
    }
}
