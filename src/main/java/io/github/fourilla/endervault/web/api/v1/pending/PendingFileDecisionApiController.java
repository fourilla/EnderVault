package io.github.fourilla.endervault.web.api.v1.pending;

import io.github.fourilla.endervault.activity.ActivityLogService;
import io.github.fourilla.endervault.directorytransfer.DirectoryTransferQueryService;
import io.github.fourilla.endervault.directorytransfer.DirectoryTransferPlan;
import io.github.fourilla.endervault.common.ByteSizeFormatter;
import io.github.fourilla.endervault.pending.PendingFileDecision;
import io.github.fourilla.endervault.pending.PendingFileDecisionAction;
import io.github.fourilla.endervault.pending.PendingFileDecisionService;
import io.github.fourilla.endervault.pending.PendingFileDecisionService.PendingFileDecisionResult;
import io.github.fourilla.endervault.pending.PendingDecisionSearchSchema;
import io.github.fourilla.endervault.pending.PendingDecisionSearchSchema.Candidate;
import io.github.fourilla.endervault.pending.PendingDecisionStatus;
import io.github.fourilla.endervault.storage.FileItem;
import io.github.fourilla.endervault.web.support.FlashNotification;
import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.io.UncheckedIOException;
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
    private final DirectoryTransferQueryService merges;

    public PendingFileDecisionApiController(
            PendingFileDecisionService pendingFileDecisionService,
            ActivityLogService activityLogService,
            DirectoryTransferQueryService merges
    ) {
        this.pendingFileDecisionService = pendingFileDecisionService;
        this.activityLogService = activityLogService;
        this.merges = merges;
    }

    @GetMapping(produces = MediaType.APPLICATION_JSON_VALUE)
    public PendingFileDecisionListResponse list(@RequestParam(value = "q", required = false) String query) throws IOException {
        var matches = PendingDecisionSearchSchema.compile(query);
        var items = new java.util.ArrayList<PendingFileDecisionItemResponse>();
        // Read independently: do not introduce nested merge-store/Pending service locks.
        var unresolved = merges.unresolved();
        var reviewsById = new java.util.HashMap<String, DirectoryTransferQueryService.Summary>();
        unresolved.forEach(review -> reviewsById.putIfAbsent(review.id(), review));
        var pendingIds = new java.util.HashSet<String>();
        synchronized (pendingFileDecisionService) {
            for (var decision : pendingFileDecisionService.list()) {
                // Deduplicate against all Pending IDs, not just the matching rows.
                pendingIds.add(decision.id());
                var state = new ReviewState(decision, reviewsById);
                try {
                    if (!matches.test(Candidate.from(decision, state::status))) continue;
                } catch (UncheckedIOException ex) {
                    throw ex.getCause();
                }
                state.load();
                String owner = state.owner;
                var review = state.review;
                items.add(PendingFileDecisionItemResponse.from(decision, owner,
                        owner == null ? "Awaiting decision" : review == null ? "Preparing or recovering review" : mergeStatus(review)));
            }
        }
        for (var review : unresolved) {
            if (review.operation() == DirectoryTransferPlan.Operation.PENDING && pendingIds.contains(review.sourceReference())) continue;
            String path = review.destinationPath();
            String name = path.substring(path.lastIndexOf('/') + 1);
            String source = switch (review.operation()) {
                case COPY -> "directory_copy";
                case MOVE -> "directory_move";
                case PENDING -> "directory_upload";
            };
            String destination = PendingDecisionSearchSchema.destinationLabel(path);
            if (!matches.test(new Candidate(name, destination, source, true, review.createdAt(), null,
                    () -> PendingDecisionStatus.from(review)))) continue;
            items.add(new PendingFileDecisionItemResponse("merge-" + review.id(),
                    name, null,
                    switch (review.operation()) { case COPY -> "Directory copy"; case MOVE -> "Directory move"; case PENDING -> "Directory upload"; },
                    destination, "-", CREATED_AT_FORMATTER.format(review.createdAt()), review.createdAt().toString(),
                    true, review.id(), mergeStatus(review)));
        }
        return new PendingFileDecisionListResponse(List.copyOf(items));
    }

    private static String mergeStatus(DirectoryTransferQueryService.Summary review) {
        return review.statusLabel();
    }

    private final class ReviewState {
        private final PendingFileDecision decision;
        private final java.util.Map<String, DirectoryTransferQueryService.Summary> reviews;
        private boolean loaded;
        private String owner;
        private DirectoryTransferQueryService.Summary review;

        private ReviewState(PendingFileDecision decision, java.util.Map<String, DirectoryTransferQueryService.Summary> reviews) {
            this.decision = decision;
            this.reviews = reviews;
        }

        private void load() throws IOException {
            if (!loaded) {
                owner = decision.directory() ? pendingFileDecisionService.directoryMergeOwner(decision.id()).orElse(null) : null;
                review = owner == null ? null : reviews.get(owner);
                loaded = true;
            }
        }

        private PendingDecisionStatus status() {
            try {
                load();
                return PendingDecisionStatus.from(owner, review);
            } catch (IOException ex) {
                throw new UncheckedIOException(ex);
            }
        }
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
        String itemLabel = decision.directory() ? "Pending directory" : "Pending file";
        String message = result.discarded()
                ? itemLabel + " discarded."
                : itemLabel + " saved as " + committedFile.name() + ".";
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
            String createdAt,
            boolean directory,
            String mergeId,
            String statusLabel
    ) {
        static PendingFileDecisionItemResponse from(PendingFileDecision decision, String mergeId, String statusLabel) {
            return new PendingFileDecisionItemResponse(
                    decision.id(),
                    decision.originalFilename(),
                    decision.submittedBy(),
                    decision.source().label(),
                    PendingDecisionSearchSchema.destinationLabel(decision.destinationPath()),
                    ByteSizeFormatter.humanSize(decision.size()),
                    CREATED_AT_FORMATTER.format(decision.createdAt()),
                    decision.createdAt().toString(),
                    decision.directory(),
                    mergeId,
                    statusLabel
            );
        }
    }
}
