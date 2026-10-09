package io.github.fourilla.endervault.directorytransfer;

import io.github.fourilla.endervault.common.StorageAccessException;
import io.github.fourilla.endervault.filecommit.DurableJsonFileWriter;
import io.github.fourilla.endervault.storage.DirectoryMoveSupport;
import io.github.fourilla.endervault.storage.FileLifecycleService;
import io.github.fourilla.endervault.storage.StorageProgressListener;
import io.github.fourilla.endervault.storage.StorageService;
import java.io.IOException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.HashMap;
import java.util.Map;
import org.springframework.stereotype.Service;
import tools.jackson.databind.ObjectMapper;

/** A no-replace rename has its own recovery proof; it must never enter per-file source cleanup. */
@Service
public class DirectoryTransferWholeMoveCoordinator {
    private final DirectoryTransferReviewStore reviews;
    private final StorageService storage;
    private final DirectoryMoveSupport nativeMoves;
    private final FileLifecycleService lifecycle;
    private final DurableJsonFileWriter durability;

    public DirectoryTransferWholeMoveCoordinator(DirectoryTransferReviewStore reviews, StorageService storage,
            DirectoryMoveSupport nativeMoves, FileLifecycleService lifecycle, ObjectMapper mapper) {
        this.reviews = reviews;
        this.storage = storage;
        this.nativeMoves = nativeMoves;
        this.lifecycle = lifecycle;
        this.durability = new DurableJsonFileWriter(mapper);
    }

    /** Structural ineligibility uses the old engine; stale approval is rejected, never bypassed. */
    public boolean eligible(DirectoryTransferReview review, StorageProgressListener listener) throws IOException {
        var plan = review.plan();
        if (!pristineApproval(review) || reviews.frozen(plan.id()) || reviews.run(plan.id()) != null
                || reviews.move(plan.id()) != null || reviews.successor(plan.id()) != null
                || reviews.hasPredecessor(plan.id()) || !reviews.results(review).isEmpty()) return false;
        for (var item : plan.items()) if (reviews.completion(plan.id(), item.id()) != null) return false;
        var paths = paths(plan);
        var progress = progress(listener);
        var keys = validateTree(plan, paths.source(), progress, null);
        requireAbsent(paths.target());
        var sourceParent = identifiedSnapshot(paths.source().getParent());
        var targetParent = identifiedSnapshot(paths.target().getParent());
        if (!stableDirectory(sourceParent) || !stableDirectory(targetParent)
                || keys.size() != plan.items().size()) return false;
        return nativeMoves.supported(paths.source(), paths.target().getParent());
    }

    public DirectoryTransferMoveRecord execute(DirectoryTransferReview review, StorageProgressListener listener) throws IOException {
        if (!pristineApproval(review)) throw new StorageAccessException("Whole-directory move approval changed.");
        var plan = review.plan();
        var progress = progress(listener);
        var move = reviews.move(plan.id());
        if (move != null && terminal(move)) return move;
        if (move == null) {
            var paths = paths(plan);
            var sourceParent = identifiedSnapshot(paths.source().getParent());
            var targetParent = identifiedSnapshot(paths.target().getParent());
            Map<String, String> keys;
            String changed = null;
            try { keys = validateTree(plan, paths.source(), progress, null); }
            catch (StorageAccessException | NoSuchFileException ex) {
                keys = Map.of();
                changed = ex.getMessage();
            }
            if (keys.size() != plan.items().size()) {
                keys = Map.of();
                if (changed == null) changed = "Directory move has no stable filesystem identity proof.";
            }
            move = new DirectoryTransferMoveRecord(plan.id(), review.revision(), plan.sourceReference(), plan.destinationPath(),
                    sourceParent, targetParent, DirectoryTransferMoveRecord.fingerprint(plan),
                    DirectoryTransferMoveRecord.Phase.INTENT, null, keys);
            reviews.saveMove(null, move);
            if (changed != null) return needsReview(move, changed);
        }
        try {
            requireKeys(plan, move);
            var paths = paths(plan);
            validateParents(move, paths);
            if (move.phase() == DirectoryTransferMoveRecord.Phase.INTENT) {
                boolean sourcePresent = DirectoryTransferPlanner.snapshotIfPresent(paths.source()) != null;
                boolean targetPresent = DirectoryTransferPlanner.snapshotIfPresent(paths.target()) != null;
                if (sourcePresent && !targetPresent) {
                    progress.onProgress("Moving", 0, plan.items().size(), 0, 0);
                    validateTree(plan, paths.source(), progress, move.entryKeys());
                    validateParents(move, paths);
                    requireAbsent(paths.target());
                    progress.checkCanceled();
                    boolean renamed;
                    try { renamed = nativeMoves.moveNoReplace(paths.source(), paths.target()); }
                    catch (FileAlreadyExistsException ex) {
                        return needsReview(move, "Destination appeared before the directory could be moved.");
                    }
                    if (!renamed) {
                        // A primitive can decline only with no side effects. Prove that again before falling back.
                        validateParents(move, paths);
                        validateTree(plan, paths.source(), progress, move.entryKeys());
                        requireAbsent(paths.target());
                        return update(move, DirectoryTransferMoveRecord.Phase.FALLBACK, null);
                    }
                } else if (sourcePresent || !targetPresent) {
                    return needsReview(move, "Directory move locations changed. Inspect the original and destination.");
                }
                // This also adopts a rename completed before a crash while the durable record still said INTENT.
                validateRenamedTree(plan, move, paths);
                durability.forceDirectory(paths.source().getParent());
                durability.forceDirectory(paths.target().getParent());
                move = update(move, DirectoryTransferMoveRecord.Phase.RENAMED, null);
            }
            if (move.phase() == DirectoryTransferMoveRecord.Phase.RENAMED) {
                progress.checkCanceled();
                progress.onProgress("Finalizing", 0, plan.items().size(), 0, 0);
                progress.checkCanceled();
                validateRenamedTree(plan, move, paths);
                // A root prefix migration covers descendants. Retrying is safe only while the old root stays absent.
                lifecycle.applyMovedPathMetadata(move.sourcePath(), move.targetPath());
                validateRenamedTree(plan, move, paths);
                move = update(move, DirectoryTransferMoveRecord.Phase.METADATA_APPLIED, null);
            }
            if (move.phase() == DirectoryTransferMoveRecord.Phase.METADATA_APPLIED) {
                validateRenamedTree(plan, move, paths);
                long completed = 0;
                // Planner ordering is not an execution authority: explicitly put parents before children.
                var ordered = plan.items().stream().sorted(java.util.Comparator
                        .comparingInt((DirectoryTransferPlan.Item item) -> depth(item.relativePath()))
                        .thenComparing(DirectoryTransferPlan.Item::relativePath)).toList();
                for (var item : ordered) {
                    String target = move.targetPath() + (item.relativePath().isEmpty() ? "" : "/" + item.relativePath());
                    reviews.recordResult(review, item, new DirectoryTransferResult(item.id(), DirectoryTransferResult.Status.MOVED,
                            target, item.source(), null, null, move.id()), move);
                    reviews.recordMovedCompletion(review, item, move);
                    progress.onItemProcessed();
                    progress.onProgress("Finalizing", ++completed, ordered.size(), 0, 0);
                }
                move = update(move, DirectoryTransferMoveRecord.Phase.COMPLETE, null);
            }
            return move;
        } catch (StorageAccessException | NoSuchFileException ex) {
            return needsReview(move, ex.getMessage());
        }
        // Other I/O failures have uncertain effects. Leave evidence in place for recovery; never copy blindly.
    }

    private void validateRenamedTree(DirectoryTransferPlan plan, DirectoryTransferMoveRecord move, Paths paths) throws IOException {
        validateParents(move, paths);
        requireAbsent(paths.source());
        validateTree(plan, paths.target(), StorageProgressListener.NOOP, move.entryKeys());
    }

    private void validateParents(DirectoryTransferMoveRecord move, Paths paths) throws IOException {
        requireParent(move.sourceParent(), paths.source().getParent());
        requireParent(move.targetParent(), paths.target().getParent());
    }

    private void requireParent(DirectoryTransferPlan.Snapshot expected, Path path) throws IOException {
        DirectoryTransferPlanner.rejectLinks(path);
        var actual = identifiedSnapshot(path);
        // Children being renamed necessarily change parent times. Identity, not the old mtime, binds the parent.
        // Match the existing publisher's identity policy. Some Unix providers expose mtime as creationTime.
        if (!stableDirectory(expected) || !stableDirectory(actual) || !expected.fileKey().equals(actual.fileKey())) {
            throw new StorageAccessException("Directory move parent changed. Inspect the transfer before continuing.");
        }
    }

    private Paths paths(DirectoryTransferPlan plan) throws IOException {
        Path source = storage.resolveVaultCommitTarget(plan.sourceReference());
        Path target = storage.resolveVaultCommitTarget(plan.destinationPath());
        DirectoryTransferPlanner.rejectLinks(source);
        DirectoryTransferPlanner.rejectLinks(target);
        if (source.startsWith(target) || target.startsWith(source)) {
            throw new StorageAccessException("Source and destination directories must not overlap.");
        }
        return new Paths(source, target);
    }

    private static Map<String, String> validateTree(DirectoryTransferPlan plan, Path root, StorageProgressListener progress,
            Map<String, String> expectedKeys) throws IOException {
        Map<String, DirectoryTransferPlan.Item> expected = new HashMap<>();
        Map<String, String> keys = new HashMap<>();
        for (var item : plan.items()) expected.put(item.relativePath(), item);
        Files.walkFileTree(root, new SimpleFileVisitor<>() {
            @Override public FileVisitResult preVisitDirectory(Path path, BasicFileAttributes attrs) throws IOException {
                validate(path);
                return FileVisitResult.CONTINUE;
            }
            @Override public FileVisitResult visitFile(Path path, BasicFileAttributes attrs) throws IOException {
                validate(path);
                return FileVisitResult.CONTINUE;
            }
            private void validate(Path path) throws IOException {
                progress.checkCanceled();
                DirectoryTransferPlanner.rejectLinks(path);
                String relative = root.relativize(path).toString().replace('\\', '/');
                var item = expected.remove(relative);
                if (item == null || !item.source().equals(DirectoryTransferPlanner.snapshot(path))) {
                    throw new StorageAccessException("Directory tree changed after approval. Inspect the transfer before continuing.");
                }
                String key = DirectoryMoveSupport.fileKey(path);
                if (expectedKeys != null && (key == null || !key.equals(expectedKeys.get(relative)))) {
                    throw new StorageAccessException("Directory entry identity changed after move approval.");
                }
                if (key != null) keys.put(relative, key);
            }
        });
        if (!expected.isEmpty()) throw new StorageAccessException("Directory tree entries disappeared after approval.");
        return Map.copyOf(keys);
    }

    private static void requireAbsent(Path path) throws IOException {
        DirectoryTransferPlanner.rejectLinks(path);
        if (DirectoryTransferPlanner.snapshotIfPresent(path) != null) {
            throw new StorageAccessException("An entry appeared at a directory move location. Inspect the transfer before continuing.");
        }
    }

    private DirectoryTransferMoveRecord needsReview(DirectoryTransferMoveRecord move, String detail) throws IOException {
        return update(move, DirectoryTransferMoveRecord.Phase.NEEDS_REVIEW, detail);
    }

    private DirectoryTransferMoveRecord update(DirectoryTransferMoveRecord previous, DirectoryTransferMoveRecord.Phase phase,
            String detail) throws IOException {
        var next = new DirectoryTransferMoveRecord(previous.id(), previous.revision(), previous.sourcePath(), previous.targetPath(),
                previous.sourceParent(), previous.targetParent(), previous.treeFingerprint(), phase, detail, previous.entryKeys());
        reviews.saveMove(previous, next);
        return next;
    }

    private static boolean pristineApproval(DirectoryTransferReview review) {
        var plan = review.plan();
        return plan.operation() == DirectoryTransferPlan.Operation.MOVE && review.choices().isEmpty()
                && plan.targetNames().isEmpty() && plan.excludedSources().isEmpty()
                && plan.items().stream().allMatch(item -> item.conflict() == DirectoryTransferPlan.Conflict.ADD);
    }

    private static boolean stableDirectory(DirectoryTransferPlan.Snapshot snapshot) {
        return snapshot.kind() == DirectoryTransferPlan.Kind.DIRECTORY && snapshot.fileKey() != null;
    }

    private static DirectoryTransferPlan.Snapshot identifiedSnapshot(Path path) throws IOException {
        var value = DirectoryTransferPlanner.snapshot(path);
        return new DirectoryTransferPlan.Snapshot(value.kind(), value.size(), value.modifiedAt(),
                DirectoryMoveSupport.fileKey(path), value.createdAt());
    }

    private static void requireKeys(DirectoryTransferPlan plan, DirectoryTransferMoveRecord move) {
        if (!stableDirectory(move.sourceParent()) || !stableDirectory(move.targetParent())
                || move.entryKeys().size() != plan.items().size()
                || plan.items().stream().anyMatch(item -> move.entryKeys().get(item.relativePath()) == null)) {
            throw new StorageAccessException("Directory move has no stable filesystem identity proof.");
        }
    }

    private static boolean terminal(DirectoryTransferMoveRecord move) {
        return move.phase() == DirectoryTransferMoveRecord.Phase.COMPLETE
                || move.phase() == DirectoryTransferMoveRecord.Phase.NEEDS_REVIEW
                || move.phase() == DirectoryTransferMoveRecord.Phase.FALLBACK;
    }

    private static StorageProgressListener progress(StorageProgressListener listener) {
        return listener == null ? StorageProgressListener.NOOP : listener;
    }

    private static int depth(String relative) { return relative.isEmpty() ? 0 : relative.split("/").length; }
    private record Paths(Path source, Path target) { }
}
