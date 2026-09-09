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
        var decision = pending.requireDirectoryMergeOwner(review.plan().sourceReference(), id);
        reviews.freeze(id, revision);
        var previous = reviews.run(id);
        if (previous != null && previous.revision() != revision) {
            throw new io.github.fourilla.endervault.common.StorageAccessException("Merge approval changed.");
        }
        // Owner completion is a later boundary; keep FINALIZING even when the tree is fully cleaned.
        if (previous != null && previous.terminal()) {
            throw new io.github.fourilla.endervault.common.StorageAccessException("Pending merge requires review.");
        }
        var run = new DirectoryMergeRun(id, revision, previous == null ? DirectoryMergeRun.Phase.PUBLISHING : previous.phase(), false);
        reviews.saveRun(previous, run);
        try {
            if (run.phase() == DirectoryMergeRun.Phase.PUBLISHING) {
                execution.publishPending(id, revision, decision, listener);
                var next = new DirectoryMergeRun(id, revision, DirectoryMergeRun.Phase.FINALIZING, false);
                reviews.saveRun(run, next);
                run = next;
            }
            pending.requireDirectoryMergeOwner(decision.id(), id);
            return finalizer.completePending(id, revision, decision, listener);
        } catch (TaskCanceledException | CancellationException ex) {
            try { reviews.saveRun(run, new DirectoryMergeRun(id, revision, run.phase(), true)); }
            catch (IOException | RuntimeException failure) { ex.addSuppressed(failure); }
            throw ex;
        }
    }
}
