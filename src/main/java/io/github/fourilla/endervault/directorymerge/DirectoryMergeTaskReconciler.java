package io.github.fourilla.endervault.directorymerge;

import io.github.fourilla.endervault.task.TaskManagerService;
import io.github.fourilla.endervault.task.TaskStatus;
import java.io.IOException;
import java.util.HashSet;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Reconcile transient task history from durable outcomes; never execute or delete file operations. */
@Component
public class DirectoryMergeTaskReconciler {
    private static final Logger log = LoggerFactory.getLogger(DirectoryMergeTaskReconciler.class);
    private final TaskManagerService tasks;
    private final DirectoryMergeReviewStore reviews;

    public DirectoryMergeTaskReconciler(TaskManagerService tasks, DirectoryMergeReviewStore reviews) {
        this.tasks = tasks;
        this.reviews = reviews;
    }

    @Scheduled(fixedDelay = 5_000L)
    public void reconcile() {
        for (var task : tasks.listTasks()) {
            if (task.status() != TaskStatus.PENDING) continue;
            var dependencies = task.directoryMergeReviews();
            if (dependencies.isEmpty()) continue;
            try {
                boolean complete = true;
                synchronized (reviews) {
                    for (String id : dependencies) {
                        if (!completedSuccessor(id)) { complete = false; break; }
                    }
                }
                if (complete) tasks.completeDirectoryMergeReviews(task.id(), dependencies);
            } catch (IOException | RuntimeException ex) {
                // Inconclusive records must not turn a pending operation into a success.
                log.debug("Could not reconcile directory merge task {}: {}", task.id(), ex.getClass().getSimpleName());
            }
        }
    }

    private boolean completedSuccessor(String id) throws IOException {
        var visited = new HashSet<String>();
        while (visited.size() < 256 && visited.add(id)) {
            reviews.require(id);
            String next = reviews.successor(id);
            if (next != null) { id = next; continue; }
            var run = reviews.run(id);
            return run != null && run.phase() == DirectoryMergeRun.Phase.COMPLETE;
        }
        return false;
    }
}
