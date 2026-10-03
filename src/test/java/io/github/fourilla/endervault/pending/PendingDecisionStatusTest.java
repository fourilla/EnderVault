package io.github.fourilla.endervault.pending;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.fourilla.endervault.directorytransfer.DirectoryTransferPlan.Operation;
import io.github.fourilla.endervault.directorytransfer.DirectoryTransferQueryService.Summary;
import io.github.fourilla.endervault.directorytransfer.DirectoryTransferRun;
import io.github.fourilla.endervault.directorytransfer.DirectoryTransferRun.Phase;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

class PendingDecisionStatusTest {
    private static final String ID = "00000000-0000-4000-8000-000000000001";

    @Test
    void ordinaryDecisionsAndMissingOwnerSnapshotsRemainDistinct() {
        assertThat(PendingDecisionStatus.from(null, null)).isEqualTo(PendingDecisionStatus.AWAITING_DECISION);
        assertThat(PendingDecisionStatus.from(ID, null)).isEqualTo(PendingDecisionStatus.PREPARING_REVIEW);
    }

    @Test
    void anUnstartedReviewDistinguishesEditableFromApproved() {
        assertThat(PendingDecisionStatus.from(review(Operation.COPY, null, true)))
                .isEqualTo(PendingDecisionStatus.AWAITING_REVIEW);
        assertThat(PendingDecisionStatus.from(review(Operation.COPY, null, false)))
                .isEqualTo(PendingDecisionStatus.READY);
    }

    @ParameterizedTest
    @EnumSource(Phase.class)
    void rawPhasesNeverDependOnEnglishLabels(Phase phase) {
        var expected = switch (phase) {
            case PUBLISHING -> PendingDecisionStatus.PUBLISHING;
            case FINALIZING -> PendingDecisionStatus.FINALIZING;
            case OWNER_COMPLETING -> PendingDecisionStatus.OWNER_COMPLETING;
            case NEEDS_REVIEW -> PendingDecisionStatus.NEEDS_REVIEW;
            case ABANDONING -> PendingDecisionStatus.ABANDONING;
            case COMPLETE, ABANDONED -> null;
        };
        assertThat(PendingDecisionStatus.from(review(Operation.MOVE, new DirectoryTransferRun(ID, 1, phase, false), false)))
                .isEqualTo(expected);
    }

    @ParameterizedTest
    @EnumSource(Phase.class)
    void recoveryAndPausePrecedenceKeepTerminalHistoryOutOfPending(Phase phase) {
        var run = new DirectoryTransferRun(ID, 1, phase, true, true);
        var expected = phase == Phase.COMPLETE || phase == Phase.ABANDONED ? null
                : run.terminal() ? PendingDecisionStatus.PAUSED : PendingDecisionStatus.RECOVERY_REQUIRED;
        assertThat(PendingDecisionStatus.from(review(Operation.PENDING, run, false))).isEqualTo(expected);
        if (phase != Phase.COMPLETE && phase != Phase.ABANDONED) {
            assertThat(PendingDecisionStatus.from(review(Operation.PENDING,
                    new DirectoryTransferRun(ID, 1, phase, true), false))).isEqualTo(PendingDecisionStatus.PAUSED);
        }
    }

    @ParameterizedTest
    @EnumSource(Operation.class)
    void sourceOperationAndExecutionStatusAreIndependent(Operation operation) {
        var value = review(operation, new DirectoryTransferRun(ID, 1, Phase.PUBLISHING, false), false);
        assertThat(PendingDecisionStatus.from(value)).isEqualTo(PendingDecisionStatus.PUBLISHING);
    }

    private Summary review(Operation operation, DirectoryTransferRun run, boolean editable) {
        return new Summary(ID, operation, "source", "target/photos", Instant.EPOCH,
                1, 3, 1, false, editable, run, null, false, false);
    }
}
