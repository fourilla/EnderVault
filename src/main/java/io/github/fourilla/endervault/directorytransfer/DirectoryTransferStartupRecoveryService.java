package io.github.fourilla.endervault.directorytransfer;

import java.io.IOException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;

@Service
public class DirectoryTransferStartupRecoveryService {
    private static final Logger logger = LoggerFactory.getLogger(DirectoryTransferStartupRecoveryService.class);
    private final DirectoryTransferReviewStore reviews;
    private final DirectoryTransferService transfers;
    private final DirectoryTransferPendingExecutionService pending;

    public DirectoryTransferStartupRecoveryService(DirectoryTransferReviewStore reviews, DirectoryTransferService transfers,
            DirectoryTransferPendingExecutionService pending) {
        this.reviews = reviews;
        this.transfers = transfers;
        this.pending = pending;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void onStartup() {
        try {
            var summary = recover();
            if (summary.recovered() + summary.review() + summary.deferred() > 0) {
                logger.info("Directory merge recovery: {} recovered, {} review, {} deferred.",
                        summary.recovered(), summary.review(), summary.deferred());
            }
        } catch (IOException | RuntimeException ex) {
            logger.warn("Could not enumerate directory merge runs: {}.", ex.getClass().getSimpleName());
        }
    }

    public synchronized Summary recover() throws IOException {
        int recovered = 0, review = 0, deferred = 0;
        for (String id : reviews.runIds()) {
            try {
                var before = reviews.run(id);
                if (before != null && before.phase() == DirectoryTransferRun.Phase.ABANDONED
                        && reviews.require(id).plan().operation() == DirectoryTransferPlan.Operation.PENDING) {
                    pending.abandonUnstarted(id, before.revision());
                    continue;
                }
                if (before == null || before.paused() || before.terminal()) continue;
                var after = reviews.require(id).plan().operation() == DirectoryTransferPlan.Operation.PENDING
                        ? pending.recover(id) : transfers.recover(id);
                if (after.phase() == DirectoryTransferRun.Phase.COMPLETE) recovered++;
                else if (after.phase() == DirectoryTransferRun.Phase.NEEDS_REVIEW) review++;
            } catch (IOException | RuntimeException ex) {
                deferred++;
                logger.warn("Deferred directory merge {} after {}.", id, ex.getClass().getSimpleName());
            }
        }
        return new Summary(recovered, review, deferred);
    }

    public record Summary(int recovered, int review, int deferred) {}
}
