package io.github.fourilla.endervault.directorymerge;

import io.github.fourilla.endervault.common.StorageAccessException;
import io.github.fourilla.endervault.storage.StorageProgressListener;
import io.github.fourilla.endervault.storage.StorageService;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.util.HashMap;
import java.util.HashSet;
import org.springframework.stereotype.Service;

@Service
public class DirectoryMergeTransferReplanningService {
    private final DirectoryMergeReviewStore reviews;
    private final DirectoryMergePlanner planner;
    private final StorageService storage;

    public DirectoryMergeTransferReplanningService(DirectoryMergeReviewStore reviews, DirectoryMergePlanner planner,
            StorageService storage) {
        this.reviews = reviews;
        this.planner = planner;
        this.storage = storage;
    }

    public synchronized DirectoryMergeReview replan(String id, long revision, StorageProgressListener listener) throws IOException {
        var old = reviews.require(id);
        var run = reviews.run(id);
        if (old.plan().operation() == DirectoryMergePlan.Operation.PENDING || old.revision() != revision
                || run == null || run.phase() != DirectoryMergeRun.Phase.NEEDS_REVIEW) {
            throw new StorageAccessException("Only a transfer requiring review can be replanned.");
        }
        String successor = reviews.successor(id);
        if (successor != null) {
            var next = reviews.require(successor);
            if (next.plan().operation() != old.plan().operation()
                    || !next.plan().sourceReference().equals(old.plan().sourceReference())
                    || !next.plan().destinationPath().equals(old.plan().destinationPath())) {
                throw new StorageAccessException("Transfer successor changed.");
            }
            return next;
        }
        var source = storage.resolveVaultDirectory(old.plan().sourceReference());
        var results = reviews.results(old);
        var names = new HashMap<>(old.plan().targetNames());
        var excluded = new HashSet<>(old.plan().excludedSources());
        for (var item : old.plan().items()) {
            if (listener != null) listener.checkCanceled();
            var completion = reviews.completion(id, item.id());
            var result = results.get(item.id());
            if (completion == null || result == null) throw new StorageAccessException("Previous transfer result is missing.");
            // COPY keeps its source, so filesystem presence alone cannot identify unfinished work.
            if (result.status() == DirectoryMergeResult.Status.SKIPPED
                    || item.source().kind() == DirectoryMergePlan.Kind.FILE
                    && completion.phase() == DirectoryMergeCompletion.Phase.COMPLETE
                    && (old.plan().operation() == DirectoryMergePlan.Operation.COPY
                            || Files.exists(source.resolve(item.relativePath()), LinkOption.NOFOLLOW_LINKS))) {
                excluded.add(item.relativePath());
            } else if (completion.phase() != DirectoryMergeCompletion.Phase.COMPLETE
                    && completion.phase() != DirectoryMergeCompletion.Phase.RETAINED
                    && !Files.exists(source.resolve(item.relativePath()), LinkOption.NOFOLLOW_LINKS)) {
                throw new StorageAccessException("Unresolved transfer source is missing; manual recovery is required.");
            }
            if (result.status() == DirectoryMergeResult.Status.PUBLISHED) {
                String target = result.targetPath();
                names.put(item.relativePath(), target.substring(target.lastIndexOf('/') + 1));
            }
        }
        String destination = old.plan().destinationPath();
        int slash = destination.lastIndexOf('/');
        var plan = planner.planTransfer(old.plan().operation(), old.plan().sourceReference(),
                slash < 0 ? "" : destination.substring(0, slash), listener, names, excluded);
        if (!plan.destinationPath().equals(old.plan().destinationPath())) throw new StorageAccessException("Transfer destination changed.");
        var next = reviews.create(plan);
        reviews.recordSuccessor(id, next.plan().id());
        return next;
    }
}
