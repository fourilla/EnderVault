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
import java.nio.file.attribute.BasicFileAttributes;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
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
        Objects.requireNonNull(owner, "owner");
        CommitPaths paths = commitPaths(stagedFile, destinationPath, filename);
        Optional<FileCommitJournalEntry> existing = journalStore.findByOwner(owner);
        if (existing.isPresent() && isFinished(existing.get().state().phase())) {
            journalStore.deleteFinished(existing.get().manifest().operationId());
            existing = Optional.empty();
        }

        FileCommitJournalEntry entry = existing.isPresent()
                ? requireMatchingPlan(existing.get(), owner, paths)
                : createJournal(owner, paths);
        return resume(entry, paths);
    }

    public synchronized Optional<FileCommitJournalEntry> findByOwner(FileCommitOwner owner) throws IOException {
        return journalStore.findByOwner(owner);
    }

    public synchronized boolean hasActiveJournal(FileCommitOwner owner) throws IOException {
        return journalStore.findByOwner(owner).isPresent();
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

    private FileCommitJournalEntry createJournal(FileCommitOwner owner, CommitPaths paths) throws IOException {
        requireRegularFile(paths.stagedFile(), "File commit staging data is unavailable.");
        if (Files.exists(paths.targetFile(), LinkOption.NOFOLLOW_LINKS)) {
            throw new FileAlreadyExistsException(paths.targetFile().toString());
        }
        FileCommitManifest manifest = new FileCommitManifest(
                FileCommitManifest.CURRENT_SCHEMA_VERSION,
                UUID.randomUUID().toString(),
                owner,
                FileCommitOperationType.SINGLE_FILE,
                ConflictPolicy.CANCEL,
                List.of(new FileCommitItem(
                        0,
                        paths.stagingPath(),
                        paths.targetPath(),
                        fingerprint(paths.stagedFile()),
                        null
                )),
                Instant.now()
        );
        return journalStore.create(manifest);
    }

    private StagedFileCommit resume(FileCommitJournalEntry initial, CommitPaths paths) throws IOException {
        FileCommitJournalEntry entry = initial;
        if (entry.state().phase() == FileCommitPhase.NEEDS_REVIEW) {
            throw recoveryRequired(entry, "File commit is waiting for manual recovery.");
        }
        if (entry.state().phase() == FileCommitPhase.PREPARED) {
            entry = update(entry, FileCommitPhase.COMMITTING, 0, "Committing staged file.");
        }
        if (entry.state().phase() == FileCommitPhase.COMMITTING) {
            entry = reconcileCommitting(entry, paths);
        }
        if (entry.state().phase() == FileCommitPhase.FILES_MOVED) {
            requireCommittedTarget(entry, paths.targetFile());
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

    private FileCommitJournalEntry reconcileCommitting(
            FileCommitJournalEntry entry,
            CommitPaths paths
    ) throws IOException {
        FileCommitFingerprint expected = entry.manifest().items().get(0).stagingFingerprint();
        boolean sourceExists = Files.exists(paths.stagedFile(), LinkOption.NOFOLLOW_LINKS);
        boolean targetExists = Files.exists(paths.targetFile(), LinkOption.NOFOLLOW_LINKS);

        if (sourceExists && targetExists) {
            requireRegularFile(paths.stagedFile(), "File commit staging data is unsafe.");
            requireRegularFile(paths.targetFile(), "File commit target is unsafe.");
            if (!Files.isSameFile(paths.stagedFile(), paths.targetFile())) {
                abort(entry, "Destination was occupied before commit completed.");
                throw new FileAlreadyExistsException(paths.targetFile().toString());
            }
            requireFingerprint(expected, paths.stagedFile(), entry, "Staging data changed before commit.");
            storageService.commitStagedRegularFileNoReplace(
                    paths.stagedFile(), paths.destinationPath(), paths.filename()
            );
        } else if (sourceExists) {
            requireFingerprint(expected, paths.stagedFile(), entry, "Staging data changed before commit.");
            try {
                storageService.commitStagedRegularFileNoReplace(
                        paths.stagedFile(), paths.destinationPath(), paths.filename()
                );
            } catch (FileAlreadyExistsException ex) {
                abort(entry, "Destination was occupied before commit completed.");
                throw ex;
            }
        } else if (!targetExists) {
            throw markRecoveryRequired(entry, "Both staging data and destination are missing.");
        }

        requireFingerprint(expected, paths.targetFile(), entry, "Committed target does not match staging data.");
        return update(entry, FileCommitPhase.FILES_MOVED, 1, "Staged file committed.");
    }

    private FileCommitJournalEntry requireMatchingPlan(
            FileCommitJournalEntry entry,
            FileCommitOwner owner,
            CommitPaths paths
    ) throws IOException {
        FileCommitManifest manifest = entry.manifest();
        boolean matches = manifest.owner().equals(owner)
                && manifest.operationType() == FileCommitOperationType.SINGLE_FILE
                && manifest.conflictPolicy() == ConflictPolicy.CANCEL
                && manifest.items().size() == 1
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
        if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS) || Files.isSymbolicLink(path)) {
            return false;
        }
        FileCommitFingerprint actual = fingerprint(path);
        if (expected.size() != actual.size()) {
            return false;
        }
        if (expected.fileKey() != null || actual.fileKey() != null) {
            return Objects.equals(expected.fileKey(), actual.fileKey());
        }
        return Objects.equals(expected.modifiedAt(), actual.modifiedAt());
    }

    private void abort(FileCommitJournalEntry entry, String detail) throws IOException {
        FileCommitJournalEntry aborted = update(
                entry,
                FileCommitPhase.ABORTED,
                entry.state().nextItemIndex(),
                detail
        );
        journalStore.deleteFinished(aborted.manifest().operationId());
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

    static FileCommitFingerprint fingerprint(Path path) throws IOException {
        BasicFileAttributes attributes = Files.readAttributes(
                path,
                BasicFileAttributes.class,
                LinkOption.NOFOLLOW_LINKS
        );
        Object fileKey = attributes.fileKey();
        return new FileCommitFingerprint(
                attributes.size(),
                attributes.lastModifiedTime().toInstant(),
                fileKey == null ? null : fileKey.toString()
        );
    }

    private void requireRegularFile(Path path, String message) throws IOException {
        if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS) || Files.isSymbolicLink(path)) {
            throw new NoSuchFileException(path.toString(), null, message);
        }
    }

    private boolean isFinished(FileCommitPhase phase) {
        return phase == FileCommitPhase.COMPLETED || phase == FileCommitPhase.ABORTED;
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
