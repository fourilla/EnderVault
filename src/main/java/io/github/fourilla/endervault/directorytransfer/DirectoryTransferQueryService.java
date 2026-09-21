package io.github.fourilla.endervault.directorytransfer;

import java.io.IOException;
import java.time.Instant;
import java.util.List;
import org.springframework.stereotype.Service;

/** Deliberately excludes filesystem identity, staging paths, and journal internals from browser responses. */
@Service
public class DirectoryTransferQueryService {
    private final DirectoryTransferReviewStore reviews;

    public DirectoryTransferQueryService(DirectoryTransferReviewStore reviews) { this.reviews = reviews; }

    public List<Summary> unresolved() throws IOException {
        synchronized (reviews) {
            var result = new java.util.ArrayList<Summary>();
            for (String id : reviews.reviewIds()) {
                var run = reviews.run(id);
                if (run != null && (run.phase() == DirectoryTransferRun.Phase.COMPLETE
                        || run.phase() == DirectoryTransferRun.Phase.ABANDONED) || reviews.successor(id) != null) continue;
                result.add(summary(reviews.require(id)));
            }
            return List.copyOf(result);
        }
    }

    public Page<Summary> list(int page, int size) throws IOException {
        checkPage(page, size);
        synchronized (reviews) {
            var ids = reviews.reviewIds();
            var selected = ids.stream().skip((long) page * size).limit(size).toList();
            var values = new java.util.ArrayList<Summary>();
            for (String id : selected) values.add(summary(reviews.require(id)));
            return new Page<>(page, size, ids.size(), List.copyOf(values));
        }
    }

    public Detail get(String id, int page, int size, boolean conflictsOnly) throws IOException {
        return get(id, page, size, conflictsOnly, false);
    }

    public Detail get(String id, int page, int size, boolean conflictsOnly, boolean remainingOnly) throws IOException {
        checkPage(page, size);
        synchronized (reviews) {
            var review = reviews.require(id);
            var index = new DirectoryTransferIndex(review.plan());
            var summary = summary(review);
            boolean executionView = remainingOnly && !summary.editable();
            var results = executionView ? reviews.results(review) : java.util.Map.<String, DirectoryTransferResult>of();
            var states = new java.util.HashMap<String, EntryStage>();
            if (executionView) {
                for (var item : review.plan().items()) {
                    states.put(item.id(), stage(results.get(item.id()), reviews.completion(id, item.id()), review.choices().get(item.id())));
                }
            }
            var items = review.plan().items().stream()
                    .filter(item -> executionView ? states.get(item.id()) != EntryStage.COMPLETE && states.get(item.id()) != EntryStage.RETAINED
                            : !conflictsOnly || item.requiresDecision()).toList();
            var rows = items.stream().skip((long) page * size).limit(size)
                    .map(item -> new Entry(item.id(), item.relativePath(), index.displayTargetPath(item, results), item.source().kind(), item.source().size(),
                            item.source().modifiedAt(), item.target() == null ? null : item.target().kind(),
                            item.target() == null ? null : item.target().size(),
                            item.target() == null ? null : item.target().modifiedAt(), item.conflict(), item.blockedBy(),
                            review.choices().get(item.id()), states.get(item.id()))).toList();
            return new Detail(summary, new Page<>(page, size, items.size(), rows), executionView);
        }
    }

    private static EntryStage stage(DirectoryTransferResult result, DirectoryTransferCompletion completion, DirectoryTransferReview.Choice choice) {
        if (completion != null) return switch (completion.phase()) {
            case COMPLETE -> EntryStage.COMPLETE;
            case RETAINED -> EntryStage.RETAINED;
            case NEEDS_REVIEW -> EntryStage.NEEDS_REVIEW;
            default -> EntryStage.FINALIZATION_PENDING;
        };
        if (result != null) return result.status() == DirectoryTransferResult.Status.NEEDS_REVIEW
                ? EntryStage.NEEDS_REVIEW : EntryStage.FINALIZATION_PENDING;
        return choice == DirectoryTransferReview.Choice.SKIP || choice == DirectoryTransferReview.Choice.DISCARD_UPLOAD
                ? EntryStage.FINALIZATION_PENDING : EntryStage.PUBLICATION_PENDING;
    }

    private Summary summary(DirectoryTransferReview review) throws IOException {
        var plan = review.plan();
        var run = reviews.run(plan.id());
        boolean editable = !reviews.frozen(plan.id()) && (run == null || run.phase() != DirectoryTransferRun.Phase.ABANDONED);
        String successor = reviews.successor(plan.id());
        boolean canAbandon = editable && run == null && successor == null
                && (plan.operation() != DirectoryTransferPlan.Operation.PENDING || !reviews.hasPredecessor(plan.id()));
        return new Summary(plan.id(), plan.operation(), plan.sourceReference(), plan.destinationPath(), plan.createdAt(),
                review.revision(), plan.items().size(),
                plan.items().stream().filter(DirectoryTransferPlan.Item::requiresDecision).count(),
                review.fullyReviewed(), editable, run, successor, canAbandon);
    }

    private static void checkPage(int page, int size) {
        if (page < 0 || size < 1 || size > 200) throw new IllegalArgumentException("Invalid review page or page size (1-200).");
    }

    public record Page<T>(int page, int size, long total, List<T> items) {}
    public record Summary(String id, DirectoryTransferPlan.Operation operation, String sourceReference,
            String destinationPath, Instant createdAt, long revision, int itemCount, long conflictCount,
            boolean fullyReviewed, boolean editable, DirectoryTransferRun run, String successorId, boolean canAbandon) {
        @com.fasterxml.jackson.annotation.JsonProperty public String title() { return DirectoryTransferPresentation.title(operation); }
        @com.fasterxml.jackson.annotation.JsonProperty public String statusLabel() { return DirectoryTransferPresentation.status(operation, run, editable); }
        @com.fasterxml.jackson.annotation.JsonProperty public boolean canAbandonRemainingCopy() {
            return operation == DirectoryTransferPlan.Operation.COPY && run != null && successorId == null
                    && (run.phase() == DirectoryTransferRun.Phase.ABANDONING
                        || run.paused() && (run.phase() == DirectoryTransferRun.Phase.PUBLISHING
                            || run.phase() == DirectoryTransferRun.Phase.FINALIZING));
        }
    }
    public enum EntryStage { PUBLICATION_PENDING, FINALIZATION_PENDING, NEEDS_REVIEW, COMPLETE, RETAINED }
    public record Entry(String id, String relativePath, String plannedTargetPath, DirectoryTransferPlan.Kind sourceKind, long sourceSize,
            Instant sourceModifiedAt, DirectoryTransferPlan.Kind targetKind, Long targetSize, Instant targetModifiedAt,
            DirectoryTransferPlan.Conflict conflict, String blockedBy, DirectoryTransferReview.Choice choice, EntryStage stage) {}
    public record Detail(Summary review, Page<Entry> entries, boolean executionView) {}
}
