package io.github.fourilla.endervault.directorytransfer;

import static io.github.fourilla.endervault.directorytransfer.DirectoryTransferPlan.*;

import io.github.fourilla.endervault.common.StorageAccessException;
import io.github.fourilla.endervault.filecommit.FileCommitCoordinator;
import io.github.fourilla.endervault.filecommit.FileCommitOwner;
import io.github.fourilla.endervault.filecommit.FileCommitOwnerType;
import io.github.fourilla.endervault.filecommit.FileCommitRecoveryRequiredException;
import io.github.fourilla.endervault.pending.PendingFileDecision;
import io.github.fourilla.endervault.storage.ConflictPolicy;
import io.github.fourilla.endervault.storage.StorageProgressListener;
import io.github.fourilla.endervault.storage.StorageService;
import io.github.fourilla.endervault.temporary.TemporaryArtifactRegistry;
import io.github.fourilla.endervault.temporary.TemporaryArtifactType;
import java.io.IOException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import org.springframework.stereotype.Service;

/** Publishes a reviewed tree, preserving all originals until the separate lifecycle phase. */
@Service
public class DirectoryTransferExecution {
    private final DirectoryTransferReviewStore reviews;
    private final DirectoryTransferFilePublisher files;
    private final FileCommitCoordinator commits;
    private final StorageService storage;
    private final TemporaryArtifactRegistry registry;

    public DirectoryTransferExecution(DirectoryTransferReviewStore reviews, DirectoryTransferFilePublisher files,
            FileCommitCoordinator commits, StorageService storage, TemporaryArtifactRegistry registry) {
        this.reviews = reviews;
        this.files = files;
        this.commits = commits;
        this.storage = storage;
        this.registry = registry;
    }

    public synchronized Map<String, DirectoryTransferResult> publishTransfer(String id, long revision,
            StorageProgressListener listener) throws IOException {
        var review = reviews.require(id);
        if (review.plan().operation() == Operation.PENDING) throw new StorageAccessException("Pending owner is required.");
        return execute(reviews.freeze(id, revision), null, listener);
    }

    /** Pending must be supplied by its server-side owner, never deserialized from a request. */
    public synchronized Map<String, DirectoryTransferResult> publishPending(String id, long revision,
            PendingFileDecision pending, StorageProgressListener listener) throws IOException {
        var review = reviews.require(id);
        if (pending == null || !pending.directory() || review.plan().operation() != Operation.PENDING
                || !review.plan().sourceReference().equals(pending.id())
                || !review.plan().destinationPath().equals(join(storage.normalizeVaultDirectory(pending.destinationPath()),
                        pending.originalFilename()))) throw new StorageAccessException("Pending owner changed.");
        return execute(reviews.freeze(id, revision), pending, listener);
    }

    private Map<String, DirectoryTransferResult> execute(DirectoryTransferReview review, PendingFileDecision pending,
            StorageProgressListener listener) throws IOException {
        var progress = listener == null ? StorageProgressListener.NOOP : listener;
        var results = new HashMap<>(reviews.results(review));
        var index = new DirectoryTransferIndex(review.plan());
        Path source;
        try {
            source = pending == null ? storage.resolveVaultDirectory(review.plan().sourceReference())
                    : storage.resolveFileStagingFile(pending.stagingFilename());
        } catch (NoSuchFileException ex) { source = null; }
        Path destination = storage.resolveVaultCommitTarget(review.plan().destinationPath());
        if (source != null) DirectoryTransferPlanner.rejectLinks(source);
        DirectoryTransferPlanner.rejectLinks(destination);
        if (source != null && (source.startsWith(destination) || destination.startsWith(source))) throw new StorageAccessException("Merge paths overlap.");
        // Parents always precede descendants, independently of the display's natural sort order.
        var ordered = review.plan().items().stream().sorted(Comparator.comparingInt(item -> depth(item.relativePath()))).toList();
        for (Item item : ordered) {
            progress.checkCanceled();
            if (results.containsKey(item.id())) {
                finishDirectoryJournal(review, item, results.get(item.id()));
                continue;
            }
            DirectoryTransferResult result;
            var parent = index.parent(item);
            var parentResult = parent == null ? null : results.get(parent.id());
            if (parent != null && (parentResult == null || parentResult.status() != DirectoryTransferResult.Status.PUBLISHED)) {
                var status = parentResult != null && parentResult.status() == DirectoryTransferResult.Status.SKIPPED
                        ? DirectoryTransferResult.Status.SKIPPED
                        : parentResult != null && parentResult.status() == DirectoryTransferResult.Status.DISCARD_APPROVED
                                ? DirectoryTransferResult.Status.DISCARD_APPROVED : DirectoryTransferResult.Status.NEEDS_REVIEW;
                result = new DirectoryTransferResult(item.id(), status, null, null, null, "Parent was not published.");
            } else if (review.choices().get(item.id()) == DirectoryTransferReview.Choice.SKIP) {
                result = new DirectoryTransferResult(item.id(), DirectoryTransferResult.Status.SKIPPED, null, null, null, null);
            } else if (review.choices().get(item.id()) == DirectoryTransferReview.Choice.DISCARD_UPLOAD) {
                result = new DirectoryTransferResult(item.id(), DirectoryTransferResult.Status.DISCARD_APPROVED,
                        null, null, null, "Explicit upload discard approved; source cleanup is separate.");
            } else {
                try {
                    var existing = commits.findByOwner(new FileCommitOwner(FileCommitOwnerType.DIRECTORY_MERGE,
                            review.plan().id() + ":" + item.id()));
                    boolean published = false;
                    if (existing.isPresent()) {
                        DirectoryTransferJournalGuard.requireMatches(existing.get(), review, item, index.targetPath(item, results));
                        published = DirectoryTransferJournalGuard.alreadyPublished(existing.get(), commits);
                    }
                    if (!published && source == null) throw new StorageAccessException("Directory merge source is unavailable.");
                    validateParents(index, item, published ? null : source, results);
                    if (item.source().kind() == Kind.DIRECTORY) {
                        result = publishDirectory(review, index, item, published ? null : source, results);
                    } else {
                        var commit = files.publishPreparedFile(review, item.id(), pending, progress, results, index);
                        var snapshot = DirectoryTransferPlanner.snapshot(storage.resolveVaultCommitTarget(commit.file().path()));
                        var expected = commits.singleFilePlan(commit.operationId()).stagingFingerprint();
                        if (snapshot.kind() != Kind.FILE || snapshot.size() != expected.size()
                                || !snapshot.modifiedAt().equals(expected.modifiedAt())
                                || !Objects.equals(snapshot.fileKey(), expected.fileKey())) {
                            throw new StorageAccessException("Published merge file changed before result recording.");
                        }
                        result = new DirectoryTransferResult(item.id(), DirectoryTransferResult.Status.PUBLISHED,
                                commit.file().path(), snapshot,
                                commit.operationId(), null);
                    }
                } catch (StorageAccessException | FileCommitRecoveryRequiredException
                        | FileAlreadyExistsException | NoSuchFileException ex) {
                    result = new DirectoryTransferResult(item.id(), DirectoryTransferResult.Status.NEEDS_REVIEW,
                            null, null, null, ex.getMessage());
                }
            }
            reviews.recordResult(review, item, result);
            results.put(item.id(), result);
            finishDirectoryJournal(review, item, result);
            progress.onItemProcessed();
        }
        return Map.copyOf(results);
    }

    private DirectoryTransferResult publishDirectory(DirectoryTransferReview review, DirectoryTransferIndex index, Item item, Path source,
            Map<String, DirectoryTransferResult> results) throws IOException {
        if (source != null) DirectoryTransferFilePublisher.requireDirectoryIdentity(source.resolve(item.relativePath()), item.source());
        String path = index.targetPath(item, results);
        var owner = new FileCommitOwner(FileCommitOwnerType.DIRECTORY_MERGE, review.plan().id() + ":" + item.id());
        var existing = commits.findByOwner(owner);
        if (existing.isPresent()) {
            DirectoryTransferJournalGuard.requireMatches(existing.get(), review, item, path);
            var commit = commits.resumeSingleFile(existing.get().manifest().operationId());
            return publishedDirectory(item, commit.file().path(), commit.operationId());
        }
        Path target = storage.resolveVaultCommitTarget(path);
        DirectoryTransferPlanner.rejectLinks(target);
        Snapshot observed = DirectoryTransferPlanner.snapshotIfPresent(target);
        if (item.conflict() == Conflict.MERGE) {
            DirectoryTransferFilePublisher.requireDirectoryIdentity(target, item.target());
            return publishedDirectory(item, path, null);
        }
        if (!Objects.equals(observed, item.target())) throw new StorageAccessException("Directory destination changed. Review it again.");
        int slash = path.lastIndexOf('/');
        String directory = slash < 0 ? "" : path.substring(0, slash);
        String name = slash < 0 ? path : path.substring(slash + 1);
        boolean keepBoth = review.choices().get(item.id()) == DirectoryTransferReview.Choice.KEEP_BOTH;
        if (keepBoth) name = storage.resolveAvailableVaultEntryName(directory, name, true);
        Path staged = storage.resolveFileStagingFile("merge-directory-" + item.id());
        try (var active = registry.register(staged, TemporaryArtifactType.FILE_COPY, owner.id())) {
            DirectoryTransferPlanner.rejectLinks(staged);
            if (!Files.exists(staged, LinkOption.NOFOLLOW_LINKS)) Files.createDirectory(staged);
            if (!Files.isDirectory(staged, LinkOption.NOFOLLOW_LINKS)) throw new StorageAccessException("Unsafe merge directory staging.");
            try (var children = Files.list(staged)) {
                if (children.findAny().isPresent()) throw new StorageAccessException("Merge directory staging is not empty.");
            }
            var commit = commits.commitSingleDirectory(owner, staged, directory, name,
                    keepBoth ? ConflictPolicy.RENAME : ConflictPolicy.CANCEL);
            return publishedDirectory(item, commit.file().path(), commit.operationId());
        }
    }

    private DirectoryTransferResult publishedDirectory(Item item, String path, String commit) throws IOException {
        return new DirectoryTransferResult(item.id(), DirectoryTransferResult.Status.PUBLISHED, path,
                DirectoryTransferPlanner.snapshot(storage.resolveVaultCommitTarget(path)), commit, null);
    }

    private void finishDirectoryJournal(DirectoryTransferReview review, Item item, DirectoryTransferResult result) throws IOException {
        // Persist ownership before removing the journal: descendants will change this directory's fingerprint.
        if (item.source().kind() == Kind.DIRECTORY && result.commitId() != null) {
            var existing = commits.findByOwner(new FileCommitOwner(FileCommitOwnerType.DIRECTORY_MERGE,
                    review.plan().id() + ":" + item.id()));
            if (existing.isPresent()) {
                if (!existing.get().manifest().operationId().equals(result.commitId())) throw new StorageAccessException("Merge journal changed.");
                commits.complete(result.commitId());
            }
        }
    }

    private void validateParents(DirectoryTransferIndex index, Item item, Path source,
            Map<String, DirectoryTransferResult> results) throws IOException {
        for (Item ancestor = index.parent(item); ancestor != null; ancestor = index.parent(ancestor)) {
            if (source != null) DirectoryTransferFilePublisher.requireDirectoryIdentity(source.resolve(ancestor.relativePath()), ancestor.source());
            var result = results.get(ancestor.id());
            if (result == null || result.status() != DirectoryTransferResult.Status.PUBLISHED) throw new StorageAccessException("Merge parent is unavailable.");
            DirectoryTransferFilePublisher.requireDirectoryIdentity(storage.resolveVaultCommitTarget(result.targetPath()), result.target());
        }
    }

    private static int depth(String path) { return path.isEmpty() ? 0 : path.split("/").length; }
    private static String join(String parent, String child) { return parent.isEmpty() ? child : parent + "/" + child; }
}
