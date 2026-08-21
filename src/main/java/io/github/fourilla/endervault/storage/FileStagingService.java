package io.github.fourilla.endervault.storage;

import io.github.fourilla.endervault.common.ByteSizeFormatter;
import io.github.fourilla.endervault.common.StorageAccessException;
import io.github.fourilla.endervault.temporary.TemporaryArtifactRegistry;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;

final class FileStagingService {

    private static final DateTimeFormatter MODIFIED_FORMATTER =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm").withZone(ZoneId.systemDefault());

    private final Path fileStagingRoot;
    private final StoragePathResolver pathResolver;
    private final TemporaryArtifactRegistry temporaryArtifactRegistry;

    FileStagingService(
            Path fileStagingRoot,
            StoragePathResolver pathResolver,
            TemporaryArtifactRegistry temporaryArtifactRegistry
    ) {
        this.fileStagingRoot = fileStagingRoot;
        this.pathResolver = pathResolver;
        this.temporaryArtifactRegistry = temporaryArtifactRegistry;
    }

    Path createTemporaryFile(String prefix, String suffix) throws IOException {
        Files.createDirectories(fileStagingRoot);
        return Files.createTempFile(fileStagingRoot, prefix, suffix);
    }

    Path resumableProtocolRoot() throws IOException {
        Path protocolRoot = fileStagingRoot.resolve("resumable-protocol").normalize();
        pathResolver.ensureInsideFileStagingRoot(protocolRoot);
        if (Files.isSymbolicLink(protocolRoot)) {
            throw new StorageAccessException("Resumable upload storage cannot be a symbolic link.");
        }
        Files.createDirectories(protocolRoot);
        if (!Files.isDirectory(protocolRoot, LinkOption.NOFOLLOW_LINKS)) {
            throw new StorageAccessException("Resumable upload storage is not a directory.");
        }
        return protocolRoot;
    }

    Path claimResumableUpload(Path protocolData, String sessionId) throws IOException {
        if (sessionId == null || !sessionId.matches("[0-9a-fA-F-]{36}")) {
            throw new StorageAccessException("Invalid resumable upload session id.");
        }
        Path protocolRoot = resumableProtocolRoot().toAbsolutePath().normalize();
        Path source = protocolData.toAbsolutePath().normalize();
        if (!source.startsWith(protocolRoot) || source.equals(protocolRoot)) {
            throw new StorageAccessException("Resumable upload data is outside protocol storage.");
        }
        if (!Files.isRegularFile(source, LinkOption.NOFOLLOW_LINKS) || Files.isSymbolicLink(source)) {
            throw new StorageAccessException("Resumable upload data is not a regular file.");
        }

        Files.createDirectories(fileStagingRoot);
        Path target = resumableStagingFile(sessionId);
        if (Files.exists(target, LinkOption.NOFOLLOW_LINKS)) {
            if (Files.isRegularFile(target, LinkOption.NOFOLLOW_LINKS)
                    && !Files.isSymbolicLink(target)
                    && Files.size(target) == Files.size(source)) {
                Files.deleteIfExists(source);
                return target;
            }
            throw new StorageAccessException("Resumable upload staging target already exists.");
        }
        try {
            return Files.move(source, target, StandardCopyOption.ATOMIC_MOVE);
        } catch (java.nio.file.AtomicMoveNotSupportedException ex) {
            return Files.move(source, target);
        }
    }

    Path resumableStagingFile(String sessionId) {
        if (sessionId == null || !sessionId.matches("[0-9a-fA-F-]{36}")) {
            throw new StorageAccessException("Invalid resumable upload session id.");
        }
        return resolveFile("resumable-" + sessionId + ".tmp");
    }

    String filename(Path path) {
        Path normalized = path.toAbsolutePath().normalize();
        pathResolver.ensureInsideFileStagingRoot(normalized);
        if (!normalized.getParent().equals(fileStagingRoot)) {
            throw new StorageAccessException("File staging paths must identify a direct child.");
        }
        return normalized.getFileName().toString();
    }

    Path resolveFile(String filename) {
        pathResolver.validateSingleName(filename);
        Path resolved = fileStagingRoot.resolve(filename).normalize();
        pathResolver.ensureInsideFileStagingRoot(resolved);
        return resolved;
    }

    List<StorageService.FileStagingInfo> listFiles() throws IOException {
        Files.createDirectories(fileStagingRoot);
        try (Stream<Path> stream = Files.list(fileStagingRoot)) {
            return stream
                    .filter(path -> Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS))
                    .filter(path -> !Files.isSymbolicLink(path))
                    .map(this::toFileStagingInfo)
                    .sorted(Comparator.comparing(StorageService.FileStagingInfo::modifiedAt).reversed())
                    .toList();
        }
    }

    void deleteFile(String filename) throws IOException {
        pathResolver.validateSingleName(filename);
        Path temporaryFile = fileStagingRoot.resolve(filename).normalize();
        pathResolver.ensureInsideFileStagingRoot(temporaryFile);
        if (temporaryArtifactRegistry.isActive(temporaryFile)) {
            throw new StorageAccessException("File staging artifact is still in use.");
        }
        if (Files.isRegularFile(temporaryFile, LinkOption.NOFOLLOW_LINKS)
                && !Files.isSymbolicLink(temporaryFile)) {
            Files.deleteIfExists(temporaryFile);
        }
    }

    private StorageService.FileStagingInfo toFileStagingInfo(Path path) {
        try {
            long size = Files.size(path);
            Instant modified = Files.getLastModifiedTime(path).toInstant();
            String activeOperation = temporaryArtifactRegistry.find(path)
                    .map(artifact -> artifact.type().label())
                    .orElse(null);
            return new StorageService.FileStagingInfo(
                    path.getFileName().toString(),
                    size,
                    ByteSizeFormatter.humanSize(size),
                    modified,
                    MODIFIED_FORMATTER.format(modified),
                    activeOperation != null,
                    activeOperation
            );
        } catch (IOException ex) {
            throw new StorageAccessException("Failed to read file staging metadata.", ex);
        }
    }

}
