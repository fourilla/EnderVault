package io.github.fourilla.endervault.directorymerge;

import static io.github.fourilla.endervault.directorymerge.DirectoryMergePlan.*;
import static io.github.fourilla.endervault.directorymerge.DirectoryMergeCompletion.Phase;

import io.github.fourilla.endervault.common.StorageAccessException;
import io.github.fourilla.endervault.filecommit.DurableJsonFileWriter;
import io.github.fourilla.endervault.filecommit.FileCommitCoordinator;
import io.github.fourilla.endervault.filecommit.FileCommitOwner;
import io.github.fourilla.endervault.filecommit.FileCommitOwnerType;
import io.github.fourilla.endervault.storage.FileLifecycleService;
import io.github.fourilla.endervault.storage.StorageService;
import io.github.fourilla.endervault.storage.StorageProgressListener;
import java.io.IOException;
import java.nio.file.DirectoryNotEmptyException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import org.springframework.stereotype.Service;
import tools.jackson.databind.ObjectMapper;

/** Source cleanup is independent of publication. It never recursively deletes an original tree. */
@Service
public class DirectoryMergeFinalizer {
    private final DirectoryMergeReviewStore reviews;
    private final StorageService storage;
    private final FileCommitCoordinator commits;
    private final FileLifecycleService lifecycle;
    private final DurableJsonFileWriter durability;

    public DirectoryMergeFinalizer(DirectoryMergeReviewStore reviews, StorageService storage,
            FileCommitCoordinator commits, FileLifecycleService lifecycle, ObjectMapper mapper) {
        this.reviews = reviews;
        this.storage = storage;
        this.commits = commits;
        this.lifecycle = lifecycle;
        this.durability = new DurableJsonFileWriter(mapper);
    }

    public synchronized Map<String, DirectoryMergeCompletion> completeTransfer(String id, long revision,
            StorageProgressListener listener) throws IOException {
        var review = reviews.require(id);
        if (review.plan().operation() == Operation.PENDING) throw new StorageAccessException("Pending completion belongs to its upload owner.");
        review = reviews.freeze(id, revision);
        var results = reviews.results(review);
        if (review.plan().items().stream().anyMatch(item -> !results.containsKey(item.id()))) {
            throw new StorageAccessException("Finish publication before completing the merge.");
        }
        var index = new DirectoryMergeIndex(review.plan());
        var progress = listener == null ? StorageProgressListener.NOOP : listener;
        var completed = new HashMap<String, DirectoryMergeCompletion>();
        // Children must finish before an empty original directory and its own metadata can move.
        var ordered = review.plan().items().stream()
                .sorted(Comparator.comparingInt((Item item) -> depth(item.relativePath())).reversed()).toList();
        for (var item : ordered) {
            progress.checkCanceled();
            var result = results.get(item.id());
            if (result == null) continue;
            var state = reviews.completion(id, item.id());
            if (state != null && terminal(state.phase())) {
                if (state.phase() == Phase.COMPLETE) completeJournal(review, item, result);
                completed.put(item.id(), state);
                continue;
            }
            if (result.status() != DirectoryMergeResult.Status.PUBLISHED) {
                state = update(id, item.id(), state, result.status() == DirectoryMergeResult.Status.SKIPPED
                        ? Phase.RETAINED : Phase.NEEDS_REVIEW, result.detail());
            } else {
                try {
                    boolean unfinishedChild = item.source().kind() == Kind.DIRECTORY
                            && review.plan().operation() == Operation.MOVE
                            && index.children(item).stream().anyMatch(child -> !completed.containsKey(child.id())
                                    || completed.get(child.id()).phase() != Phase.COMPLETE);
                    state = unfinishedChild ? update(id, item.id(), state, Phase.RETAINED,
                            "Original directory has unfinished or retained children.")
                            : finishItem(review, index, item, result, results, state);
                } catch (StorageAccessException | NoSuchFileException ex) {
                    // Journals and source data remain owned for explicit review; no automatic retry with new approval.
                    state = reviews.completion(id, item.id());
                    state = update(id, item.id(), state, Phase.NEEDS_REVIEW, ex.getMessage());
                }
            }
            completed.put(item.id(), state);
            progress.onItemProcessed();
        }
        return Map.copyOf(completed);
    }

    private DirectoryMergeCompletion finishItem(DirectoryMergeReview review, DirectoryMergeIndex index,
            Item item, DirectoryMergeResult result, Map<String, DirectoryMergeResult> results,
            DirectoryMergeCompletion state) throws IOException {
        String id = review.plan().id();
        if (state == null) {
            validateTarget(index, item, result, results);
            if (review.plan().operation() == Operation.MOVE) {
                Path source = sourcePath(review.plan(), item);
                validateSourceParents(index, review.plan(), item);
                validateSource(item, source);
                if (item.source().kind() == Kind.DIRECTORY && !empty(source)) {
                    return update(id, item.id(), null, Phase.RETAINED, "Original directory still contains entries.");
                }
            }
            state = update(id, item.id(), null, Phase.PREPARED, null);
        }
        if (state.phase() == Phase.PREPARED) {
            validateTarget(index, item, result, results);
            if (review.plan().operation() == Operation.MOVE) {
                Path source = sourcePath(review.plan(), item);
                validateSourceParents(index, review.plan(), item);
                DirectoryMergePlanner.rejectLinks(source);
                if (Files.exists(source, LinkOption.NOFOLLOW_LINKS)) {
                    validateSource(item, source);
                    try { Files.delete(source); }
                    catch (DirectoryNotEmptyException ex) {
                        return update(id, item.id(), state, Phase.RETAINED, "Original directory still contains entries.");
                    }
                }
                // PREPARED is durable before deletion. Missing source here can be a crash after delete.
                durability.forceDirectory(source.getParent());
                state = update(id, item.id(), state, Phase.SOURCE_REMOVED, null);
            } else {
                state = update(id, item.id(), state, Phase.METADATA_APPLIED, null);
            }
        }
        if (state.phase() == Phase.SOURCE_REMOVED) {
            validateTarget(index, item, result, results);
            Path source = sourcePath(review.plan(), item);
            validateSourceParents(index, review.plan(), item);
            DirectoryMergePlanner.rejectLinks(source);
            if (Files.exists(source, LinkOption.NOFOLLOW_LINKS)) throw new StorageAccessException("A new source entry appeared after merge cleanup.");
            lifecycle.applyMovedPathMetadata(join(review.plan().sourceReference(), item.relativePath()), result.targetPath());
            state = update(id, item.id(), state, Phase.METADATA_APPLIED, null);
        }
        if (state.phase() == Phase.METADATA_APPLIED) {
            state = update(id, item.id(), state, Phase.COMPLETE, null);
        }
        if (state.phase() == Phase.COMPLETE) completeJournal(review, item, result);
        return state;
    }

    private void completeJournal(DirectoryMergeReview review, Item item, DirectoryMergeResult result) throws IOException {
        if (result.commitId() == null) return;
        var existing = commits.findByOwner(new FileCommitOwner(FileCommitOwnerType.DIRECTORY_MERGE,
                review.plan().id() + ":" + item.id()));
        if (existing.isEmpty()) return;
        if (!existing.get().manifest().operationId().equals(result.commitId())) throw new StorageAccessException("Merge completion journal changed.");
        DirectoryMergeJournalGuard.requireMatches(existing.get(), review, item,
                result.targetPath());
        commits.complete(result.commitId());
    }

    private void validateTarget(DirectoryMergeIndex index, Item item, DirectoryMergeResult result,
            Map<String, DirectoryMergeResult> results) throws IOException {
        for (Item parent = index.parent(item); parent != null; parent = index.parent(parent)) {
            var prepared = results.get(parent.id());
            DirectoryMergeFilePublisher.requireDirectoryIdentity(storage.resolveVaultCommitTarget(prepared.targetPath()), prepared.target());
        }
        Path target = storage.resolveVaultCommitTarget(result.targetPath());
        DirectoryMergePlanner.rejectLinks(target);
        if (item.source().kind() == Kind.DIRECTORY) {
            DirectoryMergeFilePublisher.requireDirectoryIdentity(target, result.target());
        } else if (!Objects.equals(result.target(), DirectoryMergePlanner.snapshotIfPresent(target))) {
            throw new StorageAccessException("Published target changed before source cleanup.");
        }
    }

    private Path sourcePath(DirectoryMergePlan plan, Item item) throws IOException {
        return storage.resolveVaultCommitTarget(join(plan.sourceReference(), item.relativePath()));
    }

    private void validateSourceParents(DirectoryMergeIndex index, DirectoryMergePlan plan, Item item) throws IOException {
        for (Item parent = index.parent(item); parent != null; parent = index.parent(parent)) {
            DirectoryMergeFilePublisher.requireDirectoryIdentity(sourcePath(plan, parent), parent.source());
        }
    }

    private void validateSource(Item item, Path source) throws IOException {
        DirectoryMergePlanner.rejectLinks(source);
        if (item.source().kind() == Kind.DIRECTORY) DirectoryMergeFilePublisher.requireDirectoryIdentity(source, item.source());
        else if (!Objects.equals(item.source(), DirectoryMergePlanner.snapshotIfPresent(source))) {
            throw new StorageAccessException("Original file changed before merge cleanup.");
        }
    }

    private boolean empty(Path directory) throws IOException {
        try (var children = Files.list(directory)) { return children.findAny().isEmpty(); }
    }

    private DirectoryMergeCompletion update(String id, String itemId, DirectoryMergeCompletion previous,
            Phase phase, String detail) throws IOException {
        var next = new DirectoryMergeCompletion(itemId, phase, detail);
        reviews.recordCompletion(id, previous, next);
        return next;
    }

    private static boolean terminal(Phase phase) { return phase == Phase.COMPLETE || phase == Phase.RETAINED || phase == Phase.NEEDS_REVIEW; }
    private static int depth(String path) { return path.isEmpty() ? 0 : path.split("/").length; }
    private static String join(String parent, String child) { return child.isEmpty() ? parent : parent.isEmpty() ? child : parent + "/" + child; }
}
