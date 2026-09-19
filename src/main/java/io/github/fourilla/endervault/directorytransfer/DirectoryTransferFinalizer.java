package io.github.fourilla.endervault.directorytransfer;

import static io.github.fourilla.endervault.directorytransfer.DirectoryTransferPlan.*;
import static io.github.fourilla.endervault.directorytransfer.DirectoryTransferCompletion.Phase;

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
public class DirectoryTransferFinalizer {
    private final DirectoryTransferReviewStore reviews;
    private final StorageService storage;
    private final FileCommitCoordinator commits;
    private final FileLifecycleService lifecycle;
    private final DurableJsonFileWriter durability;

    public DirectoryTransferFinalizer(DirectoryTransferReviewStore reviews, StorageService storage,
            FileCommitCoordinator commits, FileLifecycleService lifecycle, ObjectMapper mapper) {
        this.reviews = reviews;
        this.storage = storage;
        this.commits = commits;
        this.lifecycle = lifecycle;
        this.durability = new DurableJsonFileWriter(mapper);
    }

    public synchronized Map<String, DirectoryTransferCompletion> completeTransfer(String id, long revision,
            StorageProgressListener listener) throws IOException {
        var review = reviews.require(id);
        if (review.plan().operation() == Operation.PENDING) throw new StorageAccessException("Pending completion belongs to its upload owner.");
        return complete(reviews.freeze(id, revision), null, listener);
    }

    /** Trusted pending record must be protected by its durable merge owner. */
    synchronized Map<String, DirectoryTransferCompletion> completePending(String id, long revision,
            io.github.fourilla.endervault.pending.PendingFileDecision pending, StorageProgressListener listener) throws IOException {
        var review = reviews.freeze(id, revision);
        if (review.plan().operation() != Operation.PENDING || !pending.directory()
                || !review.plan().sourceReference().equals(pending.id())
                || !review.plan().destinationPath().equals(join(storage.normalizeVaultDirectory(pending.destinationPath()), pending.originalFilename()))) {
            throw new StorageAccessException("Pending owner changed.");
        }
        return complete(review, storage.resolveFileStagingFile(pending.stagingFilename()), listener);
    }

    private Map<String, DirectoryTransferCompletion> complete(DirectoryTransferReview review, Path sourceRoot,
            StorageProgressListener listener) throws IOException {
        String id = review.plan().id();
        var results = reviews.results(review);
        if (review.plan().items().stream().anyMatch(item -> !results.containsKey(item.id()))) {
            throw new StorageAccessException("Finish publication before completing the merge.");
        }
        var index = new DirectoryTransferIndex(review.plan());
        var progress = listener == null ? StorageProgressListener.NOOP : listener;
        var completed = new HashMap<String, DirectoryTransferCompletion>();
        var excludedParents = new java.util.HashSet<String>();
        for (String excluded : review.plan().excludedSources()) {
            String path = excluded;
            while (!path.isEmpty()) {
                int slash = path.lastIndexOf('/');
                path = slash < 0 ? "" : path.substring(0, slash);
                excludedParents.add(path);
            }
        }
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
            boolean discard = result.status() == DirectoryTransferResult.Status.DISCARD_APPROVED;
            if (discard && (review.plan().operation() != Operation.PENDING || !discardApproved(index, item, review))) {
                throw new StorageAccessException("Upload discard was not approved.");
            }
            if (result.status() != DirectoryTransferResult.Status.PUBLISHED && !discard) {
                state = update(id, item.id(), state, result.status() == DirectoryTransferResult.Status.SKIPPED
                        ? Phase.RETAINED : Phase.NEEDS_REVIEW, result.detail());
            } else {
                try {
                    boolean unfinishedChild = item.source().kind() == Kind.DIRECTORY
                            && review.plan().operation() != Operation.COPY
                            && (index.children(item).stream().anyMatch(child -> !completed.containsKey(child.id())
                                    || completed.get(child.id()).phase() != Phase.COMPLETE)
                                || excludedParents.contains(item.relativePath()));
                    state = unfinishedChild ? update(id, item.id(), state, Phase.RETAINED,
                            "Original directory has unfinished or retained children.")
                            : finishItem(review, index, item, result, results, state, sourceRoot, discard);
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

    private DirectoryTransferCompletion finishItem(DirectoryTransferReview review, DirectoryTransferIndex index,
            Item item, DirectoryTransferResult result, Map<String, DirectoryTransferResult> results,
            DirectoryTransferCompletion state, Path sourceRoot, boolean discard) throws IOException {
        String id = review.plan().id();
        if (state == null) {
            if (!discard) validateTarget(index, item, result, results);
            if (review.plan().operation() != Operation.COPY) {
                Path source = sourcePath(review.plan(), item, sourceRoot);
                validateSourceParents(index, review.plan(), item, sourceRoot);
                validateSource(item, source);
                if (item.source().kind() == Kind.DIRECTORY && !empty(source)) {
                    return update(id, item.id(), null, Phase.RETAINED, "Original directory still contains entries.");
                }
            }
            state = update(id, item.id(), null, Phase.PREPARED, null);
        }
        if (state.phase() == Phase.PREPARED) {
            if (!discard) validateTarget(index, item, result, results);
            if (review.plan().operation() != Operation.COPY) {
                Path source = sourcePath(review.plan(), item, sourceRoot);
                validateSourceParents(index, review.plan(), item, sourceRoot);
                DirectoryTransferPlanner.rejectLinks(source);
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
            if (!discard) validateTarget(index, item, result, results);
            Path source = sourcePath(review.plan(), item, sourceRoot);
            validateSourceParents(index, review.plan(), item, sourceRoot);
            DirectoryTransferPlanner.rejectLinks(source);
            if (Files.exists(source, LinkOption.NOFOLLOW_LINKS)) throw new StorageAccessException("A new source entry appeared after merge cleanup.");
            if (review.plan().operation() == Operation.MOVE) {
                lifecycle.applyMovedPathMetadata(join(review.plan().sourceReference(), item.relativePath()), result.targetPath());
            }
            state = update(id, item.id(), state, Phase.METADATA_APPLIED, null);
        }
        if (state.phase() == Phase.METADATA_APPLIED) {
            state = update(id, item.id(), state, Phase.COMPLETE, null);
        }
        if (state.phase() == Phase.COMPLETE) completeJournal(review, item, result);
        return state;
    }

    private void completeJournal(DirectoryTransferReview review, Item item, DirectoryTransferResult result) throws IOException {
        if (result.commitId() == null) return;
        var existing = commits.findByOwner(new FileCommitOwner(FileCommitOwnerType.DIRECTORY_MERGE,
                review.plan().id() + ":" + item.id()));
        if (existing.isEmpty()) return;
        if (!existing.get().manifest().operationId().equals(result.commitId())) throw new StorageAccessException("Merge completion journal changed.");
        DirectoryTransferJournalGuard.requireMatches(existing.get(), review, item,
                result.targetPath());
        commits.complete(result.commitId());
    }

    private void validateTarget(DirectoryTransferIndex index, Item item, DirectoryTransferResult result,
            Map<String, DirectoryTransferResult> results) throws IOException {
        for (Item parent = index.parent(item); parent != null; parent = index.parent(parent)) {
            var prepared = results.get(parent.id());
            DirectoryTransferFilePublisher.requireDirectoryIdentity(storage.resolveVaultCommitTarget(prepared.targetPath()), prepared.target());
        }
        Path target = storage.resolveVaultCommitTarget(result.targetPath());
        DirectoryTransferPlanner.rejectLinks(target);
        if (item.source().kind() == Kind.DIRECTORY) {
            DirectoryTransferFilePublisher.requireDirectoryIdentity(target, result.target());
        } else if (!Objects.equals(result.target(), DirectoryTransferPlanner.snapshotIfPresent(target))) {
            throw new StorageAccessException("Published target changed before source cleanup.");
        }
    }

    private Path sourcePath(DirectoryTransferPlan plan, Item item, Path sourceRoot) throws IOException {
        if (sourceRoot != null) {
            Path source = sourceRoot.resolve(item.relativePath()).normalize();
            if (!source.startsWith(sourceRoot)) throw new StorageAccessException("Invalid pending source path.");
            DirectoryTransferPlanner.rejectLinks(source);
            return source;
        }
        return storage.resolveVaultCommitTarget(join(plan.sourceReference(), item.relativePath()));
    }

    private void validateSourceParents(DirectoryTransferIndex index, DirectoryTransferPlan plan, Item item, Path sourceRoot) throws IOException {
        for (Item parent = index.parent(item); parent != null; parent = index.parent(parent)) {
            DirectoryTransferFilePublisher.requireDirectoryIdentity(sourcePath(plan, parent, sourceRoot), parent.source());
        }
    }

    private void validateSource(Item item, Path source) throws IOException {
        DirectoryTransferPlanner.rejectLinks(source);
        if (item.source().kind() == Kind.DIRECTORY) DirectoryTransferFilePublisher.requireDirectoryIdentity(source, item.source());
        else if (!Objects.equals(item.source(), DirectoryTransferPlanner.snapshotIfPresent(source))) {
            throw new StorageAccessException("Original file changed before merge cleanup.");
        }
    }

    private boolean discardApproved(DirectoryTransferIndex index, Item item, DirectoryTransferReview review) {
        for (Item current = item; current != null; current = index.parent(current)) {
            if (review.choices().get(current.id()) == DirectoryTransferReview.Choice.DISCARD_UPLOAD) return true;
        }
        return false;
    }

    private boolean empty(Path directory) throws IOException {
        try (var children = Files.list(directory)) { return children.findAny().isEmpty(); }
    }

    private DirectoryTransferCompletion update(String id, String itemId, DirectoryTransferCompletion previous,
            Phase phase, String detail) throws IOException {
        var next = new DirectoryTransferCompletion(itemId, phase, detail);
        reviews.recordCompletion(id, previous, next);
        return next;
    }

    private static boolean terminal(Phase phase) { return phase == Phase.COMPLETE || phase == Phase.RETAINED || phase == Phase.NEEDS_REVIEW; }
    private static int depth(String path) { return path.isEmpty() ? 0 : path.split("/").length; }
    private static String join(String parent, String child) { return child.isEmpty() ? parent : parent.isEmpty() ? child : parent + "/" + child; }
}
