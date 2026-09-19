package io.github.fourilla.endervault.directorytransfer;

import io.github.fourilla.endervault.common.StorageAccessException;
import io.github.fourilla.endervault.storage.StorageProgressListener;
import io.github.fourilla.endervault.task.AppTask;
import io.github.fourilla.endervault.task.TaskContext;
import io.github.fourilla.endervault.task.TaskCanceledException;
import io.github.fourilla.endervault.task.TaskManagerService;
import io.github.fourilla.endervault.task.TaskOutcome;
import io.github.fourilla.endervault.task.TaskType;
import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CancellationException;
import org.springframework.stereotype.Service;

/** Task history is transient; reviews and runs remain the recovery authority. */
@Service
public class DirectoryTransferTaskService {
    private final TaskManagerService tasks;
    private final DirectoryTransferReviewStore reviews;
    private final DirectoryTransferService transfers;
    private final DirectoryTransferPendingExecutionService pending;
    private final DirectoryTransferReplanningService transferReplanning;
    private final DirectoryTransferPendingReplanningService pendingReplanning;
    private final Map<String, Submission> submissions = new HashMap<>();

    public DirectoryTransferTaskService(TaskManagerService tasks, DirectoryTransferReviewStore reviews,
            DirectoryTransferService transfers, DirectoryTransferPendingExecutionService pending,
            DirectoryTransferReplanningService transferReplanning,
            DirectoryTransferPendingReplanningService pendingReplanning) {
        this.tasks = tasks;
        this.reviews = reviews;
        this.transfers = transfers;
        this.pending = pending;
        this.transferReplanning = transferReplanning;
        this.pendingReplanning = pendingReplanning;
    }

    public synchronized AppTask execute(String id, long revision, String actor, String ip) throws IOException {
        return submit(id, revision, false, actor, ip);
    }

    public synchronized AppTask replan(String id, long revision, String actor, String ip) throws IOException {
        return submit(id, revision, true, actor, ip);
    }

    private AppTask submit(String id, long revision, boolean replan, String actor, String ip) throws IOException {
        submissions.values().removeIf(Submission::finished);
        Submission existing = submissions.get(id);
        if (existing != null) {
            if (existing.revision != revision || existing.replan != replan) {
                throw new StorageAccessException("Another task is already processing this directory merge.");
            }
            return existing.task;
        }
        var review = reviews.require(id);
        if (review.revision() != revision) throw new StorageAccessException("Directory merge decisions changed. Reload the review.");
        var run = reviews.run(id);
        if (replan) {
            if (run == null || run.phase() != DirectoryTransferRun.Phase.NEEDS_REVIEW) {
                throw new StorageAccessException("Only a directory merge requiring review can be replanned.");
            }
        } else if (!review.fullyReviewed()) {
            throw new StorageAccessException("Directory merge review is incomplete.");
        }
        Submission submission = new Submission(revision, replan);
        submission.task = tasks.submit(TaskType.DIRECTORY_MERGE,
                replan ? "Review changed directory merge" : "Merge directory",
                review.plan().destinationPath(), actor, ip, context -> {
                    submission.started = true;
                    try {
                        return run(id, revision, replan, review.plan().operation(), context);
                    } catch (CancellationException ex) {
                        throw new TaskCanceledException();
                    } finally {
                        submission.done = true;
                    }
                });
        submissions.put(id, submission);
        return submission.task;
    }

    private TaskOutcome run(String id, long revision, boolean replan, DirectoryTransferPlan.Operation operation,
            TaskContext context) throws IOException {
        StorageProgressListener listener = new StorageProgressListener() {
            @Override public void checkCanceled() { context.checkCanceled(); }
        };
        // Publication and cleanup count the same entries; do not report these as one percentage.
        context.message(replan ? "Scanning remaining directory items." : "Applying reviewed directory merge.");
        boolean isPending = operation == DirectoryTransferPlan.Operation.PENDING;
        context.resultReference(id);
        context.directoryTransferReview(id);
        if (replan) {
            var next = isPending ? pendingReplanning.replan(id, revision, listener)
                    : transferReplanning.replan(id, revision, listener);
            context.targetPath(next.plan().destinationPath());
            context.resultReference(next.plan().id());
            return TaskOutcome.pending("Directory merge review is ready. Review the remaining items before continuing.");
        }
        DirectoryTransferRun result;
        if (isPending) {
            pending.execute(id, revision, listener);
            result = reviews.run(id);
        } else {
            result = transfers.execute(id, revision, listener);
        }
        if (result == null || !result.terminal()) throw new IOException("Directory merge did not reach a final outcome.");
        return result.phase() == DirectoryTransferRun.Phase.COMPLETE
                ? TaskOutcome.complete("Directory merge complete.")
                : TaskOutcome.pending("Directory items changed. A new review is required.");
    }

    private static final class Submission {
        private final long revision;
        private final boolean replan;
        private AppTask task;
        private volatile boolean started;
        private volatile boolean done;

        private Submission(long revision, boolean replan) {
            this.revision = revision;
            this.replan = replan;
        }

        private boolean finished() {
            // Queued cancellation never enters TaskWork; running cancellation must await its finally block.
            return done || (!started && !task.active());
        }
    }
}
