package io.github.fourilla.endervault.pending;

import io.github.fourilla.endervault.directorytransfer.DirectoryTransferQueryService.Summary;
import io.github.fourilla.endervault.directorytransfer.DirectoryTransferRun.Phase;

/** Query categories only; these never authorize a transfer or change its persisted state. */
public enum PendingDecisionStatus {
    AWAITING_DECISION, PREPARING_REVIEW, AWAITING_REVIEW, READY, PAUSED, RECOVERY_REQUIRED,
    PUBLISHING, FINALIZING, OWNER_COMPLETING, NEEDS_REVIEW, ABANDONING;

    public static PendingDecisionStatus from(String owner, Summary review) {
        if (owner == null) return AWAITING_DECISION;
        if (review == null) return PREPARING_REVIEW;
        return from(review);
    }

    public static PendingDecisionStatus from(Summary review) {
        var run = review.run();
        if (run == null) return review.editable() ? AWAITING_REVIEW : READY;
        if (run.phase() == Phase.COMPLETE || run.phase() == Phase.ABANDONED) return null;
        if (run.recoveryRequired() && !run.terminal()) return RECOVERY_REQUIRED;
        if (run.paused()) return PAUSED;
        return switch (run.phase()) {
            case PUBLISHING -> PUBLISHING;
            case FINALIZING -> FINALIZING;
            case OWNER_COMPLETING -> OWNER_COMPLETING;
            case NEEDS_REVIEW -> NEEDS_REVIEW;
            case ABANDONING -> ABANDONING;
            case COMPLETE, ABANDONED -> null;
        };
    }
}
