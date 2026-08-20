package io.github.fourilla.endervault.web.api.v1.pending;

import io.github.fourilla.endervault.activity.ActivityLogService;
import io.github.fourilla.endervault.pending.PendingFileDecision;
import io.github.fourilla.endervault.pending.PendingFileDecisionAction;
import io.github.fourilla.endervault.pending.PendingFileDecisionService;
import io.github.fourilla.endervault.pending.PendingFileDecisionService.PendingFileDecisionResult;
import io.github.fourilla.endervault.storage.FileItem;
import io.github.fourilla.endervault.web.support.FlashNotification;
import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.util.Map;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/pending-decisions")
public class PendingFileDecisionApiController {

    private final PendingFileDecisionService pendingFileDecisionService;
    private final ActivityLogService activityLogService;

    public PendingFileDecisionApiController(
            PendingFileDecisionService pendingFileDecisionService,
            ActivityLogService activityLogService
    ) {
        this.pendingFileDecisionService = pendingFileDecisionService;
        this.activityLogService = activityLogService;
    }

    @PostMapping(value = "/{id}/resolve", produces = MediaType.APPLICATION_JSON_VALUE)
    public PendingFileDecisionActionResponse resolve(
            @PathVariable String id,
            @RequestParam PendingFileDecisionAction action,
            @RequestParam(value = "filename", required = false) String filename,
            @RequestParam(value = "replaceConfirmed", defaultValue = "false") boolean replaceConfirmed,
            HttpServletRequest request
    ) throws IOException {
        PendingFileDecisionResult result = pendingFileDecisionService.resolve(
                id,
                action,
                filename,
                replaceConfirmed
        );
        PendingFileDecision decision = result.decision();
        FileItem committedFile = result.committedFile();
        String message = result.discarded()
                ? "Pending file discarded."
                : "Pending file saved as " + committedFile.name() + ".";
        activityLogService.record(
                "PENDING_FILE_DECISION_RESOLVE",
                request,
                committedFile == null ? decision.destinationPath() : committedFile.path(),
                decision.originalFilename(),
                message,
                Map.of(
                        "decisionId", decision.id(),
                        "source", decision.source().name(),
                        "action", action.name()
                )
        );
        return new PendingFileDecisionActionResponse(
                true,
                FlashNotification.success(message),
                decision.id(),
                committedFile == null ? null : committedFile.path()
        );
    }

    public record PendingFileDecisionActionResponse(
            boolean ok,
            FlashNotification notification,
            String removedId,
            String committedPath
    ) {
    }
}
