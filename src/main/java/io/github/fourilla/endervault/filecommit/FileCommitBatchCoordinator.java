package io.github.fourilla.endervault.filecommit;

import io.github.fourilla.endervault.common.StorageAccessException;
import io.github.fourilla.endervault.storage.ArchiveCommitPlan;
import io.github.fourilla.endervault.storage.ArchiveCommitPlanItem;
import io.github.fourilla.endervault.storage.ConflictPolicy;
import io.github.fourilla.endervault.storage.StorageBatchCommitResult;
import io.github.fourilla.endervault.storage.StorageProgressListener;
import io.github.fourilla.endervault.storage.StorageService;
import java.io.IOException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Service;

@Service
public class FileCommitBatchCoordinator {

    private final FileCommitJournalStore journalStore;
    private final FileCommitCoordinator singleFileCoordinator;
    private final StorageService storageService;

    public FileCommitBatchCoordinator(
            FileCommitJournalStore journalStore,
            FileCommitCoordinator singleFileCoordinator,
            StorageService storageService
    ) {
        this.journalStore = journalStore;
        this.singleFileCoordinator = singleFileCoordinator;
        this.storageService = storageService;
    }

    public synchronized StagedBatchCommit commitArchiveExtraction(
            FileCommitOwner owner,
            ArchiveCommitPlan plan,
            ConflictPolicy conflictPolicy,
            StorageProgressListener progressListener
    ) throws IOException {
        Objects.requireNonNull(owner, "owner");
        Objects.requireNonNull(plan, "plan");
        ConflictPolicy policy = conflictPolicy == null ? ConflictPolicy.CANCEL : conflictPolicy;
        if (policy == ConflictPolicy.OVERWRITE) {
            throw new StorageAccessException("Archive batch replacement is not supported.");
        }
        List<BatchPaths> paths = immediatePaths(plan);
        Optional<FileCommitJournalEntry> existing = journalStore.findByOwner(owner);
        if (existing.isPresent() && existing.get().state().phase() == FileCommitPhase.COMPLETED) {
            journalStore.deleteFinished(existing.get().manifest().operationId());
            existing = Optional.empty();
        }
        FileCommitJournalEntry entry = existing.isPresent()
                ? requireMatchingPlan(existing.get(), owner, paths, policy)
                : createJournal(owner, paths, policy, progressListener);
        return resume(entry, paths);
    }

    public synchronized StagedBatchCommit resumeArchiveExtraction(String operationId) throws IOException {
        FileCommitJournalEntry entry = journalStore.load(operationId);
        return resume(entry, recoveryPaths(entry));
    }

    public synchronized Path archiveWorkspace(String operationId) throws IOException {
        FileCommitJournalEntry entry = journalStore.load(operationId);
        List<BatchPaths> paths = recoveryPaths(entry);
        return storageService.archiveStagingWorkspaceFor(paths.get(0).stagedPath());
    }

    public synchronized boolean hasActiveJournal(FileCommitOwner owner) throws IOException {
        return journalStore.findByOwner(owner).isPresent();
    }

    public synchronized void complete(String operationId) throws IOException {
        singleFileCoordinator.complete(operationId);
    }

    public synchronized Set<String> activeArchiveStagingWorkspaces() throws IOException {
        java.util.HashSet<String> workspaces = new java.util.HashSet<>();
        for (FileCommitJournalEntry entry : journalStore.list()) {
            if (entry.manifest().operationType() != FileCommitOperationType.BATCH
                    || entry.manifest().owner().type() != FileCommitOwnerType.ARCHIVE_EXTRACT) {
                continue;
            }
            Path staged = storageService.resolveArchiveStagingCommitPath(
                    entry.manifest().items().get(0).stagingPath()
            );
            workspaces.add(storageService.archiveStagingWorkspaceFor(staged).getFileName().toString());
        }
        return Set.copyOf(workspaces);
    }

    public synchronized boolean referencesArchiveStagingWorkspace(String workspaceName) throws IOException {
        return activeArchiveStagingWorkspaces().contains(workspaceName);
    }

    private FileCommitJournalEntry createJournal(
            FileCommitOwner owner,
            List<BatchPaths> paths,
            ConflictPolicy policy,
            StorageProgressListener progressListener
    ) throws IOException {
        List<FileCommitItem> items = new ArrayList<>(paths.size());
        for (int index = 0; index < paths.size(); index++) {
            BatchPaths path = paths.get(index);
            if (Files.exists(path.targetPath(), LinkOption.NOFOLLOW_LINKS)) {
                throw new FileAlreadyExistsException(path.targetStoragePath());
            }
            items.add(new FileCommitItem(
                    index,
                    path.stagingStoragePath(),
                    path.targetStoragePath(),
                    FileCommitFingerprints.tree(path.stagedPath(), progressListener),
                    null
            ));
        }
        FileCommitManifest manifest = new FileCommitManifest(
                FileCommitManifest.CURRENT_SCHEMA_VERSION,
                UUID.randomUUID().toString(),
                owner,
                FileCommitOperationType.BATCH,
                policy,
                items,
                Instant.now()
        );
        return journalStore.create(manifest);
    }

    private StagedBatchCommit resume(FileCommitJournalEntry initial, List<BatchPaths> paths) throws IOException {
        FileCommitJournalEntry entry = initial;
        if (entry.state().phase() == FileCommitPhase.NEEDS_REVIEW) {
            throw recoveryRequired(entry, "Archive commit is waiting for manual recovery.");
        }
        if (entry.state().phase() == FileCommitPhase.ABORTED) {
            throw recoveryRequired(entry, "Archive commit was aborted before completion.");
        }
        if (entry.state().phase() == FileCommitPhase.PREPARED) {
            entry = update(entry, FileCommitPhase.COMMITTING, 0, "Committing archive batch.");
        }
        if (entry.state().phase() == FileCommitPhase.COMMITTING) {
            while (entry.state().nextItemIndex() < paths.size()) {
                int index = entry.state().nextItemIndex();
                reconcileItem(entry, paths.get(index), entry.manifest().items().get(index));
                entry = update(entry, FileCommitPhase.COMMITTING, index + 1, "Committed archive batch item.");
            }
            entry = update(entry, FileCommitPhase.FILES_MOVED, paths.size(), "Archive batch committed.");
        }
        if (entry.state().phase() == FileCommitPhase.FILES_MOVED) {
            requireCommittedTargets(entry, paths);
            entry = update(entry, FileCommitPhase.APPLYING_METADATA, paths.size(), "Applying archive metadata.");
        }
        if (entry.state().phase() != FileCommitPhase.APPLYING_METADATA) {
            throw recoveryRequired(entry, "Archive commit is not ready to apply metadata.");
        }
        requireCommittedTargets(entry, paths);
        return new StagedBatchCommit(
                entry.manifest().operationId(),
                storageService.archiveStagingWorkspaceFor(paths.get(0).stagedPath()),
                new StorageBatchCommitResult(paths.stream().map(BatchPaths::targetStoragePath).toList())
        );
    }

    private void reconcileItem(
            FileCommitJournalEntry entry,
            BatchPaths paths,
            FileCommitItem item
    ) throws IOException {
        boolean sourceExists = Files.exists(paths.stagedPath(), LinkOption.NOFOLLOW_LINKS);
        boolean targetExists = Files.exists(paths.targetPath(), LinkOption.NOFOLLOW_LINKS);
        if (sourceExists && targetExists) {
            throw markRecoveryRequired(entry, "Archive source and target both exist for one batch item.");
        }
        if (sourceExists) {
            requireFingerprint(item.stagingFingerprint(), paths.stagedPath(), entry, "Archive staging item changed.");
            try {
                storageService.commitArchiveStagedEntryNoReplace(
                        paths.stagedPath(), paths.targetStoragePath()
                );
            } catch (FileAlreadyExistsException ex) {
                throw markRecoveryRequired(entry, "Archive target was occupied during batch commit.");
            }
        } else if (!targetExists) {
            throw markRecoveryRequired(entry, "Archive source and target are both missing.");
        }
        requireFingerprint(item.stagingFingerprint(), paths.targetPath(), entry, "Archive target is incomplete.");
    }

    private void requireCommittedTargets(FileCommitJournalEntry entry, List<BatchPaths> paths) throws IOException {
        for (int index = 0; index < paths.size(); index++) {
            requireFingerprint(
                    entry.manifest().items().get(index).stagingFingerprint(),
                    paths.get(index).targetPath(),
                    entry,
                    "Committed archive target is missing or changed."
            );
        }
    }

    private void requireFingerprint(
            FileCommitFingerprint expected,
            Path path,
            FileCommitJournalEntry entry,
            String detail
    ) throws IOException {
        if (!FileCommitFingerprints.matchesTree(expected, path)) {
            throw markRecoveryRequired(entry, detail);
        }
    }

    private List<BatchPaths> immediatePaths(ArchiveCommitPlan plan) throws IOException {
        List<BatchPaths> paths = new ArrayList<>(plan.items().size());
        for (ArchiveCommitPlanItem item : plan.items()) {
            String stagingPath = storageService.archiveStagingCommitPath(item.stagedPath());
            Path staged = storageService.resolveArchiveStagingCommitPath(stagingPath);
            Path target = storageService.resolveVaultCommitTarget(item.targetPath());
            paths.add(new BatchPaths(staged, target, stagingPath, item.targetPath()));
        }
        requireOneWorkspace(paths, plan.workspace());
        return List.copyOf(paths);
    }

    private List<BatchPaths> recoveryPaths(FileCommitJournalEntry entry) throws IOException {
        if (entry.manifest().operationType() != FileCommitOperationType.BATCH
                || entry.manifest().owner().type() != FileCommitOwnerType.ARCHIVE_EXTRACT) {
            throw rejectRecoveryPlan(entry, "Stored file commit is not an archive extraction batch.");
        }
        List<BatchPaths> paths = new ArrayList<>(entry.manifest().items().size());
        for (FileCommitItem item : entry.manifest().items()) {
            try {
                Path staged = storageService.resolveArchiveStagingCommitPath(item.stagingPath());
                Path target = storageService.resolveVaultCommitTarget(item.targetPath());
                paths.add(new BatchPaths(staged, target, item.stagingPath(), item.targetPath()));
            } catch (IOException | RuntimeException ex) {
                throw rejectRecoveryPlan(entry, "Stored archive commit path is invalid.");
            }
        }
        requireMatchingPlan(entry, entry.manifest().owner(), paths, entry.manifest().conflictPolicy());
        requireOneWorkspace(paths, storageService.archiveStagingWorkspaceFor(paths.get(0).stagedPath()));
        return List.copyOf(paths);
    }

    private FileCommitJournalEntry requireMatchingPlan(
            FileCommitJournalEntry entry,
            FileCommitOwner owner,
            List<BatchPaths> paths,
            ConflictPolicy policy
    ) throws IOException {
        FileCommitManifest manifest = entry.manifest();
        boolean matches = manifest.owner().equals(owner)
                && manifest.operationType() == FileCommitOperationType.BATCH
                && manifest.conflictPolicy() == policy
                && manifest.items().size() == paths.size();
        if (matches) {
            for (int index = 0; index < paths.size(); index++) {
                FileCommitItem item = manifest.items().get(index);
                BatchPaths path = paths.get(index);
                if (!item.stagingPath().equals(path.stagingStoragePath())
                        || !item.targetPath().equals(path.targetStoragePath())) {
                    matches = false;
                    break;
                }
            }
        }
        if (!matches) {
            throw rejectRecoveryPlan(entry, "Stored archive commit plan does not match its owner state.");
        }
        return entry;
    }

    private void requireOneWorkspace(List<BatchPaths> paths, Path expectedWorkspace) throws IOException {
        Path normalizedExpected = expectedWorkspace.toAbsolutePath().normalize();
        for (BatchPaths path : paths) {
            Path workspace = storageService.archiveStagingWorkspaceFor(path.stagedPath());
            if (!workspace.equals(normalizedExpected)) {
                throw new StorageAccessException("Archive batch items must belong to one extraction workspace.");
            }
        }
    }

    private FileCommitJournalEntry update(
            FileCommitJournalEntry entry,
            FileCommitPhase phase,
            int nextItemIndex,
            String detail
    ) throws IOException {
        return journalStore.updateState(new FileCommitJournalState(
                entry.manifest().operationId(), phase, nextItemIndex, detail, Instant.now()
        ));
    }

    private FileCommitRecoveryRequiredException markRecoveryRequired(
            FileCommitJournalEntry entry,
            String detail
    ) throws IOException {
        FileCommitJournalEntry marked = update(
                entry, FileCommitPhase.NEEDS_REVIEW, entry.state().nextItemIndex(), detail
        );
        return recoveryRequired(marked, detail);
    }

    private FileCommitRecoveryRequiredException rejectRecoveryPlan(
            FileCommitJournalEntry entry,
            String detail
    ) throws IOException {
        FileCommitPhase phase = entry.state().phase();
        if (phase == FileCommitPhase.COMPLETED || phase == FileCommitPhase.ABORTED) {
            return recoveryRequired(entry, detail);
        }
        return markRecoveryRequired(entry, detail);
    }

    private FileCommitRecoveryRequiredException recoveryRequired(FileCommitJournalEntry entry, String detail) {
        return new FileCommitRecoveryRequiredException(
                detail + " Operation " + entry.manifest().operationId() + " requires inspection."
        );
    }

    public record StagedBatchCommit(
            String operationId,
            Path workspace,
            StorageBatchCommitResult result
    ) {
    }

    private record BatchPaths(
            Path stagedPath,
            Path targetPath,
            String stagingStoragePath,
            String targetStoragePath
    ) {
    }

}
