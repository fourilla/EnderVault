package io.github.fourilla.endervault.directorymerge;

import io.github.fourilla.endervault.pending.PendingFileDecisionService;
import io.github.fourilla.endervault.storage.StorageProgressListener;
import io.github.fourilla.endervault.task.TaskCanceledException;
import java.io.IOException;
import java.util.Map;
import java.util.concurrent.CancellationException;
import org.springframework.stereotype.Service;

/** Pending keeps its owner until every staged item has a completed outcome. */
@Service
public class DirectoryMergePendingExecutionService {
    private final PendingFileDecisionService pending;
    private final DirectoryMergeReviewStore reviews;
    private final DirectoryMergeExecution execution;
    private final DirectoryMergeFinalizer finalizer;

    public DirectoryMergePendingExecutionService(PendingFileDecisionService pending, DirectoryMergeReviewStore reviews,
            DirectoryMergeExecution execution, DirectoryMergeFinalizer finalizer) {
        this.pending = pending;
        this.reviews = reviews;
        this.execution = execution;
        this.finalizer = finalizer;
    }

    public synchronized Map<String, DirectoryMergeCompletion> execute(String id, long revision,
            StorageProgressListener listener) throws IOException {
        var review = reviews.require(id);
        if (review.plan().operation() != DirectoryMergePlan.Operation.PENDING) {
            throw new io.github.fourilla.endervault.common.StorageAccessException("A pending merge is required.");
        }
        reviews.freeze(id, revision);
        var previous = reviews.run(id);
        if (previous != null && previous.revision() != revision) {
            throw new io.github.fourilla.endervault.common.StorageAccessException("Merge approval changed.");
        }
        if (previous != null && previous.terminal()) {
            return completions(review);
        }
        var run = new DirectoryMergeRun(id, revision, previous == null ? DirectoryMergeRun.Phase.PUBLISHING : previous.phase(), false);
        reviews.saveRun(previous, run);
        try {
            if (run.phase() == DirectoryMergeRun.Phase.PUBLISHING) {
                var decision = pending.requireDirectoryMergeOwner(review.plan().sourceReference(), id);
                execution.publishPending(id, revision, decision, listener);
                var next = new DirectoryMergeRun(id, revision, DirectoryMergeRun.Phase.FINALIZING, false);
                reviews.saveRun(run, next);
                run = next;
            }
            if (run.phase() == DirectoryMergeRun.Phase.FINALIZING) {
                var decision = pending.requireDirectoryMergeOwner(review.plan().sourceReference(), id);
                var completion = finalizer.completePending(id, revision, decision, listener);
                boolean complete = completion.size() == review.plan().items().size()
                        && completion.values().stream().allMatch(item -> item.phase() == DirectoryMergeCompletion.Phase.COMPLETE);
                var next = new DirectoryMergeRun(id, revision, complete ? DirectoryMergeRun.Phase.OWNER_COMPLETING
                        : DirectoryMergeRun.Phase.NEEDS_REVIEW, false);
                reviews.saveRun(run, next);
                run = next;
                if (!complete) return completion;
            }
            var completion = completions(review);
            if (completion.values().stream().anyMatch(item -> item.phase() != DirectoryMergeCompletion.Phase.COMPLETE)) {
                throw new io.github.fourilla.endervault.common.StorageAccessException("Pending merge owner completion is incomplete.");
            }
            var root = review.plan().items().stream().filter(item -> item.relativePath().isEmpty()).findFirst().orElseThrow();
            var result = reviews.results(review).get(root.id());
            if (result == null || result.status() != DirectoryMergeResult.Status.PUBLISHED
                    && result.status() != DirectoryMergeResult.Status.DISCARD_APPROVED) {
                throw new io.github.fourilla.endervault.common.StorageAccessException("Invalid pending root outcome.");
            }
            boolean discarded = result.status() == DirectoryMergeResult.Status.DISCARD_APPROVED;
            pending.completeDirectoryMerge(review.plan().sourceReference(), id, result.targetPath(), discarded);
            reviews.saveRun(run, new DirectoryMergeRun(id, revision, DirectoryMergeRun.Phase.COMPLETE, false));
            return completion;
        } catch (TaskCanceledException | CancellationException ex) {
            try { reviews.saveRun(run, new DirectoryMergeRun(id, revision, run.phase(), true)); }
            catch (IOException | RuntimeException failure) { ex.addSuppressed(failure); }
            throw ex;
        }
    }

    synchronized DirectoryMergeRun recover(String id) throws IOException {
        var run = reviews.run(id);
        if (run == null || run.paused() || run.terminal()) return run;
        execute(id, run.revision(), null);
        return reviews.run(id);
    }

    private Map<String, DirectoryMergeCompletion> completions(DirectoryMergeReview review) throws IOException {
        var result = new java.util.HashMap<String, DirectoryMergeCompletion>();
        for (var item : review.plan().items()) {
            var completion = reviews.completion(review.plan().id(), item.id());
            if (completion == null) throw new io.github.fourilla.endervault.common.StorageAccessException("Missing pending merge completion record.");
            result.put(item.id(), completion);
        }
        return Map.copyOf(result);
    }
}
