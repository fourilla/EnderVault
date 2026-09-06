package io.github.fourilla.endervault.filecommit;

import io.github.fourilla.endervault.common.StorageAccessException;
import io.github.fourilla.endervault.config.NasProperties;
import io.github.fourilla.endervault.storage.ConflictPolicy;
import io.github.fourilla.endervault.storage.StorageService;
import java.io.IOException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;

@Service
public class FileCommitCoordinator {

    private static final String FILE_STAGING_DIRECTORY = "file-staging";

    private final FileCommitJournalStore journalStore;
    private final StorageService storageService;
    private final Path storageRoot;
    private final String metadataDirectory;

    public FileCommitCoordinator(
            FileCommitJournalStore journalStore,
            StorageService storageService,
            NasProperties nasProperties
    ) {
        this.journalStore = journalStore;
        this.storageService = storageService;
        this.storageRoot = nasProperties.getStorage().getRoot().toAbsolutePath().normalize();
        this.metadataDirectory = requireDirectoryName(nasProperties.getStorage().getMetadataDirectory());
    }

    public synchronized StagedFileCommit commitSingleFile(
            FileCommitOwner owner,
            Path stagedFile,
            String destinationPath,
            String filename
    ) throws IOException {
        return commitSingleFile(owner, stagedFile, destinationPath, filename, ConflictPolicy.CANCEL);
    }

    public synchronized StagedFileCommit commitSingleFile(
            FileCommitOwner owner,
            Path stagedFile,
            String destinationPath,
            String filename,
            ConflictPolicy conflictPolicy
    ) throws IOException {
        Objects.requireNonNull(owner, "owner");
        Objects.requireNonNull(conflictPolicy, "conflictPolicy");
        return commitSingleEntry(owner, stagedFile, destinationPath, filename, conflictPolicy, false);
    }

    /**
     * Publishes one direct file-staging child using a same-filesystem atomic move, never a merge.
     * The owner must stop staging writers and complete owner metadata before calling complete().
     * Java's precheck plus ATOMIC_MOVE cannot guarantee no replacement against external writers
     * racing the destination, or exclude source mutations/same-directory renames after fingerprinting.
     * DIRECTORY_UPLOAD recovery and completion belong to the upload group service.
     */
    public synchronized StagedFileCommit commitSingleDirectory(
            FileCommitOwner owner, Path stagedPath, String destinationPath, String name
    ) throws IOException {
        return commitSingleDirectory(owner, stagedPath, destinationPath, name, ConflictPolicy.CANCEL);
    }

    // RENAME records a pending KEEP_BOTH choice; the caller supplies the already selected name.
    public synchronized StagedFileCommit commitSingleDirectory(
            FileCommitOwner owner, Path stagedPath, String destinationPath, String name, ConflictPolicy policy
    ) throws IOException {
        Objects.requireNonNull(owner, "owner");
        if (policy != ConflictPolicy.CANCEL && policy != ConflictPolicy.RENAME) {
            throw new StorageAccessException("Directory replacement is not supported.");
        }
        return commitSingleEntry(owner, stagedPath, destinationPath, name, policy, true);
    }

    private StagedFileCommit commitSingleEntry(FileCommitOwner owner, Path stagedFile,
            String destinationPath, String filename, ConflictPolicy conflictPolicy, boolean directory)
            throws IOException {
        CommitPaths paths = commitPaths(stagedFile, destinationPath, filename);
        Optional<FileCommitJournalEntry> existing = journalStore.findByOwner(owner);
        if (existing.isPresent() && existing.get().state().phase() == FileCommitPhase.COMPLETED) {
            journalStore.deleteFinished(existing.get().manifest().operationId());
            existing = Optional.empty();
        }

        FileCommitJournalEntry entry = existing.isPresent()
                ? requireMatchingPlan(existing.get(), owner, paths, conflictPolicy, directory)
                : createJournal(owner, paths, conflictPolicy, directory);
        return resume(entry, paths);
    }

    public synchronized Optional<FileCommitJournalEntry> findByOwner(FileCommitOwner owner) throws IOException {
        return journalStore.findByOwner(owner);
    }

    public synchronized boolean hasActiveJournal(FileCommitOwner owner) throws IOException {
        return journalStore.findByOwner(owner).isPresent();
    }

    public synchronized List<FileCommitJournalInspection> inspectJournals() throws IOException {
        return journalStore.inspectJournals();
    }

    public synchronized SingleFileCommitPlan singleFilePlan(String operationId) throws IOException {
        FileCommitJournalEntry entry = journalStore.load(operationId);
        CommitPaths paths = recoveryPaths(entry);
        return new SingleFileCommitPlan(
                entry.manifest().operationId(),
                entry.manifest().owner(),
                entry.state().phase(),
                paths.stagedFile(),
                paths.destinationPath(),
                paths.filename(),
                entry.manifest().conflictPolicy(),
                entry.manifest().items().get(0).stagingFingerprint(),
                entry.manifest().items().get(0).targetSnapshot()
        );
    }

    public synchronized StagedFileCommit resumeSingleFile(String operationId) throws IOException {
        FileCommitJournalEntry entry = journalStore.load(operationId);
        return resume(entry, recoveryPaths(entry));
    }

    public synchronized Set<String> activeStagingFilenames() throws IOException {
        String stagingPrefix = metadataDirectory + "/" + FILE_STAGING_DIRECTORY + "/";
        List<FileCommitJournalEntry> entries = journalStore.list();
        Set<String> filenames = entries.stream()
                .flatMap(entry -> entry.manifest().items().stream())
                .map(FileCommitItem::stagingPath)
                .filter(path -> path.startsWith(stagingPrefix))
                .map(path -> path.substring(stagingPrefix.length()))
                .filter(filename -> !filename.isBlank() && !filename.contains("/"))
                .collect(Collectors.toSet());
        entries.stream()
                .filter(entry -> entry.manifest().conflictPolicy() == ConflictPolicy.OVERWRITE)
                .map(entry -> replacementBackupFilename(entry.manifest().operationId()))
                .forEach(filenames::add);
        return Set.copyOf(filenames);
    }

    public synchronized boolean referencesStagingFile(String filename) throws IOException {
        String stagingFilename = storageService.fileStagingFilename(
                storageService.resolveFileStagingFile(filename)
        );
        return activeStagingFilenames().contains(stagingFilename);
    }

    public synchronized void complete(String operationId) throws IOException {
        FileCommitJournalEntry entry = journalStore.load(operationId);
        FileCommitPhase phase = entry.state().phase();
        if (phase == FileCommitPhase.APPLYING_METADATA) {
            entry = update(entry, FileCommitPhase.COMPLETED, entry.manifest().items().size(), null);
        } else if (phase != FileCommitPhase.COMPLETED) {
            throw new IllegalStateException("File commit metadata is not ready to complete.");
        }
        journalStore.deleteFinished(entry.manifest().operationId());
    }

    public synchronized void completeForOwnerIfPresent(FileCommitOwner owner) throws IOException {
        Optional<FileCommitJournalEntry> existing = journalStore.findByOwner(owner);
        if (existing.isPresent()) {
            complete(existing.get().manifest().operationId());
        }
    }

    public synchronized void completeConflict(String operationId) throws IOException {
        FileCommitJournalEntry entry = journalStore.load(operationId);
        if (entry.state().phase() != FileCommitPhase.ABORTED) {
            throw new IllegalStateException("File commit is not waiting for conflict handoff.");
        }
        journalStore.deleteFinished(operationId);
    }

    public synchronized void completeConflictForOwnerIfPresent(FileCommitOwner owner) throws IOException {
        Optional<FileCommitJournalEntry> existing = journalStore.findByOwner(owner);
        if (existing.isPresent() && existing.get().state().phase() == FileCommitPhase.ABORTED) {
            completeConflict(existing.get().manifest().operationId());
        }
    }

    private FileCommitJournalEntry createJournal(
            FileCommitOwner owner,
            CommitPaths paths,
            ConflictPolicy conflictPolicy,
            boolean directory
    ) throws IOException {
        if (directory) {
            if (!Files.isDirectory(paths.stagedFile(), LinkOption.NOFOLLOW_LINKS)
                    || Files.isSymbolicLink(paths.stagedFile())) {
                throw new StorageAccessException("Directory commit staging data is unavailable.");
            }
        } else {
            requireRegularFile(paths.stagedFile(), "File commit staging data is unavailable.");
        }
        FileCommitFingerprint targetSnapshot = null;
        if (conflictPolicy == ConflictPolicy.OVERWRITE) {
            requireRegularFile(paths.targetFile(), "File replacement target is unavailable.");
            targetSnapshot = fingerprint(paths.targetFile());
        }
        FileCommitFingerprint stagingFingerprint = directory
                ? FileCommitFingerprints.tree(paths.stagedFile(), null) : fingerprint(paths.stagedFile());
        if (stagingFingerprint.directory() != directory) {
            throw new StorageAccessException("Staging entry kind changed before commit.");
        }
        FileCommitManifest manifest = new FileCommitManifest(
                FileCommitManifest.CURRENT_SCHEMA_VERSION,
                UUID.randomUUID().toString(),
                owner,
                directory ? FileCommitOperationType.SINGLE_DIRECTORY : FileCommitOperationType.SINGLE_FILE,
                conflictPolicy,
                List.of(new FileCommitItem(
                        0,
                        paths.stagingPath(),
                        paths.targetPath(),
                        stagingFingerprint,
                        targetSnapshot
                )),
                Instant.now()
        );
        return journalStore.create(manifest);
    }

    private StagedFileCommit resume(FileCommitJournalEntry initial, CommitPaths paths) throws IOException {
        FileCommitJournalEntry entry = initial;
        if (entry.state().phase() == FileCommitPhase.ABORTED) {
            throw conflict(entry, paths.targetFile());
        }
        if (entry.state().phase() == FileCommitPhase.NEEDS_REVIEW) {
            throw recoveryRequired(entry, "File commit is waiting for manual recovery.");
        }
        if (entry.state().phase() == FileCommitPhase.PREPARED) {
            entry = update(entry, FileCommitPhase.COMMITTING, 0, "Committing staged file.");
        }
        if (entry.state().phase() == FileCommitPhase.COMMITTING) {
            entry = entry.manifest().conflictPolicy() == ConflictPolicy.OVERWRITE
                    ? reconcileOverwrite(entry, paths)
                    : reconcileNoReplace(entry, paths);
        }
        if (entry.state().phase() == FileCommitPhase.FILES_MOVED) {
            requireCommittedTarget(entry, paths.targetFile());
            cleanupReplacementBackup(entry);
            entry = update(entry, FileCommitPhase.APPLYING_METADATA, 1, "Applying owner metadata.");
        }
        if (entry.state().phase() != FileCommitPhase.APPLYING_METADATA) {
            throw recoveryRequired(entry, "File commit is not ready to apply owner metadata.");
        }
        requireCommittedTarget(entry, paths.targetFile());
        return new StagedFileCommit(
                entry.manifest().operationId(),
                new StorageService.CommittedVaultFile(
                        paths.targetFile().getFileName().toString(),
                        paths.targetPath()
                )
        );
    }

    private FileCommitJournalEntry reconcileNoReplace(
            FileCommitJournalEntry entry,
            CommitPaths paths
    ) throws IOException {
        FileCommitFingerprint expected = entry.manifest().items().get(0).stagingFingerprint();
        boolean sourceExists = Files.exists(paths.stagedFile(), LinkOption.NOFOLLOW_LINKS);
        boolean targetExists = Files.exists(paths.targetFile(), LinkOption.NOFOLLOW_LINKS);

        if (sourceExists && targetExists) {
            if (expected.directory()) {
                requireFingerprint(expected, paths.stagedFile(), entry, "Staging directory changed before commit.");
                throw conflict(abort(entry, "Destination was occupied before commit completed."), paths.targetFile());
            }
            requireRegularFile(paths.stagedFile(), "File commit staging data is unsafe.");
            requireRegularFile(paths.targetFile(), "File commit target is unsafe.");
            if (!Files.isSameFile(paths.stagedFile(), paths.targetFile())) {
                FileCommitJournalEntry aborted = abort(entry, "Destination was occupied before commit completed.");
                throw conflict(aborted, paths.targetFile());
            }
            requireFingerprint(expected, paths.stagedFile(), entry, "Staging data changed before commit.");
            storageService.commitStagedRegularFileNoReplace(
                    paths.stagedFile(), paths.destinationPath(), paths.filename()
            );
        } else if (sourceExists) {
            requireFingerprint(expected, paths.stagedFile(), entry, "Staging data changed before commit.");
            try {
                if (expected.directory()) {
                    storageService.commitStagedDirectoryNoReplace(
                            paths.stagedFile(), paths.destinationPath(), paths.filename());
                } else {
                    storageService.commitStagedRegularFileNoReplace(
                            paths.stagedFile(), paths.destinationPath(), paths.filename());
                }
            } catch (FileAlreadyExistsException ex) {
                FileCommitJournalEntry aborted = abort(entry, "Destination was occupied before commit completed.");
                throw conflict(aborted, paths.targetFile());
            }
        } else if (!targetExists) {
            throw markRecoveryRequired(entry, "Both staging data and destination are missing.");
        }

        requireFingerprint(expected, paths.targetFile(), entry, "Committed target does not match staging data.");
        return update(entry, FileCommitPhase.FILES_MOVED, 1, "Staged file committed.");
    }

    private FileCommitJournalEntry reconcileOverwrite(
            FileCommitJournalEntry entry,
            CommitPaths paths
    ) throws IOException {
        FileCommitItem item = entry.manifest().items().get(0);
        FileCommitFingerprint stagedFingerprint = item.stagingFingerprint();
        FileCommitFingerprint targetSnapshot = item.targetSnapshot();
        if (targetSnapshot == null) {
            throw markRecoveryRequired(entry, "File replacement target snapshot is missing.");
        }

        Path backup = replacementBackup(entry.manifest().operationId());
        boolean sourceExists = Files.exists(paths.stagedFile(), LinkOption.NOFOLLOW_LINKS);
        boolean targetExists = Files.exists(paths.targetFile(), LinkOption.NOFOLLOW_LINKS);
        boolean backupExists = Files.exists(backup, LinkOption.NOFOLLOW_LINKS);
        if (backupExists) {
            requireFingerprint(targetSnapshot, backup, entry, "File replacement backup changed.");
        }

        if (targetExists && matchesFingerprint(stagedFingerprint, paths.targetFile())) {
            if (sourceExists) {
                requireFingerprint(stagedFingerprint, paths.stagedFile(), entry, "Staging data changed.");
                if (!Files.isSameFile(paths.stagedFile(), paths.targetFile())) {
                    throw markRecoveryRequired(entry, "Replacement target and staging data are ambiguous.");
                }
                storageService.commitStagedRegularFileNoReplace(
                        paths.stagedFile(), paths.destinationPath(), paths.filename()
                );
            }
            return update(entry, FileCommitPhase.FILES_MOVED, 1, "Staged file replaced target.");
        }

        if (!sourceExists || !targetExists) {
            throw markRecoveryRequired(entry, "Replacement staging data or target is missing.");
        }
        requireFingerprint(stagedFingerprint, paths.stagedFile(), entry, "Staging data changed before replacement.");
        requireFingerprint(targetSnapshot, paths.targetFile(), entry, "Replacement target changed before commit.");
        if (!backupExists) {
            storageService.createStagedRegularFileReplacementBackup(
                    paths.destinationPath(), paths.filename(), backup
            );
            requireFingerprint(targetSnapshot, backup, entry, "File replacement backup is invalid.");
        }
        requireFingerprint(targetSnapshot, paths.targetFile(), entry, "Replacement target changed during commit.");
        storageService.commitStagedRegularFileReplace(
                paths.stagedFile(), paths.destinationPath(), paths.filename()
        );
        requireFingerprint(stagedFingerprint, paths.targetFile(), entry, "Replacement target is incomplete.");
        return update(entry, FileCommitPhase.FILES_MOVED, 1, "Staged file replaced target.");
    }

    private void cleanupReplacementBackup(FileCommitJournalEntry entry) throws IOException {
        if (entry.manifest().conflictPolicy() != ConflictPolicy.OVERWRITE) {
            return;
        }
        Path backup = replacementBackup(entry.manifest().operationId());
        if (!Files.exists(backup, LinkOption.NOFOLLOW_LINKS)) {
            return;
        }
        FileCommitFingerprint targetSnapshot = entry.manifest().items().get(0).targetSnapshot();
        if (targetSnapshot == null) {
            throw markRecoveryRequired(entry, "File replacement target snapshot is missing during cleanup.");
        }
        requireFingerprint(targetSnapshot, backup, entry, "File replacement backup changed before cleanup.");
        storageService.deleteStagedRegularFileReplacementBackup(backup);
    }

    private FileCommitJournalEntry requireMatchingPlan(
            FileCommitJournalEntry entry,
            FileCommitOwner owner,
            CommitPaths paths,
            ConflictPolicy conflictPolicy,
            boolean directory
    ) throws IOException {
        FileCommitManifest manifest = entry.manifest();
        boolean matches = manifest.owner().equals(owner)
                && manifest.operationType() == (directory
                        ? FileCommitOperationType.SINGLE_DIRECTORY : FileCommitOperationType.SINGLE_FILE)
                && (!directory || conflictPolicy != ConflictPolicy.OVERWRITE)
                && manifest.conflictPolicy() == conflictPolicy
                && manifest.items().size() == 1
                && manifest.items().get(0).stagingFingerprint().directory() == directory
                && manifest.items().get(0).stagingPath().equals(paths.stagingPath())
                && manifest.items().get(0).targetPath().equals(paths.targetPath());
        if (!matches) {
            throw markRecoveryRequired(entry, "Stored file commit plan does not match its owner state.");
        }
        return entry;
    }

    private void requireCommittedTarget(FileCommitJournalEntry entry, Path target) throws IOException {
        requireFingerprint(
                entry.manifest().items().get(0).stagingFingerprint(),
                target,
                entry,
                "Committed target is missing or changed."
        );
    }

    private void requireFingerprint(
            FileCommitFingerprint expected,
            Path path,
            FileCommitJournalEntry entry,
            String detail
    ) throws IOException {
        if (!matchesFingerprint(expected, path)) {
            throw markRecoveryRequired(entry, detail);
        }
    }

    private boolean matchesFingerprint(FileCommitFingerprint expected, Path path) throws IOException {
        try {
            return expected != null && expected.directory()
                    ? FileCommitFingerprints.matchesTree(expected, path)
                    : FileCommitFingerprints.matchesRegularFile(expected, path);
        } catch (StorageAccessException ex) {
            return false;
        }
    }

    private FileCommitJournalEntry abort(FileCommitJournalEntry entry, String detail) throws IOException {
        return update(
                entry,
                FileCommitPhase.ABORTED,
                entry.state().nextItemIndex(),
                detail
        );
    }

    private FileCommitConflictException conflict(FileCommitJournalEntry entry, Path target) {
        return new FileCommitConflictException(target.toString(), entry.manifest().operationId());
    }

    private FileCommitRecoveryRequiredException markRecoveryRequired(
            FileCommitJournalEntry entry,
            String detail
    ) throws IOException {
        FileCommitJournalEntry marked = update(
                entry,
                FileCommitPhase.NEEDS_REVIEW,
                entry.state().nextItemIndex(),
                detail
        );
        return recoveryRequired(marked, detail);
    }

    private FileCommitRecoveryRequiredException recoveryRequired(FileCommitJournalEntry entry, String detail) {
        return new FileCommitRecoveryRequiredException(
                detail + " Operation " + entry.manifest().operationId() + " requires inspection."
        );
    }

    private FileCommitJournalEntry update(
            FileCommitJournalEntry entry,
            FileCommitPhase phase,
            int nextItemIndex,
            String detail
    ) throws IOException {
        return journalStore.updateState(new FileCommitJournalState(
                entry.manifest().operationId(),
                phase,
                nextItemIndex,
                detail,
                Instant.now()
        ));
    }

    private CommitPaths recoveryPaths(FileCommitJournalEntry entry) throws IOException {
        FileCommitManifest manifest = entry.manifest();
        if ((manifest.operationType() != FileCommitOperationType.SINGLE_FILE
                && manifest.operationType() != FileCommitOperationType.SINGLE_DIRECTORY) || manifest.items().size() != 1) {
            throw rejectRecoveryPlan(entry, "Stored file commit is not a single-file plan.");
        }
        FileCommitItem item = manifest.items().get(0);
        String stagingPrefix = metadataDirectory + "/" + FILE_STAGING_DIRECTORY + "/";
        if (!item.stagingPath().startsWith(stagingPrefix)) {
            throw rejectRecoveryPlan(entry, "Stored staging path is outside file staging.");
        }
        String stagingFilename = item.stagingPath().substring(stagingPrefix.length());
        if (stagingFilename.isBlank() || stagingFilename.contains("/")) {
            throw rejectRecoveryPlan(entry, "Stored staging path is not a direct file-staging child.");
        }

        String targetPath = item.targetPath();
        int separator = targetPath.lastIndexOf('/');
        String destinationPath = separator < 0 ? "" : targetPath.substring(0, separator);
        String filename = separator < 0 ? targetPath : targetPath.substring(separator + 1);
        CommitPaths paths;
        try {
            paths = commitPaths(
                    storageRoot.resolve(item.stagingPath()).normalize(),
                    destinationPath,
                    filename
            );
        } catch (IllegalArgumentException | StorageAccessException ex) {
            throw rejectRecoveryPlan(entry, "Stored file commit path is invalid.");
        }
        requireMatchingPlan(entry, manifest.owner(), paths, manifest.conflictPolicy(),
                manifest.operationType() == FileCommitOperationType.SINGLE_DIRECTORY);
        return paths;
    }

    private FileCommitRecoveryRequiredException rejectRecoveryPlan(
            FileCommitJournalEntry entry,
            String detail
    ) throws IOException {
        FileCommitPhase phase = entry.state().phase();
        if (phase == FileCommitPhase.ABORTED || phase == FileCommitPhase.COMPLETED) {
            return recoveryRequired(entry, detail);
        }
        return markRecoveryRequired(entry, detail);
    }

    private CommitPaths commitPaths(Path stagedFile, String destinationPath, String filename) throws IOException {
        Objects.requireNonNull(stagedFile, "stagedFile");
        String stagingFilename = storageService.fileStagingFilename(stagedFile);
        Path canonicalStagingFile = storageService.resolveFileStagingFile(stagingFilename);
        String normalizedDestination = storageService.normalizeVaultDirectory(destinationPath);
        storageService.validateVaultEntryName(filename);
        String targetPath = normalizedDestination.isBlank()
                ? filename
                : normalizedDestination + "/" + filename;
        Path targetFile = storageRoot.resolve(targetPath).normalize();
        if (!targetFile.startsWith(storageRoot)) {
            throw new StorageAccessException("File commit target is outside storage.");
        }
        String stagingPath = metadataDirectory + "/" + FILE_STAGING_DIRECTORY + "/" + stagingFilename;
        return new CommitPaths(
                canonicalStagingFile,
                targetFile,
                stagingPath,
                targetPath,
                normalizedDestination,
                filename
        );
    }

    private Path replacementBackup(String operationId) {
        return storageService.resolveFileStagingFile(replacementBackupFilename(operationId));
    }

    private String replacementBackupFilename(String operationId) {
        return "file-commit-backup-" + FileCommitJournalPaths.requireOperationId(operationId) + ".tmp";
    }

    static FileCommitFingerprint fingerprint(Path path) throws IOException {
        return FileCommitFingerprints.regularFile(path);
    }

    private void requireRegularFile(Path path, String message) throws IOException {
        if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS) || Files.isSymbolicLink(path)) {
            throw new NoSuchFileException(path.toString(), null, message);
        }
    }

    private String requireDirectoryName(String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("Metadata directory name is required.");
        }
        Path configured = Path.of(value.trim());
        if (configured.isAbsolute()
                || configured.getNameCount() != 1
                || configured.getFileName().toString().equals(".")
                || configured.getFileName().toString().equals("..")) {
            throw new IllegalArgumentException("Metadata directory must be a single relative name.");
        }
        return configured.toString().replace('\\', '/');
    }

    public record StagedFileCommit(String operationId, StorageService.CommittedVaultFile file) {
    }

    public record SingleFileCommitPlan(
            String operationId,
            FileCommitOwner owner,
            FileCommitPhase phase,
            Path stagedFile,
            String destinationPath,
            String filename,
            ConflictPolicy conflictPolicy,
            FileCommitFingerprint stagingFingerprint,
            FileCommitFingerprint targetSnapshot
    ) {
    }

    private record CommitPaths(
            Path stagedFile,
            Path targetFile,
            String stagingPath,
            String targetPath,
            String destinationPath,
            String filename
    ) {
    }
}
