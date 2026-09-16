package io.github.fourilla.endervault.directorymerge;

import io.github.fourilla.endervault.common.StorageAccessException;
import io.github.fourilla.endervault.storage.StorageProgressListener;
import io.github.fourilla.endervault.task.TaskCanceledException;
import java.io.IOException;
import java.util.concurrent.CancellationException;
import org.springframework.stereotype.Service;

@Service
public class DirectoryMergeTransferService {
    private final DirectoryMergeReviewStore reviews;
    private final DirectoryMergeExecution execution;
    private final DirectoryMergeFinalizer finalizer;

    public DirectoryMergeTransferService(DirectoryMergeReviewStore reviews, DirectoryMergeExecution execution,
            DirectoryMergeFinalizer finalizer) {
        this.reviews = reviews;
        this.execution = execution;
        this.finalizer = finalizer;
    }

    public synchronized DirectoryMergeRun execute(String id, long revision, StorageProgressListener listener) throws IOException {
        var review = reviews.require(id);
        if (review.plan().operation() == DirectoryMergePlan.Operation.PENDING) {
            throw new StorageAccessException("Pending completion requires its upload owner.");
        }
        reviews.freeze(id, revision);
        var run = reviews.run(id);
        if (run != null && run.revision() != revision) throw new StorageAccessException("Merge run approval changed.");
        if (run != null && run.terminal()) return run;
        run = update(run, id, revision, run == null ? DirectoryMergeRun.Phase.PUBLISHING : run.phase(), false);
        try {
            if (run.phase() == DirectoryMergeRun.Phase.PUBLISHING) {
                execution.publishTransfer(id, revision, listener);
                run = update(run, id, revision, DirectoryMergeRun.Phase.FINALIZING, false);
            }
            var completion = finalizer.completeTransfer(id, revision, listener);
            boolean needsReview = completion.values().stream()
                    .anyMatch(item -> item.phase() == DirectoryMergeCompletion.Phase.NEEDS_REVIEW);
            return update(run, id, revision, needsReview ? DirectoryMergeRun.Phase.NEEDS_REVIEW
                    : DirectoryMergeRun.Phase.COMPLETE, false);
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
    synchronized DirectoryMergeRun recover(String id) throws IOException {
        var run = reviews.run(id);
        if (run == null || run.paused() || run.terminal()) return run;
        return execute(id, run.revision(), null);
    }

    private DirectoryMergeRun update(DirectoryMergeRun previous, String id, long revision,
            DirectoryMergeRun.Phase phase, boolean paused) throws IOException {
        var next = new DirectoryMergeRun(id, revision, phase, paused);
        reviews.saveRun(previous, next);
        return next;
    }
}
