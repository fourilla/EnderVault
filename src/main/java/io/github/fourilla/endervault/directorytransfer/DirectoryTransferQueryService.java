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
                if (run != null && run.phase() == DirectoryTransferRun.Phase.COMPLETE || reviews.successor(id) != null) continue;
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
        checkPage(page, size);
        synchronized (reviews) {
            var review = reviews.require(id);
            var index = new DirectoryTransferIndex(review.plan());
            var items = review.plan().items().stream()
                    .filter(item -> !conflictsOnly || item.requiresDecision()).toList();
            var rows = items.stream().skip((long) page * size).limit(size)
                    .map(item -> new Entry(item.id(), item.relativePath(), index.plannedTargetPath(item), item.source().kind(), item.source().size(),
                            item.source().modifiedAt(), item.target() == null ? null : item.target().kind(),
                            item.target() == null ? null : item.target().size(),
                            item.target() == null ? null : item.target().modifiedAt(), item.conflict(), item.blockedBy(),
                            review.choices().get(item.id()))).toList();
            return new Detail(summary(review), new Page<>(page, size, items.size(), rows));
        }
    }

    private Summary summary(DirectoryTransferReview review) throws IOException {
        var plan = review.plan();
        var run = reviews.run(plan.id());
        return new Summary(plan.id(), plan.operation(), plan.sourceReference(), plan.destinationPath(), plan.createdAt(),
                review.revision(), plan.items().size(),
                plan.items().stream().filter(DirectoryTransferPlan.Item::requiresDecision).count(),
                review.fullyReviewed(), !reviews.frozen(plan.id()), run, reviews.successor(plan.id()));
    }

    private static void checkPage(int page, int size) {
        if (page < 0 || size < 1 || size > 200) throw new IllegalArgumentException("Invalid review page or page size (1-200).");
    }

    public record Page<T>(int page, int size, long total, List<T> items) {}
    public record Summary(String id, DirectoryTransferPlan.Operation operation, String sourceReference,
            String destinationPath, Instant createdAt, long revision, int itemCount, long conflictCount,
            boolean fullyReviewed, boolean editable, DirectoryTransferRun run, String successorId) {}
    public record Entry(String id, String relativePath, String plannedTargetPath, DirectoryTransferPlan.Kind sourceKind, long sourceSize,
            Instant sourceModifiedAt, DirectoryTransferPlan.Kind targetKind, Long targetSize, Instant targetModifiedAt,
            DirectoryTransferPlan.Conflict conflict, String blockedBy, DirectoryTransferReview.Choice choice) {}
    public record Detail(Summary review, Page<Entry> entries) {}
}
