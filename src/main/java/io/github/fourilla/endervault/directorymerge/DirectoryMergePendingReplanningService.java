package io.github.fourilla.endervault.directorymerge;

import io.github.fourilla.endervault.common.StorageAccessException;
import io.github.fourilla.endervault.pending.PendingFileDecisionService;
import io.github.fourilla.endervault.storage.StorageProgressListener;
import io.github.fourilla.endervault.storage.StorageService;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.HashMap;
import org.springframework.stereotype.Service;

@Service
public class DirectoryMergePendingReplanningService {
    private final DirectoryMergeReviewStore reviews;
    private final PendingFileDecisionService pending;
    private final DirectoryMergePlanner planner;
    private final StorageService storage;

    public DirectoryMergePendingReplanningService(DirectoryMergeReviewStore reviews, PendingFileDecisionService pending,
            DirectoryMergePlanner planner, StorageService storage) {
        this.reviews = reviews;
        this.pending = pending;
        this.planner = planner;
        this.storage = storage;
    }

    public synchronized DirectoryMergeReview replan(String id, long revision, StorageProgressListener listener) throws IOException {
        var old = reviews.require(id);
        var run = reviews.run(id);
        if (old.plan().operation() != DirectoryMergePlan.Operation.PENDING || old.revision() != revision
                || run == null || run.phase() != DirectoryMergeRun.Phase.NEEDS_REVIEW) {
            throw new StorageAccessException("Only a pending merge requiring review can be replanned.");
        }
        String pendingId = old.plan().sourceReference();
        String nextId = reviews.successor(id);
        if (nextId != null) {
            var next = reviews.require(nextId);
            if (next.plan().operation() != DirectoryMergePlan.Operation.PENDING
                    || !pendingId.equals(next.plan().sourceReference())
                    || !old.plan().destinationPath().equals(next.plan().destinationPath())) {
                throw new StorageAccessException("Pending successor changed.");
            }
            pending.transferDirectoryMergeOwner(pendingId, id, nextId);
            return next;
        }
        var decision = pending.requireDirectoryMergeOwner(pendingId, id);
        Path staged = storage.resolveFileStagingFile(decision.stagingFilename());
        var results = reviews.results(old);
        var names = new HashMap<>(old.plan().targetNames());
        for (var item : old.plan().items()) {
            var completion = reviews.completion(id, item.id());
            if (completion == null) throw new StorageAccessException("Previous merge completion is missing.");
            Path source = staged.resolve(item.relativePath());
            DirectoryMergePlanner.rejectLinks(source);
            if (completion.phase() != DirectoryMergeCompletion.Phase.COMPLETE
                    && !Files.exists(source, LinkOption.NOFOLLOW_LINKS)) {
                throw new StorageAccessException("Unresolved upload source is missing; manual recovery is required.");
            }
            var result = results.get(item.id());
            if (result != null && result.status() == DirectoryMergeResult.Status.PUBLISHED) {
                String target = result.targetPath();
                names.put(item.relativePath(), target.substring(target.lastIndexOf('/') + 1));
            }
        }
        var plan = planner.planPending(decision, listener, names);
        var next = reviews.create(plan);
        // Keep the old claim until both the new plan and handoff link are durable.
        reviews.recordSuccessor(id, plan.id());
        pending.transferDirectoryMergeOwner(pendingId, id, plan.id());
        return next;
    }
}
