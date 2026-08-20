package io.github.fourilla.endervault.pending;

import io.github.fourilla.endervault.common.StorageAccessException;
import io.github.fourilla.endervault.pending.PendingFileDecision.TargetSnapshot;
import io.github.fourilla.endervault.storage.ConflictPolicy;
import io.github.fourilla.endervault.storage.FileItem;
import io.github.fourilla.endervault.storage.StorageService;
import io.github.fourilla.endervault.temporary.TemporaryArtifactRegistry;
import io.github.fourilla.endervault.temporary.TemporaryArtifactType;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

@Service
public class PendingFileDecisionService {

    private static final Logger logger = LoggerFactory.getLogger(PendingFileDecisionService.class);

    private final PendingFileDecisionRepository repository;
    private final StorageService storageService;
    private final TemporaryArtifactRegistry temporaryArtifactRegistry;
    private final List<PendingFileDecisionResolutionObserver> resolutionObservers;
    private final Map<String, TemporaryArtifactRegistry.Registration> registrations = new HashMap<>();

    public PendingFileDecisionService(
            PendingFileDecisionRepository repository,
            StorageService storageService,
            TemporaryArtifactRegistry temporaryArtifactRegistry,
            List<PendingFileDecisionResolutionObserver> resolutionObservers
    ) {
        this.repository = repository;
        this.storageService = storageService;
        this.temporaryArtifactRegistry = temporaryArtifactRegistry;
        this.resolutionObservers = List.copyOf(resolutionObservers);
    }

    @PostConstruct
    public synchronized void restoreRegistrations() throws IOException {
        for (PendingFileDecision decision : repository.list()) {
            Path stagedFile = storageService.resolveFileStagingFile(decision.stagingFilename());
            if (!validStagedFile(stagedFile)) {
                logger.warn("Pending file decision {} has no safe staging file.", decision.id());
                continue;
            }
            register(decision, stagedFile);
        }
    }

    public synchronized PendingFileDecision create(
            Path stagedFile,
            PendingFileDecisionSource source,
            String destinationPath,
            String originalFilename,
            long size
    ) throws IOException {
        return create(stagedFile, source, destinationPath, originalFilename, size, null, null);
    }

    public synchronized PendingFileDecision create(
            Path stagedFile,
            PendingFileDecisionSource source,
            String destinationPath,
            String originalFilename,
            long size,
            String sourceReference,
            String submittedBy
    ) throws IOException {
        String stagingFilename = storageService.fileStagingFilename(stagedFile);
        if (!validStagedFile(stagedFile)) {
            throw new StorageAccessException("Pending file data must be a regular staging file.");
        }
        storageService.validateVaultEntryName(originalFilename);
        PendingFileDecision decision = new PendingFileDecision(
                UUID.randomUUID().toString(),
                source,
                stagingFilename,
                destinationPath == null ? "" : destinationPath,
                originalFilename,
                size,
                Instant.now(),
                targetSnapshot(destinationPath, originalFilename),
                cleanOptional(sourceReference),
                cleanOptional(submittedBy)
        );
        register(decision, stagedFile);
        try {
            repository.add(decision);
            return decision;
        } catch (IOException | RuntimeException ex) {
            release(decision.id());
            throw ex;
        }
    }

    public synchronized List<PendingFileDecision> list() throws IOException {
        return repository.list().stream()
                .sorted(Comparator.comparing(PendingFileDecision::createdAt).reversed())
                .toList();
    }

    public synchronized PendingFileDecision require(String id) throws IOException {
        String cleanId = cleanId(id);
        return repository.find(cleanId)
                .orElseThrow(() -> new NoSuchFileException("Pending file decision was not found."));
    }

    public synchronized PendingFileDecisionResult resolve(
            String id,
            PendingFileDecisionAction action,
            String requestedFilename,
            boolean replaceConfirmed
    ) throws IOException {
        PendingFileDecision decision = require(id);
        Path stagedFile = storageService.resolveFileStagingFile(decision.stagingFilename());
        if (!validStagedFile(stagedFile)) {
            removeMissingData(decision.id());
            throw new NoSuchFileException("Pending file data is no longer available.");
        }

        if (action == PendingFileDecisionAction.DISCARD) {
            Files.deleteIfExists(stagedFile);
            complete(decision.id());
            notifyResolved(decision, action, true);
            return new PendingFileDecisionResult(decision, null, true);
        }

        String filename = decision.originalFilename();
        ConflictPolicy policy = switch (action) {
            case KEEP_BOTH -> ConflictPolicy.RENAME;
            case SAVE_AS -> {
                filename = cleanFilename(requestedFilename);
                storageService.validateVaultEntryName(filename);
                yield ConflictPolicy.CANCEL;
            }
            case REPLACE -> {
                if (!replaceConfirmed) {
                    throw new StorageAccessException("Replacing an existing file requires confirmation.");
                }
                assertTargetUnchanged(decision);
                yield ConflictPolicy.OVERWRITE;
            }
            case DISCARD -> throw new IllegalStateException("Discard is handled before file commit.");
        };

        FileItem committed = storageService.moveTemporaryFileIntoVault(
                stagedFile,
                decision.destinationPath(),
                filename,
                policy
        );
        complete(decision.id());
        notifyResolved(decision, action, false);
        return new PendingFileDecisionResult(decision, committed, false);
    }

    public synchronized PendingFileDecision removeMissingData(String id) throws IOException {
        PendingFileDecision decision = require(id);
        Path stagedFile = storageService.resolveFileStagingFile(decision.stagingFilename());
        if (validStagedFile(stagedFile)) {
            throw new StorageAccessException("Pending file data still exists.");
        }
        Files.deleteIfExists(stagedFile);
        complete(decision.id());
        notifyResolved(decision, PendingFileDecisionAction.DISCARD, true);
        return decision;
    }

    @PreDestroy
    public synchronized void closeRegistrations() {
        registrations.values().forEach(TemporaryArtifactRegistry.Registration::close);
        registrations.clear();
    }

    private TargetSnapshot targetSnapshot(String destinationPath, String filename) throws IOException {
        try {
            FileItem target = storageService.describeVaultChild(destinationPath, filename);
            return new TargetSnapshot(target.size(), target.modifiedAt());
        } catch (NoSuchFileException ex) {
            return null;
        }
    }

    private void assertTargetUnchanged(PendingFileDecision decision) throws IOException {
        TargetSnapshot expected = decision.targetSnapshot();
        if (expected == null) {
            throw new StorageAccessException("The original conflict target is no longer available.");
        }
        FileItem current;
        try {
            current = storageService.describeVaultChild(decision.destinationPath(), decision.originalFilename());
        } catch (NoSuchFileException ex) {
            throw new StorageAccessException("The conflict target changed after this decision was created.", ex);
        }
        if (current.directory()
                || current.size() != expected.size()
                || !current.modifiedAt().equals(expected.modifiedAt())) {
            throw new StorageAccessException("The conflict target changed after this decision was created.");
        }
    }

    private void register(PendingFileDecision decision, Path stagedFile) {
        TemporaryArtifactRegistry.Registration registration = temporaryArtifactRegistry.register(
                stagedFile,
                TemporaryArtifactType.PENDING_FILE_DECISION,
                decision.id()
        );
        registrations.put(decision.id(), registration);
    }

    private void complete(String id) throws IOException {
        try {
            repository.remove(id);
        } finally {
            release(id);
        }
    }

    private void release(String id) {
        TemporaryArtifactRegistry.Registration registration = registrations.remove(id);
        if (registration != null) {
            registration.close();
        }
    }

    private String cleanId(String id) {
        if (id == null || id.isBlank()) {
            throw new StorageAccessException("Pending file decision id is required.");
        }
        return id.trim();
    }

    private String cleanFilename(String filename) {
        if (filename == null || filename.isBlank()) {
            throw new StorageAccessException("A new file name is required.");
        }
        return filename.trim();
    }

    private String cleanOptional(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private void notifyResolved(
            PendingFileDecision decision,
            PendingFileDecisionAction action,
            boolean discarded
    ) {
        resolutionObservers.stream()
                .filter(observer -> observer.supports(decision))
                .forEach(observer -> {
                    try {
                        observer.afterResolved(decision, action, discarded);
                    } catch (Exception ex) {
                        logger.error(
                                "Failed to apply pending decision side effects for {} using {}.",
                                decision.id(),
                                observer.getClass().getSimpleName(),
                                ex
                        );
                    }
                });
    }

    private boolean validStagedFile(Path path) {
        return Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS) && !Files.isSymbolicLink(path);
    }

    public record PendingFileDecisionResult(
            PendingFileDecision decision,
            FileItem committedFile,
            boolean discarded
    ) {
    }
}
