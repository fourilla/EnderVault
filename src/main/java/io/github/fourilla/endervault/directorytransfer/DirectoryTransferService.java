package io.github.fourilla.endervault.directorytransfer;

import io.github.fourilla.endervault.common.StorageAccessException;
import io.github.fourilla.endervault.storage.StorageProgressListener;
import io.github.fourilla.endervault.task.TaskCanceledException;
import java.io.IOException;
import java.util.concurrent.CancellationException;
import org.springframework.stereotype.Service;

@Service
public class DirectoryTransferService {
    private final DirectoryTransferReviewStore reviews;
    private final DirectoryTransferExecution execution;
    private final DirectoryTransferFinalizer finalizer;

    public DirectoryTransferService(DirectoryTransferReviewStore reviews, DirectoryTransferExecution execution,
            DirectoryTransferFinalizer finalizer) {
        this.reviews = reviews;
        this.execution = execution;
        this.finalizer = finalizer;
    }

    public synchronized DirectoryTransferRun execute(String id, long revision, StorageProgressListener listener) throws IOException {
        var review = reviews.require(id);
        if (review.plan().operation() == DirectoryTransferPlan.Operation.PENDING) {
            throw new StorageAccessException("Pending completion requires its upload owner.");
        }
        reviews.freeze(id, revision);
        var run = reviews.run(id);
        if (run != null && run.revision() != revision) throw new StorageAccessException("Merge run approval changed.");
        if (run != null && run.terminal()) return run;
        run = update(run, id, revision, run == null ? DirectoryTransferRun.Phase.PUBLISHING : run.phase(), false);
        try {
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
        } catch (IOException ex) {
            if (!Thread.currentThread().isInterrupted() && !(ex instanceof java.nio.channels.ClosedByInterruptException)) throw ex;
            reviews.pauseRun(run, ex);
            var canceled = new TaskCanceledException();
            canceled.initCause(ex);
            throw canceled;
        }
    }

    /** Never unpause a canceled run merely because the server restarted. */
    synchronized DirectoryTransferRun recover(String id) throws IOException {
        var run = reviews.run(id);
        if (run == null || run.paused() || run.terminal()) return run;
        return execute(id, run.revision(), null);
    }

    private DirectoryTransferRun update(DirectoryTransferRun previous, String id, long revision,
            DirectoryTransferRun.Phase phase, boolean paused) throws IOException {
        var next = new DirectoryTransferRun(id, revision, phase, paused);
        reviews.saveRun(previous, next);
        return next;
    }
}
