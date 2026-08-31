package io.github.fourilla.endervault.web.api.v1.pending;

import io.github.fourilla.endervault.activity.ActivityLogService;
import io.github.fourilla.endervault.common.ByteSizeFormatter;
import io.github.fourilla.endervault.pending.PendingFileDecision;
import io.github.fourilla.endervault.pending.PendingFileDecisionAction;
import io.github.fourilla.endervault.pending.PendingFileDecisionService;
import io.github.fourilla.endervault.pending.PendingFileDecisionService.PendingFileDecisionResult;
import io.github.fourilla.endervault.storage.FileItem;
import io.github.fourilla.endervault.web.support.FlashNotification;
import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/pending-decisions")
public class PendingFileDecisionApiController {

    private static final DateTimeFormatter CREATED_AT_FORMATTER =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm").withZone(ZoneId.systemDefault());

    private final PendingFileDecisionService pendingFileDecisionService;
    private final ActivityLogService activityLogService;

    public PendingFileDecisionApiController(
            PendingFileDecisionService pendingFileDecisionService,
            ActivityLogService activityLogService
    ) {
        this.pendingFileDecisionService = pendingFileDecisionService;
        this.activityLogService = activityLogService;
    }

    @GetMapping(produces = MediaType.APPLICATION_JSON_VALUE)
    public PendingFileDecisionListResponse list() throws IOException {
        return new PendingFileDecisionListResponse(
                pendingFileDecisionService.list().stream()
                        .map(PendingFileDecisionItemResponse::from)
                        .toList()
        );
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

    public record PendingFileDecisionListResponse(List<PendingFileDecisionItemResponse> decisions) {
    }

    public record PendingFileDecisionItemResponse(
            String id,
            String originalFilename,
            String submittedBy,
            String sourceLabel,
            String destinationLabel,
            String sizeLabel,
            String createdLabel,
            String createdAt
    ) {
        static PendingFileDecisionItemResponse from(PendingFileDecision decision) {
            return new PendingFileDecisionItemResponse(
                    decision.id(),
                    decision.originalFilename(),
                    decision.submittedBy(),
                    decision.source().label(),
                    decision.destinationPath() == null || decision.destinationPath().isBlank()
                            ? "/"
                            : "/" + decision.destinationPath(),
                    ByteSizeFormatter.humanSize(decision.size()),
                    CREATED_AT_FORMATTER.format(decision.createdAt()),
                    decision.createdAt().toString()
            );
        }
    }
}
