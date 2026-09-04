package io.github.fourilla.endervault.pending;

import io.github.fourilla.endervault.storage.FileItem;

public interface PendingFileDecisionResolutionObserver {

    boolean supports(PendingFileDecision decision);

    void afterResolved(
            PendingFileDecision decision,
            PendingFileDecisionAction action,
            boolean discarded,
            FileItem committedFile
    ) throws Exception;
}
