package io.github.fourilla.endervault.remote;

import io.github.fourilla.endervault.pending.PendingFileDecision;
import io.github.fourilla.endervault.pending.PendingFileDecisionAction;
import io.github.fourilla.endervault.pending.PendingFileDecisionResolutionObserver;
import io.github.fourilla.endervault.pending.PendingFileDecisionSource;
import io.github.fourilla.endervault.storage.FileItem;
import org.springframework.stereotype.Component;

@Component
public class RemoteDownloadPendingDecisionObserver implements PendingFileDecisionResolutionObserver {

    private final RemoteDownloadTaskStore taskStore;

    public RemoteDownloadPendingDecisionObserver(RemoteDownloadTaskStore taskStore) {
        this.taskStore = taskStore;
    }

    @Override
    public boolean supports(PendingFileDecision decision) {
        return decision.source() == PendingFileDecisionSource.REMOTE_DOWNLOAD
                && decision.sourceReference() != null;
    }

    @Override
    public void afterResolved(
            PendingFileDecision decision,
            PendingFileDecisionAction action,
            boolean discarded,
            FileItem committedFile
    ) {
        taskStore.find(decision.sourceReference()).ifPresent(task -> task.resolvePending(
                discarded,
                committedFile == null ? null : committedFile.path(),
                committedFile == null ? null : committedFile.name()
        ));
    }
}
