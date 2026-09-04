package io.github.fourilla.endervault.filetool.archive;

import io.github.fourilla.endervault.pending.PendingFileDecision;
import io.github.fourilla.endervault.pending.PendingFileDecisionAction;
import io.github.fourilla.endervault.pending.PendingFileDecisionResolutionObserver;
import io.github.fourilla.endervault.pending.PendingFileDecisionSource;
import io.github.fourilla.endervault.storage.FileItem;
import io.github.fourilla.endervault.task.TaskManagerService;
import org.springframework.stereotype.Component;

@Component
public class ArchiveOutputPendingDecisionObserver implements PendingFileDecisionResolutionObserver {

    private final TaskManagerService taskManagerService;

    public ArchiveOutputPendingDecisionObserver(TaskManagerService taskManagerService) {
        this.taskManagerService = taskManagerService;
    }

    @Override
    public boolean supports(PendingFileDecision decision) {
        return decision.source() == PendingFileDecisionSource.ARCHIVE_OUTPUT
                && decision.sourceReference() != null;
    }

    @Override
    public void afterResolved(
            PendingFileDecision decision,
            PendingFileDecisionAction action,
            boolean discarded,
            FileItem committedFile
    ) {
        taskManagerService.resolvePending(
                decision.sourceReference(),
                discarded,
                committedFile == null ? null : committedFile.path()
        );
    }
}
