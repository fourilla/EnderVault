package io.github.fourilla.endervault.directorymerge;

import io.github.fourilla.endervault.common.StorageAccessException;
import io.github.fourilla.endervault.filecommit.*;
import io.github.fourilla.endervault.storage.ConflictPolicy;
import java.util.Objects;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;

final class DirectoryMergeJournalGuard {
    private DirectoryMergeJournalGuard() {}

    static void requireMatches(FileCommitJournalEntry entry, DirectoryMergeReview review,
            DirectoryMergePlan.Item item, String targetPath) {
        var manifest = entry.manifest();
        var choice = review.choices().get(item.id());
        var policy = choice == DirectoryMergeReview.Choice.KEEP_BOTH ? ConflictPolicy.RENAME
                : choice == DirectoryMergeReview.Choice.OVERWRITE ? ConflictPolicy.OVERWRITE : ConflictPolicy.CANCEL;
        boolean directory = item.source().kind() == DirectoryMergePlan.Kind.DIRECTORY;
        var expectedTarget = policy == ConflictPolicy.OVERWRITE ? new FileCommitFingerprint(item.target().size(),
                item.target().modifiedAt(), item.target().fileKey()) : null;
        if (!manifest.owner().equals(new FileCommitOwner(FileCommitOwnerType.DIRECTORY_MERGE,
                review.plan().id() + ":" + item.id())) || manifest.items().size() != 1
                || manifest.operationType() != (directory ? FileCommitOperationType.SINGLE_DIRECTORY : FileCommitOperationType.SINGLE_FILE)
                || manifest.conflictPolicy() != policy) throw mismatch();
        var stored = manifest.items().getFirst();
        if (stored.stagingFingerprint().directory() != directory || !Objects.equals(stored.targetSnapshot(), expectedTarget)
                || (policy == ConflictPolicy.RENAME ? !parent(stored.targetPath()).equals(parent(targetPath))
                        : !stored.targetPath().equals(targetPath))) throw mismatch();
    }

    static boolean alreadyPublished(FileCommitJournalEntry entry, FileCommitCoordinator commits) throws IOException {
        var plan = commits.singleFilePlan(entry.manifest().operationId());
        return plan.phase() == FileCommitPhase.FILES_MOVED || plan.phase() == FileCommitPhase.APPLYING_METADATA
                || plan.phase() == FileCommitPhase.COMMITTING && !Files.exists(plan.stagedFile(), LinkOption.NOFOLLOW_LINKS);
    }

    private static String parent(String path) { int slash = path.lastIndexOf('/'); return slash < 0 ? "" : path.substring(0, slash); }
    private static StorageAccessException mismatch() { return new StorageAccessException("Merge journal does not match the approved item."); }
}
