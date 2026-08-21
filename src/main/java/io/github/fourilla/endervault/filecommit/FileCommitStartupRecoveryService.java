package io.github.fourilla.endervault.filecommit;

import io.github.fourilla.endervault.filecommit.FileCommitCoordinator.SingleFileCommitPlan;
import io.github.fourilla.endervault.pending.PendingFileDecision;
import io.github.fourilla.endervault.pending.PendingFileDecisionService;
import io.github.fourilla.endervault.pending.PendingFileDecisionSource;
import java.io.IOException;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;

@Service
public class FileCommitStartupRecoveryService {

    private static final Logger logger = LoggerFactory.getLogger(FileCommitStartupRecoveryService.class);

    private final FileCommitCoordinator coordinator;
    private final PendingFileDecisionService pendingFileDecisionService;

    public FileCommitStartupRecoveryService(
            FileCommitCoordinator coordinator,
            PendingFileDecisionService pendingFileDecisionService
    ) {
        this.coordinator = coordinator;
        this.pendingFileDecisionService = pendingFileDecisionService;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void recoverAfterStartup() {
        try {
            RecoverySummary summary = recover();
            if (summary.recovered() > 0 || summary.pending() > 0 || summary.deferred() > 0) {
                logger.info(
                        "File commit startup recovery finished: {} recovered, {} pending, {} deferred.",
                        summary.recovered(),
                        summary.pending(),
                        summary.deferred()
                );
            }
        } catch (IOException ex) {
            logger.warn("Could not enumerate file commit journals during startup recovery: {}.",
                    ex.getClass().getSimpleName());
        }
    }

    public synchronized RecoverySummary recover() throws IOException {
        int recovered = 0;
        int pending = 0;
        int deferred = 0;
        for (FileCommitJournalEntry entry : coordinator.listJournals()) {
            if (!supports(entry.manifest().owner().type())) {
                continue;
            }
            try {
                RecoveryOutcome outcome = recover(entry);
                recovered += outcome == RecoveryOutcome.RECOVERED ? 1 : 0;
                pending += outcome == RecoveryOutcome.PENDING ? 1 : 0;
                deferred += outcome == RecoveryOutcome.DEFERRED ? 1 : 0;
            } catch (IOException | RuntimeException ex) {
                deferred++;
                logger.warn(
                        "Deferred file commit recovery for operation {} after {}.",
                        entry.manifest().operationId(),
                        ex.getClass().getSimpleName()
                );
            }
        }
        return new RecoverySummary(recovered, pending, deferred);
    }

    private RecoveryOutcome recover(FileCommitJournalEntry entry) throws IOException {
        String operationId = entry.manifest().operationId();
        FileCommitOwner owner = entry.manifest().owner();
        PendingFileDecisionSource pendingSource = pendingSource(owner.type());
        Optional<PendingFileDecision> existingPending = pendingFileDecisionService.findBySourceReference(
                pendingSource,
                owner.id()
        );
        if (existingPending.isPresent()) {
            if (entry.state().phase() == FileCommitPhase.ABORTED) {
                coordinator.completeConflict(operationId);
                return RecoveryOutcome.PENDING;
            }
            return RecoveryOutcome.DEFERRED;
        }
        if (entry.state().phase() == FileCommitPhase.COMPLETED) {
            coordinator.complete(operationId);
            return RecoveryOutcome.RECOVERED;
        }
        if (entry.state().phase() == FileCommitPhase.NEEDS_REVIEW) {
            return RecoveryOutcome.DEFERRED;
        }
        if (entry.state().phase() == FileCommitPhase.ABORTED) {
            handoffConflict(coordinator.singleFilePlan(operationId), pendingSource);
            return RecoveryOutcome.PENDING;
        }

        try {
            FileCommitCoordinator.StagedFileCommit commit = coordinator.resumeSingleFile(operationId);
            coordinator.complete(commit.operationId());
            return RecoveryOutcome.RECOVERED;
        } catch (FileCommitConflictException ex) {
            handoffConflict(coordinator.singleFilePlan(ex.operationId()), pendingSource);
            return RecoveryOutcome.PENDING;
        }
    }

    private void handoffConflict(
            SingleFileCommitPlan plan,
            PendingFileDecisionSource pendingSource
    ) throws IOException {
        pendingFileDecisionService.create(
                plan.stagedFile(),
                pendingSource,
                plan.destinationPath(),
                plan.filename(),
                plan.stagingFingerprint().size(),
                plan.owner().id(),
                null
        );
        coordinator.completeConflict(plan.operationId());
    }

    private boolean supports(FileCommitOwnerType ownerType) {
        return ownerType == FileCommitOwnerType.REMOTE_DOWNLOAD
                || ownerType == FileCommitOwnerType.ARCHIVE_CREATE;
    }

    private PendingFileDecisionSource pendingSource(FileCommitOwnerType ownerType) {
        return switch (ownerType) {
            case REMOTE_DOWNLOAD -> PendingFileDecisionSource.REMOTE_DOWNLOAD;
            case ARCHIVE_CREATE -> PendingFileDecisionSource.ARCHIVE_OUTPUT;
            default -> throw new IllegalArgumentException("Unsupported startup recovery owner: " + ownerType);
        };
    }

    private enum RecoveryOutcome {
        RECOVERED,
        PENDING,
        DEFERRED
    }

    public record RecoverySummary(int recovered, int pending, int deferred) {
    }
}
