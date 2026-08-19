package io.github.fourilla.endervault.pending;

public interface PendingFileDecisionResolutionObserver {

    boolean supports(PendingFileDecision decision);

    void afterResolved(
            PendingFileDecision decision,
            PendingFileDecisionAction action,
            boolean discarded
    ) throws Exception;
}
