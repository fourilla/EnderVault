package io.github.fourilla.endervault.web.file;

import static io.github.fourilla.endervault.web.file.FileRedirects.clean;
import static io.github.fourilla.endervault.web.file.FileRedirects.redirectToFiles;
import static io.github.fourilla.endervault.web.file.FileRedirects.targetPath;

import io.github.fourilla.endervault.activity.ActivityLogService;
import io.github.fourilla.endervault.pending.PendingFileDecision;
import io.github.fourilla.endervault.pending.PendingFileDecisionAction;
import io.github.fourilla.endervault.pending.PendingFileDecisionService;
import io.github.fourilla.endervault.pending.PendingFileDecisionService.PendingFileDecisionResult;
import io.github.fourilla.endervault.storage.ConflictPolicy;
import io.github.fourilla.endervault.storage.FileItem;
import io.github.fourilla.endervault.storage.StorageService;
import io.github.fourilla.endervault.web.support.ActionResponseSupport;
import io.github.fourilla.endervault.web.support.FlashNotification;
import io.github.fourilla.endervault.web.support.UploadedFilePayload;
import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.util.Map;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

@Controller
public class AdminFileUploadController {

    private final StorageService storageService;
    private final ActivityLogService activityLogService;
    private final PendingFileDecisionService pendingFileDecisionService;

    public AdminFileUploadController(
            StorageService storageService,
            ActivityLogService activityLogService,
            PendingFileDecisionService pendingFileDecisionService
    ) {
        this.storageService = storageService;
        this.activityLogService = activityLogService;
        this.pendingFileDecisionService = pendingFileDecisionService;
    }

    @PostMapping("/files/upload/conflicts/resolve")
    public Object resolveUploadConflict(
            @RequestParam("id") String id,
            @RequestParam(value = "conflictPolicy", required = false) String conflictPolicy,
            @RequestParam(value = "view", required = false) String view,
            @RequestParam(value = "sort", required = false) String sort,
            @RequestParam(value = "dir", required = false) String direction,
            @RequestParam(value = "page", required = false) Integer page,
            @RequestParam(value = "size", required = false) Integer size,
            HttpServletRequest request,
            RedirectAttributes redirectAttributes
    ) throws IOException {
        PendingFileDecision decision = pendingFileDecisionService.require(id);
        ConflictPolicy policy = effectiveUploadConflictPolicy(conflictPolicy);
        String redirect = redirectToFiles(decision.destinationPath(), view, sort, direction, page, size);
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
            return ActionResponseSupport.ok(
                    request,
                    redirectAttributes,
                    notification,
                    redirect,
                    UploadConflictResolveResponse.ok(
                            notification,
                            null,
                            ActionResponseSupport.redirectUrl(redirect)
                    )
            );
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
        return ActionResponseSupport.ok(
                request,
                redirectAttributes,
                notification,
                redirect,
                UploadConflictResolveResponse.ok(
                        notification,
                        UploadedFilePayload.from(uploadedFile),
                        ActionResponseSupport.redirectUrl(redirect)
                )
        );
    }

    private ConflictPolicy effectiveUploadConflictPolicy(String conflictPolicy) {
        if ("default".equalsIgnoreCase(clean(conflictPolicy))) {
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
