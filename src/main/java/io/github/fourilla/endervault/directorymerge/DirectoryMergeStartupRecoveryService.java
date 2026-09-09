package io.github.fourilla.endervault.directorymerge;

import java.io.IOException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;

@Service
public class DirectoryMergeStartupRecoveryService {
    private static final Logger logger = LoggerFactory.getLogger(DirectoryMergeStartupRecoveryService.class);
    private final DirectoryMergeReviewStore reviews;
    private final DirectoryMergeTransferService transfers;

    public DirectoryMergeStartupRecoveryService(DirectoryMergeReviewStore reviews, DirectoryMergeTransferService transfers) {
        this.reviews = reviews;
        this.transfers = transfers;
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
                if (before == null || before.paused() || before.terminal()) continue;
                var after = transfers.recover(id);
                if (after.phase() == DirectoryMergeRun.Phase.COMPLETE) recovered++;
                else if (after.phase() == DirectoryMergeRun.Phase.NEEDS_REVIEW) review++;
            } catch (IOException | RuntimeException ex) {
                deferred++;
                logger.warn("Deferred directory merge {} after {}.", id, ex.getClass().getSimpleName());
            }
        }
        return new Summary(recovered, review, deferred);
    }

    public record Summary(int recovered, int review, int deferred) {}
}
