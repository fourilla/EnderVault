package io.github.fourilla.endervault.upload;

import io.github.fourilla.endervault.activity.ActivityLogService;
import io.github.fourilla.endervault.common.StorageAccessException;
import io.github.fourilla.endervault.config.NasProperties;
import io.github.fourilla.endervault.filecommit.FileCommitConflictException;
import io.github.fourilla.endervault.filecommit.FileCommitCoordinator;
import io.github.fourilla.endervault.filecommit.FileCommitOwner;
import io.github.fourilla.endervault.filecommit.FileCommitOwnerType;
import io.github.fourilla.endervault.filecommit.FileCommitFingerprints;
import io.github.fourilla.endervault.pending.PendingFileDecisionService;
import io.github.fourilla.endervault.pending.PendingFileDecisionSource;
import io.github.fourilla.endervault.storage.StorageService;
import io.github.fourilla.endervault.temporary.TemporaryArtifactRegistry;
import io.github.fourilla.endervault.temporary.TemporaryArtifactType;
import jakarta.annotation.PreDestroy;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.http.HttpStatus;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

@Service
public class DirectoryUploadService {
    private static final Logger logger = LoggerFactory.getLogger(DirectoryUploadService.class);
    private final DirectoryUploadRepository repository;
    private final ResumableUploadService uploads;
    private final ResumableUploadCoordinator protocol;
    private final StorageService storage;
    private final FileCommitCoordinator commits;
    private final PendingFileDecisionService pending;
    private final TemporaryArtifactRegistry artifacts;
    private final NasProperties properties;
    private final ActivityLogService activity;
    private final Map<String, TemporaryArtifactRegistry.Registration> registrations = new HashMap<>();

    public DirectoryUploadService(DirectoryUploadRepository repository, ResumableUploadService uploads,
            ResumableUploadCoordinator protocol, StorageService storage, FileCommitCoordinator commits,
            PendingFileDecisionService pending, TemporaryArtifactRegistry artifacts, NasProperties properties,
            ActivityLogService activity) {
        this.repository = repository;
        this.uploads = uploads;
        this.protocol = protocol;
        this.storage = storage;
        this.commits = commits;
        this.pending = pending;
        this.artifacts = artifacts;
        this.properties = properties;
        this.activity = activity;
    }

    public synchronized DirectoryUpload create(String destination, DirectoryUploadManifest input) throws IOException {
        DirectoryUploadManifest manifest = input.validate(storage);
        String path = storage.normalizeVaultDirectory(destination);
        if (manifest.resumeId() != null && !manifest.resumeId().isBlank()) {
            DirectoryUpload existing = repository.require(manifest.resumeId());
            requireLive(existing);
            boolean matches = existing.destinationPath().equals(path) && existing.name().equals(manifest.name())
                    && existing.directories().equals(manifest.directories())
                    && existing.files().stream().map(e -> new DirectoryUploadManifest.File(e.path(), e.size(), e.lastModified()))
                        .toList().equals(manifest.files());
            if (!matches) throw new ResponseStatusException(HttpStatus.CONFLICT, "The selected directory differs from this upload.");
            return refresh(existing);
        }
        NasProperties.Upload settings = properties.getUpload();
        if (!settings.isDirectoryEnabled()) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "New directory uploads are disabled. Existing uploads can still resume.");
        }
        int maxEntries = Math.max(1, Math.min(settings.getDirectoryMaxEntries(), NasProperties.Upload.DIRECTORY_ENTRIES_CEILING));
        int maxDepth = Math.max(1, Math.min(settings.getDirectoryMaxDepth(), NasProperties.Upload.DIRECTORY_DEPTH_CEILING));
        if (1L + manifest.files().size() + manifest.directories().size() > maxEntries) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Directory upload exceeds the configured limit of " + maxEntries + " entries.");
        }
        boolean tooDeep = java.util.stream.Stream.concat(manifest.directories().stream(), manifest.files().stream().map(DirectoryUploadManifest.File::path))
                .anyMatch(entry -> entry.split("/", -1).length > maxDepth);
        if (tooDeep) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Directory upload exceeds the configured depth of " + maxDepth + ".");
        long active = 0;
        for (String id : repository.ids()) if (!repository.require(id).terminal()) active++;
        int maxActive = Math.max(1, Math.min(settings.getDirectoryMaxActive(), NasProperties.Upload.DIRECTORY_ACTIVE_CEILING));
        if (active >= maxActive) throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS, "Too many directory uploads are active.");
        Instant now = Instant.now();
        DirectoryUpload upload = new DirectoryUpload(UUID.randomUUID().toString(), path, manifest.name(),
                manifest.files().stream().map(f -> new DirectoryUpload.Entry(f.path(), f.size(), f.lastModified(), null, false)).toList(),
                manifest.directories(), now,
                now.plus(properties.getUpload().getResumableSessionRetentionHours(), ChronoUnit.HOURS),
                DirectoryUpload.Status.RECEIVING, null, null);
        repository.save(upload);
        register(upload);
        initializeDirectories(upload);
        return upload;
    }

    public synchronized DirectoryUpload get(String id) throws IOException {
        DirectoryUpload upload = repository.require(id);
        if (!upload.terminal()) requireLive(upload);
        return refresh(upload);
    }

    public synchronized ResumableUploadAdmissionResponse admit(String id, String relativePath,
            ResumableUploadAdmissionRequest request) throws IOException {
        DirectoryUpload group = repository.require(id);
        requireLive(group);
        if (group.status() != DirectoryUpload.Status.RECEIVING) throw new ResponseStatusException(HttpStatus.CONFLICT, "Directory is no longer receiving files.");
        DirectoryUpload.Entry entry = group.files().stream().filter(e -> e.path().equals(relativePath)).findFirst()
                .orElseThrow(() -> new StorageAccessException("File is not declared in the directory upload."));
        String filename = relativePath.substring(relativePath.lastIndexOf('/') + 1);
        if (!filename.equals(request.filename()) || request.size() != entry.size() || request.lastModified() != entry.lastModified()) {
            throw new StorageAccessException("File does not match the directory manifest.");
        }
        ResumableUploadSession session = uploads.admitDirectoryFile(id, relativePath, group.destinationPath(), request, entry.sessionId());
        if (!entry.received()) repository.save(group.withEntry(entry.admitted(session.id())));
        return ResumableUploadAdmissionResponse.from(session, uploads);
    }

    public synchronized DirectoryUpload complete(String id) throws IOException {
        DirectoryUpload upload = repository.require(id);
        if (!upload.terminal()) requireLive(upload);
        upload = refresh(upload);
        if (upload.status() == DirectoryUpload.Status.RECEIVING) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Some files have not finished uploading.");
        }
        return upload;
    }

    private DirectoryUpload refresh(DirectoryUpload upload) throws IOException {
        if (upload.terminal()) {
            finishJournal(upload);
            return upload;
        }
        var existingPending = pending.findBySourceReference(PendingFileDecisionSource.DIRECTORY_UPLOAD, upload.id());
        if (existingPending.isPresent()) {
            upload = upload.withStatus(DirectoryUpload.Status.PENDING, null, existingPending.get().id());
            repository.save(upload);
            release(upload.id());
            finishJournal(upload);
            return upload;
        }
        register(upload);
        if (upload.status() == DirectoryUpload.Status.RECEIVING) {
            upload = assemble(upload);
            if (!upload.files().stream().allMatch(DirectoryUpload.Entry::received)) return upload;
            upload = upload.withStatus(DirectoryUpload.Status.COMMITTING, null, null);
            repository.save(upload);
        }
        if (upload.status() != DirectoryUpload.Status.COMMITTING) return upload;
        if (!commits.hasActiveJournal(owner(upload))) validateCompletedTree(upload);
        try {
            var result = commits.commitSingleDirectory(owner(upload), root(upload), upload.destinationPath(), upload.name());
            upload = upload.withStatus(DirectoryUpload.Status.COMPLETED, result.file().path(), null);
            repository.save(upload);
            commits.complete(result.operationId());
            release(upload.id());
            recordCompletion(upload);
            return upload;
        } catch (FileCommitConflictException ex) {
            release(upload.id());
            try {
                var decision = pending.create(root(upload), PendingFileDecisionSource.DIRECTORY_UPLOAD,
                        upload.destinationPath(), upload.name(), upload.totalBytes(), upload.id(), null);
                upload = upload.withStatus(DirectoryUpload.Status.PENDING, null, decision.id());
                repository.save(upload);
                commits.completeConflict(ex.operationId());
                recordCompletion(upload);
                return upload;
            } catch (IOException | RuntimeException failure) {
                if (!artifacts.isActive(root(upload))) register(upload);
                throw failure;
            }
        }
    }

    private DirectoryUpload assemble(DirectoryUpload upload) throws IOException {
        initializeDirectories(upload);
        // Keep a source hard link until the receipt is durable. Recovery can verify identity,
        // rather than assuming a same-sized file belongs to this upload.
        synchronized (uploads) {
            for (DirectoryUpload.Entry entry : upload.files()) {
                Path target = memberPath(upload, entry.path());
                if (entry.received() && !FileCommitFingerprints.matchesRegularFile(entry.receipt(), target)) {
                    throw new StorageAccessException("Previously received directory file is missing or changed.");
                }
                if (entry.sessionId() == null) continue;
                ResumableUploadSession session;
                try { session = uploads.require(entry.sessionId()); }
                catch (NoSuchFileException ex) { continue; }
                if (session.status() != ResumableUploadStatus.DIRECTORY_READY) continue;
                Path source = storage.resolveFileStagingFile(session.stagingFilename());
                if (entry.received()) {
                    if (Files.exists(source, LinkOption.NOFOLLOW_LINKS)) {
                        if (!Files.isSameFile(source, target)) throw new StorageAccessException("Directory receipt no longer matches staging data.");
                        Files.delete(source);
                    }
                    continue;
                }
                if (!Files.isRegularFile(source, LinkOption.NOFOLLOW_LINKS) || Files.size(source) != entry.size()) {
                    throw new StorageAccessException("Directory member staging data is missing or changed.");
                }
                if (!Files.exists(target, LinkOption.NOFOLLOW_LINKS)) Files.createLink(target, source);
                if (!Files.isRegularFile(target, LinkOption.NOFOLLOW_LINKS) || !Files.isSameFile(source, target)) {
                    throw new StorageAccessException("Unexpected directory staging entry.");
                }
                try (var channel = java.nio.channels.FileChannel.open(source, java.nio.file.StandardOpenOption.WRITE)) {
                    channel.force(true);
                }
                repository.forceDirectory(target.getParent());
                upload = upload.withEntry(entry.complete(FileCommitFingerprints.regularFile(target)));
                repository.save(upload);
                Files.delete(source);
                repository.forceDirectory(source.getParent());
            }
        }
        return upload;
    }

    public synchronized DirectoryUpload cancel(String id) throws IOException {
        DirectoryUpload upload = repository.require(id);
        if (upload.terminal()) return upload;
        if (commits.hasActiveJournal(owner(upload))) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Directory is being committed; check its final status.");
        }
        upload = upload.withStatus(DirectoryUpload.Status.CANCELING, null, null);
        repository.save(upload);
        for (DirectoryUpload.Entry entry : upload.files()) {
            if (entry.sessionId() == null) continue;
            synchronized (uploads) {
                try {
                    ResumableUploadSession session = uploads.require(entry.sessionId());
                    uploads.cancel(session.id());
                    protocol.deleteProtocolDataIfPresent(session);
                    uploads.remove(session.id());
                } catch (NoSuchFileException ignored) { /* Already cleaned up. */ }
            }
        }
        release(id);
        try {
            if (Files.exists(root(upload), LinkOption.NOFOLLOW_LINKS)) storage.deleteStagedDirectory(root(upload));
        } catch (IOException | RuntimeException ex) {
            register(upload);
            throw ex;
        }
        upload = upload.withStatus(DirectoryUpload.Status.CANCELED, null, null);
        repository.save(upload);
        release(id);
        return upload;
    }

    @EventListener(ApplicationReadyEvent.class)
    @Scheduled(fixedDelay = 10_000)
    public synchronized void recoverAndCleanup() {
        try {
            for (String id : repository.ids()) {
                try {
                    DirectoryUpload upload = repository.require(id);
                    if (upload.status() == DirectoryUpload.Status.CANCELING) cancel(id);
                    else if (!upload.terminal() && upload.expired() && !commits.hasActiveJournal(owner(upload))) cancel(id);
                    else refresh(upload);
                    upload = repository.require(id);
                    if (upload.terminal() && upload.expired()) repository.remove(id);
                } catch (IOException | RuntimeException ex) {
                    logger.warn("Directory upload {} requires attention after {}.", id, ex.getClass().getSimpleName());
                }
            }
        } catch (IOException ex) { logger.warn("Could not enumerate directory uploads.", ex); }
    }

    private void initializeDirectories(DirectoryUpload upload) throws IOException {
        Path root = root(upload);
        if (Files.isSymbolicLink(root)) throw new StorageAccessException("Unsafe directory staging root.");
        Files.createDirectories(root);
        repository.forceDirectory(root.getParent());
        for (String directory : upload.directories()) {
            Path path = memberPath(upload, directory);
            Files.createDirectories(path);
            repository.forceDirectory(path.getParent());
        }
    }

    private void validateCompletedTree(DirectoryUpload upload) throws IOException {
        var expected = new java.util.HashSet<>(upload.directories());
        for (String directory : upload.directories()) {
            if (!Files.isDirectory(memberPath(upload, directory), LinkOption.NOFOLLOW_LINKS)) {
                throw new StorageAccessException("Directory staging is missing a declared directory.");
            }
        }
        for (DirectoryUpload.Entry entry : upload.files()) {
            if (!entry.received() || !FileCommitFingerprints.matchesRegularFile(entry.receipt(), memberPath(upload, entry.path()))) {
                throw new StorageAccessException("Directory upload contains a missing or changed file.");
            }
            expected.add(entry.path());
        }
        Path root = root(upload);
        try (var paths = Files.walk(root)) {
            for (Path path : paths.toList()) {
                if (path.equals(root)) continue;
                String relative = root.relativize(path).toString().replace('\\', '/');
                if (!expected.remove(relative) || Files.isSymbolicLink(path)) {
                    throw new StorageAccessException("Directory staging contains an unexpected entry.");
                }
            }
        }
        if (!expected.isEmpty()) throw new StorageAccessException("Directory staging is incomplete.");
    }

    private Path memberPath(DirectoryUpload upload, String relativePath) throws IOException {
        DirectoryUploadManifest.validatePath(storage, relativePath);
        Path root = root(upload);
        Path target = root.resolve(relativePath).normalize();
        if (!target.startsWith(root)) throw new StorageAccessException("Directory entry escaped staging.");
        for (Path current = target; current != null && current.startsWith(root); current = current.getParent()) {
            if (Files.isSymbolicLink(current)) throw new StorageAccessException("Symbolic links are not allowed in directory staging.");
        }
        return target;
    }

    private Path root(DirectoryUpload upload) { return storage.resolveFileStagingFile("directory-upload-" + upload.id()); }
    private FileCommitOwner owner(DirectoryUpload upload) { return new FileCommitOwner(FileCommitOwnerType.DIRECTORY_UPLOAD, upload.id()); }
    private void register(DirectoryUpload upload) {
        registrations.computeIfAbsent(upload.id(), id -> artifacts.register(root(upload), TemporaryArtifactType.DIRECTORY_UPLOAD, id));
    }
    private void release(String id) {
        var registration = registrations.remove(id);
        if (registration != null) registration.close();
    }
    private void finishJournal(DirectoryUpload upload) throws IOException {
        if (upload.status() == DirectoryUpload.Status.COMPLETED) commits.completeForOwnerIfPresent(owner(upload));
        if (upload.status() == DirectoryUpload.Status.PENDING) commits.completeConflictForOwnerIfPresent(owner(upload));
        release(upload.id());
    }
    private void requireLive(DirectoryUpload upload) {
        if (upload.expired() && upload.status() != DirectoryUpload.Status.COMMITTING) {
            throw new ResponseStatusException(HttpStatus.GONE, "Directory upload has expired. Select the directory again.");
        }
    }
    private void recordCompletion(DirectoryUpload upload) {
        activity.record("UPLOAD", "system", "-", upload.committedPath(), null, true,
                "Uploaded directory " + upload.name(), Map.of("uploadId", upload.id(), "directory", "true",
                        "filename", upload.name(), "size", Long.toString(upload.totalBytes()),
                        "fileCount", Integer.toString(upload.files().size()),
                        "pendingDecision", Boolean.toString(upload.status() == DirectoryUpload.Status.PENDING)));
    }
    @PreDestroy
    public synchronized void close() { registrations.values().forEach(TemporaryArtifactRegistry.Registration::close); registrations.clear(); }
}
