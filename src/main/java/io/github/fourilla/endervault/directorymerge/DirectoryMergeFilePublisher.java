package io.github.fourilla.endervault.directorymerge;

import static io.github.fourilla.endervault.directorymerge.DirectoryMergePlan.*;

import io.github.fourilla.endervault.common.StorageAccessException;
import io.github.fourilla.endervault.filecommit.FileCommitCoordinator;
import io.github.fourilla.endervault.filecommit.FileCommitFingerprint;
import io.github.fourilla.endervault.filecommit.FileCommitOwner;
import io.github.fourilla.endervault.filecommit.FileCommitOwnerType;
import io.github.fourilla.endervault.pending.PendingFileDecision;
import io.github.fourilla.endervault.storage.ConflictPolicy;
import io.github.fourilla.endervault.storage.StorageProgressListener;
import io.github.fourilla.endervault.storage.StorageService;
import io.github.fourilla.endervault.temporary.TemporaryArtifactRegistry;
import io.github.fourilla.endervault.temporary.TemporaryArtifactType;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Objects;
import java.util.Map;
import org.springframework.stereotype.Service;

/**
 * Leaf publication only: directory creation/remapping and lifecycle completion belong to the
 * merge orchestrator. A returned commit retains its journal until that owner completes metadata.
 * In particular, this class never deletes a transfer source or the only pending upload copy.
 */
@Service
public class DirectoryMergeFilePublisher {
    private final DirectoryMergeReviewStore reviews;
    private final StorageService storage;
    private final FileCommitCoordinator commits;
    private final TemporaryArtifactRegistry temporaryArtifacts;

    public DirectoryMergeFilePublisher(DirectoryMergeReviewStore reviews, StorageService storage,
            FileCommitCoordinator commits, TemporaryArtifactRegistry temporaryArtifacts) {
        this.reviews = reviews;
        this.storage = storage;
        this.commits = commits;
        this.temporaryArtifacts = temporaryArtifacts;
    }

    public synchronized FileCommitCoordinator.StagedFileCommit publishTransferFile(String reviewId,
            long revision, String itemId, StorageProgressListener listener) throws IOException {
        DirectoryMergeReview review = reviews.require(reviewId);
        if (review.plan().operation() == Operation.PENDING) {
            throw new StorageAccessException("Pending directory source must be resolved by its owner.");
        }
        return publish(review, revision, itemId, null, listener, null, new DirectoryMergeIndex(review.plan()));
    }

    /** The owner must load this pending record from the server repository, not request JSON. */
    public synchronized FileCommitCoordinator.StagedFileCommit publishPendingFile(String reviewId,
            long revision, String itemId, PendingFileDecision pending, StorageProgressListener listener)
            throws IOException {
        DirectoryMergeReview review = reviews.require(reviewId);
        if (review.plan().operation() != Operation.PENDING || pending == null || !pending.directory()
                || !review.plan().sourceReference().equals(pending.id())) {
            throw new StorageAccessException("Pending directory does not match the merge review.");
        }
        String destination = join(storage.normalizeVaultDirectory(pending.destinationPath()), pending.originalFilename());
        if (!destination.equals(review.plan().destinationPath())) {
            throw new StorageAccessException("Pending directory destination changed.");
        }
        return publish(review, revision, itemId, pending, listener, null, new DirectoryMergeIndex(review.plan()));
    }

    synchronized FileCommitCoordinator.StagedFileCommit publishPreparedFile(DirectoryMergeReview review,
            String itemId, PendingFileDecision pending, StorageProgressListener listener,
            Map<String, DirectoryMergeResult> results, DirectoryMergeIndex index) throws IOException {
        return publish(review, review.revision(), itemId, pending, listener, results, index);
    }

    private FileCommitCoordinator.StagedFileCommit publish(DirectoryMergeReview review, long revision,
            String itemId, PendingFileDecision pending, StorageProgressListener listener,
            Map<String, DirectoryMergeResult> results, DirectoryMergeIndex index) throws IOException {
        Item item = index.item(itemId);
        DirectoryMergeReview.Choice choice = review.choices().get(itemId);
        if (item.source().kind() != Kind.FILE || item.blockedBy() != null && results == null
                || item.requiresDecision() && choice != DirectoryMergeReview.Choice.OVERWRITE
                        && choice != DirectoryMergeReview.Choice.KEEP_BOTH) {
            throw new StorageAccessException("This merge item does not have an approved file publication.");
        }
        if (results == null) review = reviews.freeze(review.plan().id(), revision);
        FileCommitOwner owner = new FileCommitOwner(FileCommitOwnerType.DIRECTORY_MERGE,
                review.plan().id() + ":" + itemId);
        StorageProgressListener progress = listener == null ? StorageProgressListener.NOOP : listener;
        progress.checkCanceled();
        String targetPath = results == null ? join(review.plan().destinationPath(), item.relativePath())
                : index.targetPath(item, results);
        var existing = commits.findByOwner(owner);
        boolean published = false;
        if (existing.isPresent()) {
            DirectoryMergeJournalGuard.requireMatches(existing.get(), review, item, targetPath);
            published = DirectoryMergeJournalGuard.alreadyPublished(existing.get(), commits);
        }
        Path sourceRoot = published ? null : pending == null ? storage.resolveVaultDirectory(review.plan().sourceReference())
                : storage.resolveFileStagingFile(pending.stagingFilename());
        Path targetRoot = storage.resolveVaultCommitTarget(review.plan().destinationPath());
        if (sourceRoot != null && (sourceRoot.startsWith(targetRoot) || targetRoot.startsWith(sourceRoot))) {
            throw new StorageAccessException("Source and destination directories must not overlap.");
        }
        Path source = sourceRoot == null ? null : sourceRoot.resolve(item.relativePath());
        validateAncestors(index, item, sourceRoot, targetRoot, results);
        Path originalTarget = storage.resolveVaultCommitTarget(targetPath);
        if (source != null) requireSnapshot(source, item.source(), "Directory merge source changed. Scan again.");
        if (existing.isPresent()) {
            // Before publication validate the original; after publication reconcile the committed
            // target even if the original changed. Later source cleanup must revalidate separately.
            // Never recopy the source or choose a second KEEP_BOTH name after a lost response.
            return commits.resumeSingleFile(existing.get().manifest().operationId());
        }
        requireSnapshot(originalTarget, item.target(), "Directory merge destination changed. Review it again.");
        int slash = targetPath.lastIndexOf('/');
        String directory = slash < 0 ? "" : targetPath.substring(0, slash);
        String name = slash < 0 ? targetPath : targetPath.substring(slash + 1);
        storage.resolveVaultDirectory(directory);
        ConflictPolicy policy = choice == DirectoryMergeReview.Choice.OVERWRITE ? ConflictPolicy.OVERWRITE
                : choice == DirectoryMergeReview.Choice.KEEP_BOTH ? ConflictPolicy.RENAME : ConflictPolicy.CANCEL;
        if (policy == ConflictPolicy.RENAME) name = storage.resolveAvailableVaultFilename(directory, name);
        FileCommitFingerprint approved = policy == ConflictPolicy.OVERWRITE
                ? new FileCommitFingerprint(item.target().size(), item.target().modifiedAt(), item.target().fileKey()) : null;
        Path staged = storage.createFileStagingTemporaryFile("directory-merge-", ".tmp");
        try (var registration = temporaryArtifacts.register(staged, TemporaryArtifactType.FILE_COPY, owner.id())) {
            copySnapshot(source, staged, item.source().size(), progress);
            validateAncestors(index, item, sourceRoot, targetRoot, results);
            requireSnapshot(source, item.source(), "Directory merge source changed while copying. Scan again.");
            requireSnapshot(originalTarget, item.target(), "Directory merge destination changed while copying. Review it again.");
            progress.checkCanceled();
            String operation = commits.prepareReviewedSingleFile(owner, staged, directory, name, policy, approved);
            return commits.resumeSingleFile(operation);
        } finally {
            // A failed journal write can have reached disk. Query before deleting; if that query
            // fails, retain the staging file for inspection instead of risking the only copy.
            if (commits.findByOwner(owner).isEmpty()) Files.deleteIfExists(staged);
        }
    }

    private void validateAncestors(DirectoryMergeIndex index, Item item, Path source, Path target,
            Map<String, DirectoryMergeResult> results) throws IOException {
        for (Item parent = index.parent(item); parent != null; parent = index.parent(parent)) {
            if (source != null) requireDirectoryIdentity(source.resolve(parent.relativePath()), parent.source());
            if (results != null) {
                DirectoryMergeResult result = results.get(parent.id());
                if (result == null || result.status() != DirectoryMergeResult.Status.PUBLISHED) {
                    throw new StorageAccessException("Merge destination parent is not prepared yet.");
                }
                requireDirectoryIdentity(storage.resolveVaultCommitTarget(result.targetPath()), result.target());
                continue;
            }
            // Creating/remapping missing directories is a separate journaled orchestration step.
            if (parent.conflict() != Conflict.MERGE) {
                throw new StorageAccessException("Merge destination parent is not prepared yet.");
            }
            requireDirectoryIdentity(target.resolve(parent.relativePath()), parent.target());
        }
    }

    static void requireDirectoryIdentity(Path path, Snapshot expected) throws IOException {
        DirectoryMergePlanner.rejectLinks(path);
        Snapshot actual = DirectoryMergePlanner.snapshotIfPresent(path);
        if (actual == null || actual.kind() != Kind.DIRECTORY || expected.kind() != Kind.DIRECTORY
                || !Objects.equals(expected.fileKey(), actual.fileKey())
                || expected.fileKey() == null && !expected.createdAt().equals(actual.createdAt())) {
            throw new StorageAccessException("Directory merge parent changed. Scan again.");
        }
    }

    private static void requireSnapshot(Path path, Snapshot expected, String message) throws IOException {
        DirectoryMergePlanner.rejectLinks(path);
        if (!Objects.equals(expected, DirectoryMergePlanner.snapshotIfPresent(path))) throw new StorageAccessException(message);
    }

    private static void copySnapshot(Path source, Path staged, long size, StorageProgressListener progress) throws IOException {
        try (var input = FileChannel.open(source, StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS);
                var output = FileChannel.open(staged, StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS)) {
            ByteBuffer buffer = ByteBuffer.allocate(64 * 1024);
            long remaining = size;
            while (remaining > 0) {
                progress.checkCanceled();
                buffer.clear().limit((int) Math.min(buffer.capacity(), remaining));
                int count = input.read(buffer);
                if (count < 0) throw new StorageAccessException("Directory merge source was truncated while copying.");
                buffer.flip();
                while (buffer.hasRemaining()) output.write(buffer);
                remaining -= count;
                progress.onBytesProcessed(count);
            }
            if (input.size() != size) throw new StorageAccessException("Directory merge source size changed while copying.");
            output.force(true);
        }
    }

    private static String join(String parent, String child) { return parent.isEmpty() ? child : parent + "/" + child; }
}
