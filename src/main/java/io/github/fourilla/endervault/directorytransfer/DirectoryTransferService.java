package io.github.fourilla.endervault.directorytransfer;

import io.github.fourilla.endervault.common.StorageAccessException;
import io.github.fourilla.endervault.storage.StorageProgressListener;
import io.github.fourilla.endervault.task.TaskCanceledException;
import java.io.IOException;
import java.util.concurrent.CancellationException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

@Service
public class DirectoryTransferService {
    private final DirectoryTransferReviewStore reviews;
    private final DirectoryTransferExecution execution;
    private final DirectoryTransferFinalizer finalizer;
    private final DirectoryTransferWholeMoveCoordinator wholeMoves;

    public DirectoryTransferService(DirectoryTransferReviewStore reviews, DirectoryTransferExecution execution,
            DirectoryTransferFinalizer finalizer) {
        this(reviews, execution, finalizer, null);
    }

    @Autowired
    public DirectoryTransferService(DirectoryTransferReviewStore reviews, DirectoryTransferExecution execution,
            DirectoryTransferFinalizer finalizer, DirectoryTransferWholeMoveCoordinator wholeMoves) {
        this.reviews = reviews;
        this.execution = execution;
        this.finalizer = finalizer;
        this.wholeMoves = wholeMoves;
    }

    public synchronized DirectoryTransferRun execute(String id, long revision, StorageProgressListener listener) throws IOException {
        var review = reviews.require(id);
        if (review.revision() != revision) throw new StorageAccessException("Merge run approval changed.");
        if (review.plan().operation() == DirectoryTransferPlan.Operation.PENDING) {
            throw new StorageAccessException("Pending completion requires its upload owner.");
        }
        var run = reviews.run(id);
        if (run != null && run.phase() == DirectoryTransferRun.Phase.ABANDONING) return abandonRemainingTransfer(id, revision);
        if (run != null && run.revision() != revision) throw new StorageAccessException("Merge run approval changed.");
        if (run != null && run.terminal()) {
            // Keep the existing abandoned-run rejection and frozen-approval validation on repeated requests.
            reviews.freeze(id, revision);
            return run;
        }
        boolean whole = run == null && wholeMoves != null && wholeMoves.eligible(review, listener);
        review = reviews.freeze(id, revision);
        if (run == null) {
            var first = new DirectoryTransferRun(id, revision, DirectoryTransferRun.Phase.PUBLISHING, false,
                    whole ? DirectoryTransferRun.Strategy.WHOLE_DIRECTORY_MOVE : DirectoryTransferRun.Strategy.ITEMWISE);
            reviews.saveRun(null, first);
            run = first;
        } else run = update(run, id, revision, run.phase(), false);
        try {
            if (run.strategy() == DirectoryTransferRun.Strategy.WHOLE_DIRECTORY_MOVE) {
                if (wholeMoves == null) throw new StorageAccessException("Directory move recovery is unavailable.");
                var move = wholeMoves.execute(review, listener);
                if (move.phase() == DirectoryTransferMoveRecord.Phase.NEEDS_REVIEW) {
                    return update(run, id, revision, DirectoryTransferRun.Phase.NEEDS_REVIEW, false);
                }
                if (move.phase() == DirectoryTransferMoveRecord.Phase.COMPLETE) {
                    if (run.phase() == DirectoryTransferRun.Phase.PUBLISHING) {
                        run = update(run, id, revision, DirectoryTransferRun.Phase.FINALIZING, false);
                    }
                    return update(run, id, revision, DirectoryTransferRun.Phase.COMPLETE, false);
                }
                if (move.phase() != DirectoryTransferMoveRecord.Phase.FALLBACK) {
                    throw new StorageAccessException("Directory move evidence is incomplete.");
                }
            }
            if (run.phase() == DirectoryTransferRun.Phase.PUBLISHING) {
                execution.publishTransfer(id, revision, listener);
                run = update(run, id, revision, DirectoryTransferRun.Phase.FINALIZING, false);
            }
            var completion = finalizer.completeTransfer(id, revision, listener);
            boolean needsReview = completion.values().stream()
                    .anyMatch(item -> item.phase() == DirectoryTransferCompletion.Phase.NEEDS_REVIEW);
            return update(run, id, revision, needsReview ? DirectoryTransferRun.Phase.NEEDS_REVIEW
                    : DirectoryTransferRun.Phase.COMPLETE, false);
        } catch (TaskCanceledException | CancellationException ex) {
            reviews.pauseRun(run, ex);
            throw ex;
        } catch (StorageAccessException ex) {
            if (run.strategy() == DirectoryTransferRun.Strategy.WHOLE_DIRECTORY_MOVE && reviews.move(id) == null) {
                return update(run, id, revision, DirectoryTransferRun.Phase.NEEDS_REVIEW, false);
            }
            throw ex;
        } catch (IOException ex) {
            if (!Thread.currentThread().isInterrupted() && !(ex instanceof java.nio.channels.ClosedByInterruptException)) {
                if (ex instanceof java.nio.file.NoSuchFileException
                        && run.strategy() == DirectoryTransferRun.Strategy.WHOLE_DIRECTORY_MOVE && reviews.move(id) == null) {
                    return update(run, id, revision, DirectoryTransferRun.Phase.NEEDS_REVIEW, false);
                }
                reviews.recordFailure(run, ex);
                throw ex;
            }
            reviews.pauseRun(run, ex);
            var canceled = new TaskCanceledException();
            canceled.initCause(ex);
            throw canceled;
        }
    }

    public synchronized DirectoryTransferRun abandonRemainingTransfer(String id, long revision) throws IOException {
        var review = reviews.require(id);
        var run = reviews.run(id);
        if (review.revision() != revision || review.plan().operation() == DirectoryTransferPlan.Operation.PENDING
                || run == null || run.revision() != revision || !reviews.frozen(id) || reviews.successor(id) != null) {
            throw new StorageAccessException("Only an eligible paused transfer can abandon its remaining work.");
        }
        if (run.strategy() == DirectoryTransferRun.Strategy.WHOLE_DIRECTORY_MOVE) {
            throw new StorageAccessException("A whole-directory move must be resumed or inspected before further work.");
        }
        if (run.phase() == DirectoryTransferRun.Phase.ABANDONED) return run;
        review = reviews.freeze(id, revision);
        if (run.phase() != DirectoryTransferRun.Phase.ABANDONING && (!run.paused()
                || run.phase() != DirectoryTransferRun.Phase.PUBLISHING && run.phase() != DirectoryTransferRun.Phase.FINALIZING)) {
            throw new StorageAccessException("Pause the transfer before abandoning its remaining work.");
        }
        finalizer.validateTransferAbandonment(review);
        if (run.phase() != DirectoryTransferRun.Phase.ABANDONING) {
            run = update(run, id, revision, DirectoryTransferRun.Phase.ABANDONING, false);
        }
        // After this durable intent, recovery may finish bookkeeping but must never publish another item.
        finalizer.completePublishedTransfer(review);
        return update(run, id, revision, DirectoryTransferRun.Phase.ABANDONED, false);
    }

    /** Never unpause a canceled run merely because the server restarted. */
    synchronized DirectoryTransferRun recover(String id) throws IOException {
        var run = reviews.run(id);
        if (run == null || run.paused() || run.terminal()) return run;
        return execute(id, run.revision(), null);
    }

    private DirectoryTransferRun update(DirectoryTransferRun previous, String id, long revision,
            DirectoryTransferRun.Phase phase, boolean paused) throws IOException {
        var next = new DirectoryTransferRun(id, revision, phase, paused, false,
                previous == null ? DirectoryTransferRun.Strategy.ITEMWISE : previous.strategy());
        reviews.saveRun(previous, next);
        return next;
    }
}
