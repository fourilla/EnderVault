package io.github.fourilla.endervault.directorytransfer;

import io.github.fourilla.endervault.storage.StorageProgressListener;
import io.github.fourilla.endervault.task.AppTask;
import io.github.fourilla.endervault.task.TaskManagerService;
import io.github.fourilla.endervault.task.TaskOutcome;
import io.github.fourilla.endervault.task.TaskType;
import java.io.IOException;
import org.springframework.stereotype.Service;

@Service
public class DirectoryTransferPreparationTaskService {
    private final TaskManagerService tasks;
    private final DirectoryTransferPlanner planner;
    private final DirectoryTransferReviewStore reviews;
    private final DirectoryTransferPendingPreparationService pending;

    public DirectoryTransferPreparationTaskService(TaskManagerService tasks, DirectoryTransferPlanner planner,
            DirectoryTransferReviewStore reviews, DirectoryTransferPendingPreparationService pending) {
        this.tasks = tasks;
        this.planner = planner;
        this.reviews = reviews;
        this.pending = pending;
    }

    public AppTask transfer(DirectoryTransferPlan.Operation operation, String source, String destination,
            String actor, String ip) {
        if (operation != DirectoryTransferPlan.Operation.COPY && operation != DirectoryTransferPlan.Operation.MOVE) {
            throw new IllegalArgumentException("A transfer must be copy or move.");
        }
        return queue(destination, actor, ip, listener -> reviews.create(
                planner.planTransfer(operation, source, destination, listener)));
    }

    public AppTask pending(String pendingId, String actor, String ip) {
        return queue(null, actor, ip, listener -> pending.prepare(pendingId, listener));
    }

    private AppTask queue(String destination, String actor, String ip, Preparation preparation) {
        return tasks.submit(TaskType.DIRECTORY_MERGE, "Prepare directory merge", destination, actor, ip, context -> {
            context.message("Scanning directory items for review.");
            StorageProgressListener listener = new StorageProgressListener() {
                @Override public void checkCanceled() { context.checkCanceled(); }
            };
            context.checkCanceled();
            var review = preparation.prepare(listener);
            // Do not turn a successfully persisted review into a canceled/lost result after this point.
            context.resultReference(review.plan().id());
            context.directoryTransferReview(review.plan().id());
            context.targetPath(review.plan().destinationPath());
            return TaskOutcome.pending("Directory merge review is ready. Review before continuing.");
        });
    }

    @FunctionalInterface
    private interface Preparation {
        DirectoryTransferReview prepare(StorageProgressListener listener) throws IOException;
    }
}
