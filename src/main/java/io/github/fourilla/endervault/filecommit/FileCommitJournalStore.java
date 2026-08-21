package io.github.fourilla.endervault.filecommit;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.fourilla.endervault.config.NasProperties;
import jakarta.annotation.PostConstruct;
import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.DirectoryNotEmptyException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Repository;

@Repository
public class FileCommitJournalStore {

    private static final String MANIFEST_FILE = "manifest.json";
    private static final String STATE_FILE = "state.json";
    private static final String DELETING_PREFIX = ".deleting-";

    private final Path journalRoot;
    private final DurableJsonFileWriter jsonWriter;

    @Autowired
    public FileCommitJournalStore(ObjectMapper objectMapper, NasProperties nasProperties) {
        Path storageRoot = nasProperties.getStorage().getRoot().toAbsolutePath().normalize();
        String metadataDirectory = requireDirectoryName(nasProperties.getStorage().getMetadataDirectory());
        this.journalRoot = storageRoot.resolve(metadataDirectory).resolve("commit-journal").normalize();
        if (!journalRoot.startsWith(storageRoot)) {
            throw new IllegalArgumentException("File commit journal must remain inside storage metadata.");
        }
        this.jsonWriter = new DurableJsonFileWriter(objectMapper);
    }

    FileCommitJournalStore(ObjectMapper objectMapper, Path journalRoot) {
        this.journalRoot = journalRoot.toAbsolutePath().normalize();
        this.jsonWriter = new DurableJsonFileWriter(objectMapper);
    }

    @PostConstruct
    public synchronized void initialize() throws IOException {
        Files.createDirectories(journalRoot);
        requireSafeDirectory(journalRoot);
        cleanupInterruptedWrites(journalRoot);
        cleanupDeletingDirectories();
    }

    public synchronized FileCommitJournalEntry create(FileCommitManifest manifest) throws IOException {
        Path operationDirectory = operationDirectory(manifest.operationId());
        Files.createDirectory(operationDirectory);
        try {
            FileCommitJournalState state = FileCommitJournalState.prepared(manifest.operationId(), Instant.now());
            jsonWriter.write(operationDirectory.resolve(MANIFEST_FILE), manifest);
            jsonWriter.write(operationDirectory.resolve(STATE_FILE), state);
            jsonWriter.forceDirectory(operationDirectory);
            return new FileCommitJournalEntry(manifest, state);
        } catch (IOException | RuntimeException ex) {
            tombstoneAndDelete(operationDirectory, manifest.operationId());
            throw ex;
        }
    }

    public synchronized FileCommitJournalEntry load(String operationId) throws IOException {
        Path operationDirectory = operationDirectory(operationId);
        requireSafeDirectory(operationDirectory);
        FileCommitManifest manifest = jsonWriter.read(operationDirectory.resolve(MANIFEST_FILE), FileCommitManifest.class);
        Path statePath = operationDirectory.resolve(STATE_FILE);
        FileCommitJournalState state = Files.exists(statePath, LinkOption.NOFOLLOW_LINKS)
                ? jsonWriter.read(statePath, FileCommitJournalState.class)
                : FileCommitJournalState.prepared(manifest.operationId(), manifest.createdAt());
        return new FileCommitJournalEntry(manifest, state);
    }

    public synchronized List<FileCommitJournalEntry> list() throws IOException {
        if (!Files.exists(journalRoot, LinkOption.NOFOLLOW_LINKS)) {
            return List.of();
        }
        requireSafeDirectory(journalRoot);
        List<FileCommitJournalEntry> entries = new ArrayList<>();
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(journalRoot)) {
            for (Path operationDirectory : stream) {
                if (!Files.isDirectory(operationDirectory, LinkOption.NOFOLLOW_LINKS)
                        || Files.isSymbolicLink(operationDirectory)
                        || operationDirectory.getFileName().toString().startsWith(DELETING_PREFIX)) {
                    continue;
                }
                entries.add(load(operationDirectory.getFileName().toString()));
            }
        }
        entries.sort(Comparator.comparing(entry -> entry.manifest().createdAt()));
        return List.copyOf(entries);
    }

    public synchronized Optional<FileCommitJournalEntry> findByOwner(FileCommitOwner owner) throws IOException {
        List<FileCommitJournalEntry> matches = list().stream()
                .filter(entry -> entry.manifest().owner().equals(owner))
                .toList();
        if (matches.size() > 1) {
            throw new IOException("Multiple file commit journals exist for one owner: " + owner);
        }
        return matches.stream().findFirst();
    }

    public synchronized FileCommitJournalEntry updateState(FileCommitJournalState state) throws IOException {
        FileCommitJournalEntry current = load(state.operationId());
        validateTransition(current.state(), state, current.manifest().items().size());
        jsonWriter.write(operationDirectory(state.operationId()).resolve(STATE_FILE), state);
        return new FileCommitJournalEntry(current.manifest(), state);
    }

    public synchronized void deleteFinished(String operationId) throws IOException {
        FileCommitJournalEntry entry = load(operationId);
        if (entry.state().phase() != FileCommitPhase.COMPLETED
                && entry.state().phase() != FileCommitPhase.ABORTED) {
            throw new IllegalStateException("Only completed or aborted file commit journals can be deleted.");
        }
        tombstoneAndDelete(operationDirectory(operationId), operationId);
        jsonWriter.forceDirectory(journalRoot);
    }

    Path journalRoot() {
        return journalRoot;
    }

    private void validateTransition(
            FileCommitJournalState current,
            FileCommitJournalState next,
            int itemCount
    ) {
        if (next.nextItemIndex() > itemCount) {
            throw new IllegalArgumentException("File commit state points beyond the commit plan.");
        }
        if (next.nextItemIndex() < current.nextItemIndex()) {
            throw new IllegalArgumentException("File commit progress cannot move backwards.");
        }
        if (!allowedTransition(current.phase(), next.phase())) {
            throw new IllegalStateException(
                    "Invalid file commit phase transition: " + current.phase() + " -> " + next.phase()
            );
        }
    }

    private boolean allowedTransition(FileCommitPhase current, FileCommitPhase next) {
        if (current == next) {
            return current == FileCommitPhase.COMMITTING || current == FileCommitPhase.NEEDS_REVIEW;
        }
        if (next == FileCommitPhase.NEEDS_REVIEW) {
            return current != FileCommitPhase.COMPLETED && current != FileCommitPhase.ABORTED;
        }
        if (next == FileCommitPhase.ABORTED) {
            return current != FileCommitPhase.COMPLETED && current != FileCommitPhase.ABORTED;
        }
        return switch (current) {
            case PREPARED -> next == FileCommitPhase.COMMITTING;
            case COMMITTING -> next == FileCommitPhase.FILES_MOVED;
            case FILES_MOVED -> next == FileCommitPhase.APPLYING_METADATA;
            case APPLYING_METADATA -> next == FileCommitPhase.COMPLETED;
            case NEEDS_REVIEW -> next == FileCommitPhase.COMMITTING
                    || next == FileCommitPhase.FILES_MOVED
                    || next == FileCommitPhase.APPLYING_METADATA;
            case ABORTED, COMPLETED -> false;
        };
    }

    private Path operationDirectory(String operationId) {
        String cleanId = FileCommitJournalPaths.requireOperationId(operationId);
        Path path = journalRoot.resolve(cleanId).normalize();
        if (!path.getParent().equals(journalRoot)) {
            throw new IllegalArgumentException("Invalid file commit operation path.");
        }
        return path;
    }

    private void cleanupInterruptedWrites(Path directory) throws IOException {
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(directory)) {
            for (Path child : stream) {
                if (Files.isDirectory(child, LinkOption.NOFOLLOW_LINKS) && !Files.isSymbolicLink(child)) {
                    cleanupInterruptedWrites(child);
                    continue;
                }
                if (Files.isRegularFile(child, LinkOption.NOFOLLOW_LINKS)
                        && !Files.isSymbolicLink(child)
                        && child.getFileName().toString().endsWith(".tmp")) {
                    Files.deleteIfExists(child);
                }
            }
        }
    }

    private void deleteOperationDirectory(Path operationDirectory) throws IOException {
        Files.deleteIfExists(operationDirectory.resolve(STATE_FILE));
        Files.deleteIfExists(operationDirectory.resolve(MANIFEST_FILE));
        try (DirectoryStream<Path> remaining = Files.newDirectoryStream(operationDirectory)) {
            if (remaining.iterator().hasNext()) {
                throw new DirectoryNotEmptyException(operationDirectory.toString());
            }
        }
        Files.deleteIfExists(operationDirectory);
    }

    private void tombstoneAndDelete(Path operationDirectory, String operationId) throws IOException {
        if (!Files.exists(operationDirectory, LinkOption.NOFOLLOW_LINKS)) {
            return;
        }
        requireSafeDirectory(operationDirectory);
        Path deletingDirectory = journalRoot.resolve(
                DELETING_PREFIX + FileCommitJournalPaths.requireOperationId(operationId) + "-" + java.util.UUID.randomUUID()
        );
        try {
            Files.move(operationDirectory, deletingDirectory, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException ex) {
            Files.move(operationDirectory, deletingDirectory);
        }
        jsonWriter.forceDirectory(journalRoot);
        deleteOperationDirectory(deletingDirectory);
    }

    private void cleanupDeletingDirectories() throws IOException {
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(journalRoot, DELETING_PREFIX + "*")) {
            for (Path deletingDirectory : stream) {
                if (Files.isDirectory(deletingDirectory, LinkOption.NOFOLLOW_LINKS)
                        && !Files.isSymbolicLink(deletingDirectory)) {
                    deleteOperationDirectory(deletingDirectory);
                }
            }
        }
        jsonWriter.forceDirectory(journalRoot);
    }

    private void requireSafeDirectory(Path directory) throws IOException {
        if (!Files.isDirectory(directory, LinkOption.NOFOLLOW_LINKS) || Files.isSymbolicLink(directory)) {
            throw new IOException("File commit journal path is not a safe directory: " + directory);
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
        return configured.toString();
    }
}
