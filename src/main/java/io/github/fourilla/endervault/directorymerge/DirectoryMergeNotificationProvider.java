package io.github.fourilla.endervault.directorymerge;

import io.github.fourilla.endervault.notificationcenter.ActionRequiredItem;
import io.github.fourilla.endervault.notificationcenter.ActionRequiredProvider;
import io.github.fourilla.endervault.task.TaskManagerService;
import io.github.fourilla.endervault.task.TaskType;
import java.io.IOException;
import java.util.List;
import java.util.stream.Collectors;
import org.springframework.stereotype.Component;

@Component
public class DirectoryMergeNotificationProvider implements ActionRequiredProvider {
    private final DirectoryMergeQueryService query;
    private final TaskManagerService tasks;
    private final io.github.fourilla.endervault.pending.PendingFileDecisionService pending;

    public DirectoryMergeNotificationProvider(DirectoryMergeQueryService query, TaskManagerService tasks,
            io.github.fourilla.endervault.pending.PendingFileDecisionService pending) {
        this.query = query;
        this.tasks = tasks;
        this.pending = pending;
    }

    @Override public List<ActionRequiredItem> items() throws IOException {
        var running = java.util.stream.Stream.of(TaskType.DIRECTORY_MERGE, TaskType.FILE_COPY, TaskType.FILE_MOVE)
                .flatMap(type -> tasks.activeTasks(type).stream())
                .map(task -> task.resultReference()).collect(Collectors.toSet());
        var pendingIds = pending.list().stream().map(item -> item.id()).collect(Collectors.toSet());
        return query.unresolved().stream()
                // A live uploaded directory already has its own Pending notification.
                .filter(review -> review.operation() != DirectoryMergePlan.Operation.PENDING
                        || !pendingIds.contains(review.sourceReference()) && review.run() != null
                        && review.run().phase() == DirectoryMergeRun.Phase.OWNER_COMPLETING)
                .filter(review -> !running.contains(review.id()))
                .map(review -> new ActionRequiredItem("directory-merge-" + review.id(), "DIRECTORY_MERGE",
                        "Directory merge", review.destinationPath(), review.createdAt(),
                        reviewAllHref() + "#merge-" + review.id(),
                        new ActionRequiredItem.Target(ActionRequiredItem.TargetKind.DIRECTORY_MERGE, review.id()))).toList();
    }

    @Override public String reviewAllHref() { return "/admin/pending-decisions"; }
}
