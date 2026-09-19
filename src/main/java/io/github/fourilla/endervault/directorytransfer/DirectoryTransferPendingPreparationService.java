package io.github.fourilla.endervault.directorytransfer;

import io.github.fourilla.endervault.pending.PendingFileDecisionService;
import io.github.fourilla.endervault.storage.StorageProgressListener;
import java.io.IOException;
import java.util.UUID;
import org.springframework.stereotype.Service;

@Service
public class DirectoryTransferPendingPreparationService {
    private final PendingFileDecisionService pending;
    private final DirectoryTransferPlanner planner;
    private final DirectoryTransferReviewStore reviews;

    public DirectoryTransferPendingPreparationService(PendingFileDecisionService pending,
            DirectoryTransferPlanner planner, DirectoryTransferReviewStore reviews) {
        this.pending = pending;
        this.planner = planner;
        this.reviews = reviews;
    }

    public synchronized DirectoryTransferReview prepare(String pendingId, StorageProgressListener listener) throws IOException {
        var existing = pending.directoryMergeOwner(pendingId);
        if (existing.isPresent()) {
            var review = reviews.require(existing.get());
            if (review.plan().operation() != DirectoryTransferPlan.Operation.PENDING
                    || !review.plan().sourceReference().equals(pendingId)) {
                throw new io.github.fourilla.endervault.common.StorageAccessException("Pending merge plan does not match its owner.");
            }
            return review;
        }
        String mergeId = UUID.randomUUID().toString();
        var decision = pending.claimDirectoryMerge(pendingId, mergeId);
        DirectoryTransferPlan plan;
        try {
            var scanned = planner.planPending(decision, listener);
            plan = new DirectoryTransferPlan(mergeId, scanned.operation(), scanned.sourceReference(),
                    scanned.destinationPath(), scanned.createdAt(), scanned.items());
        } catch (IOException | RuntimeException ex) {
            try { pending.releaseDirectoryMergeClaim(pendingId, mergeId); }
            catch (IOException | RuntimeException failure) { ex.addSuppressed(failure); }
            throw ex;
        }
        // A failed durable write can still have installed the plan: preserve ownership on uncertain outcomes.
        return reviews.create(plan);
    }
}
