package io.github.fourilla.endervault.directorytransfer;

import io.github.fourilla.endervault.pending.PendingFileDecisionService;
import io.github.fourilla.endervault.storage.StorageProgressListener;
import io.github.fourilla.endervault.task.TaskCanceledException;
import java.io.IOException;
import java.util.Map;
import java.util.concurrent.CancellationException;
import org.springframework.stereotype.Service;

/** Pending keeps its owner until every staged item has a completed outcome. */
@Service
public class DirectoryTransferPendingExecutionService {
    private final PendingFileDecisionService pending;
    private final DirectoryTransferReviewStore reviews;
    private final DirectoryTransferExecution execution;
    private final DirectoryTransferFinalizer finalizer;

    public DirectoryTransferPendingExecutionService(PendingFileDecisionService pending, DirectoryTransferReviewStore reviews,
            DirectoryTransferExecution execution, DirectoryTransferFinalizer finalizer) {
        this.pending = pending;
        this.reviews = reviews;
        this.execution = execution;
        this.finalizer = finalizer;
    }

    /** Initial conflict-free merges need no second approval; never resume an existing execution implicitly. */
    synchronized DirectoryTransferRun executeUncontested(String id, long revision, StorageProgressListener listener) throws IOException {
        var review = reviews.require(id);
        if (reviews.run(id) != null || reviews.frozen(id) || reviews.hasPredecessor(id)
                || review.plan().items().stream().anyMatch(DirectoryTransferPlan.Item::requiresDecision)) return null;
        execute(id, revision, listener);
        return reviews.run(id);
    }

    public synchronized Map<String, DirectoryTransferCompletion> execute(String id, long revision,
            StorageProgressListener listener) throws IOException {
        var review = reviews.require(id);
        if (review.plan().operation() != DirectoryTransferPlan.Operation.PENDING) {
            throw new io.github.fourilla.endervault.common.StorageAccessException("A pending merge is required.");
        }
        var currentRun = reviews.run(id);
        if (currentRun != null && currentRun.phase() == DirectoryTransferRun.Phase.ABANDONING) {
            abandonRemaining(id, revision);
            return Map.of();
        }
        reviews.freeze(id, revision);
        var previous = reviews.run(id);
        if (previous != null && previous.revision() != revision) {
            throw new io.github.fourilla.endervault.common.StorageAccessException("Merge approval changed.");
        }
        if (previous != null && previous.terminal()) {
            return completions(review);
        }
        var run = new DirectoryTransferRun(id, revision, previous == null ? DirectoryTransferRun.Phase.PUBLISHING : previous.phase(), false);
        reviews.saveRun(previous, run);
        try {
            if (run.phase() == DirectoryTransferRun.Phase.PUBLISHING) {
                var decision = pending.requireDirectoryMergeOwner(review.plan().sourceReference(), id);
                execution.publishPending(id, revision, decision, listener);
                if (listener != null) listener.checkCanceled();
                var next = new DirectoryTransferRun(id, revision, DirectoryTransferRun.Phase.FINALIZING, false);
                reviews.saveRun(run, next);
                run = next;
            }
            if (listener != null) listener.onFinalizing();
            if (run.phase() == DirectoryTransferRun.Phase.FINALIZING) {
                var decision = pending.requireDirectoryMergeOwner(review.plan().sourceReference(), id);
                // User cancellation ends at publication. Keep source/target validation and IO errors intact.
                var completion = finalizer.completePending(id, revision, decision, new StorageProgressListener() {
                    @Override public void onItemProcessed() { if (listener != null) listener.onItemProcessed(); }
                    @Override public void onProgress(String phase, long completed, long total, long bytes, long byteTotal) {
                        if (listener != null) listener.onProgress(phase, completed, total, bytes, byteTotal);
                    }
                });
                boolean complete = completion.size() == review.plan().items().size()
                        && completion.values().stream().allMatch(item -> item.phase() == DirectoryTransferCompletion.Phase.COMPLETE);
                var next = new DirectoryTransferRun(id, revision, complete ? DirectoryTransferRun.Phase.OWNER_COMPLETING
                        : DirectoryTransferRun.Phase.NEEDS_REVIEW, false);
                reviews.saveRun(run, next);
                run = next;
                if (!complete) return completion;
            }
            var completion = completions(review);
            if (completion.values().stream().anyMatch(item -> item.phase() != DirectoryTransferCompletion.Phase.COMPLETE)) {
                throw new io.github.fourilla.endervault.common.StorageAccessException("Pending merge owner completion is incomplete.");
            }
            var root = review.plan().items().stream().filter(item -> item.relativePath().isEmpty()).findFirst().orElseThrow();
            var result = reviews.results(review).get(root.id());
            if (result == null || result.status() != DirectoryTransferResult.Status.PUBLISHED
                    && result.status() != DirectoryTransferResult.Status.DISCARD_APPROVED) {
                throw new io.github.fourilla.endervault.common.StorageAccessException("Invalid pending root outcome.");
            }
            boolean discarded = result.status() == DirectoryTransferResult.Status.DISCARD_APPROVED;
            pending.completeDirectoryMerge(review.plan().sourceReference(), id, result.targetPath(), discarded);
            reviews.saveRun(run, new DirectoryTransferRun(id, revision, DirectoryTransferRun.Phase.COMPLETE, false));
            return completion;
        } catch (TaskCanceledException | CancellationException ex) {
            if (run.phase() == DirectoryTransferRun.Phase.PUBLISHING) reviews.pauseRun(run, ex);
            throw ex;
        } catch (IOException ex) {
            if (run.phase() != DirectoryTransferRun.Phase.PUBLISHING
                    || !Thread.currentThread().isInterrupted() && !(ex instanceof java.nio.channels.ClosedByInterruptException)) {
                reviews.recordFailure(run, ex);
                throw ex;
            }
            reviews.pauseRun(run, ex);
            var canceled = new TaskCanceledException();
            canceled.initCause(ex);
            throw canceled;
        }
    }

    /** Durable abandonment precedes releasing the staging owner; retries never release a newer owner. */
    public synchronized DirectoryTransferRun abandonRemaining(String id, long revision) throws IOException {
        var review = reviews.require(id);
        var run = reviews.run(id);
        if (review.plan().operation() != DirectoryTransferPlan.Operation.PENDING || review.revision() != revision
                || run == null || run.revision() != revision || !reviews.frozen(id)
                || reviews.hasPredecessor(id) || reviews.successor(id) != null) {
            throw new io.github.fourilla.endervault.common.StorageAccessException("Only an initial paused upload publication can be abandoned.");
        }
        if (run.phase() != DirectoryTransferRun.Phase.ABANDONED) {
            if (run.phase() != DirectoryTransferRun.Phase.ABANDONING
                    && !(run.paused() && run.phase() == DirectoryTransferRun.Phase.PUBLISHING)) {
                throw new io.github.fourilla.endervault.common.StorageAccessException("Upload staging cleanup has already started or the merge is active.");
            }
            review = reviews.freeze(id, revision);
            var decision = pending.requireDirectoryMergeOwner(review.plan().sourceReference(), id);
            finalizer.validatePendingAbandonment(review, decision);
            if (run.phase() != DirectoryTransferRun.Phase.ABANDONING) {
                var next = new DirectoryTransferRun(id, revision, DirectoryTransferRun.Phase.ABANDONING, false);
                reviews.saveRun(run, next);
                run = next;
            }
            finalizer.completePublishedPending(review, decision);
            var next = new DirectoryTransferRun(id, revision, DirectoryTransferRun.Phase.ABANDONED, false);
            reviews.saveRun(run, next);
            run = next;
        }
        pending.releaseAbandonedDirectoryMergeClaim(review.plan().sourceReference(), id);
        return run;
    }

    public synchronized void abandonUnstarted(String id, long revision) throws IOException {
        var review = reviews.require(id);
        if (review.plan().operation() != DirectoryTransferPlan.Operation.PENDING) {
            throw new io.github.fourilla.endervault.common.StorageAccessException("A pending merge is required.");
        }
        var run = reviews.run(id);
        if (run != null && run.phase() == DirectoryTransferRun.Phase.ABANDONED && reviews.frozen(id)) {
            abandonRemaining(id, revision);
            return;
        }
        if (run == null || run.phase() != DirectoryTransferRun.Phase.ABANDONED) {
            pending.requireDirectoryMergeOwner(review.plan().sourceReference(), id);
        }
        reviews.abandonUnstarted(id, revision);
        pending.releaseAbandonedDirectoryMergeClaim(review.plan().sourceReference(), id);
    }

    synchronized DirectoryTransferRun recover(String id) throws IOException {
        var run = reviews.run(id);
        if (run == null || run.paused() || run.terminal()) return run;
        execute(id, run.revision(), null);
        return reviews.run(id);
    }

    private Map<String, DirectoryTransferCompletion> completions(DirectoryTransferReview review) throws IOException {
        var result = new java.util.HashMap<String, DirectoryTransferCompletion>();
        for (var item : review.plan().items()) {
            var completion = reviews.completion(review.plan().id(), item.id());
            if (completion == null) throw new io.github.fourilla.endervault.common.StorageAccessException("Missing pending merge completion record.");
            result.put(item.id(), completion);
        }
        return Map.copyOf(result);
    }
}
