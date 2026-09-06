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

    Path claimTemporaryFile(Path sourceFile, String prefix, String suffix) throws IOException {
        Path source = sourceFile.toAbsolutePath().normalize();
        if (!Files.isRegularFile(source, LinkOption.NOFOLLOW_LINKS) || Files.isSymbolicLink(source)) {
            throw new StorageAccessException("Temporary source is not a regular file.");
        }
        Files.createDirectories(fileStagingRoot);
        Path target = Files.createTempFile(fileStagingRoot, prefix, suffix);
        try {
            try {
                return Files.move(
                        source,
                        target,
                        StandardCopyOption.ATOMIC_MOVE,
                        StandardCopyOption.REPLACE_EXISTING
                );
            } catch (java.nio.file.AtomicMoveNotSupportedException ex) {
                return Files.move(source, target, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException | RuntimeException ex) {
            Files.deleteIfExists(target);
            throw ex;
        }
    }

    Path resumableStagingFile(String sessionId) {
        if (sessionId == null || !sessionId.matches("[0-9a-fA-F-]{36}")) {
            throw new StorageAccessException("Invalid resumable upload session id.");
        }
        return resolveFile("resumable-" + sessionId + ".tmp");
    }

    String filename(Path path) {
        requireSafeRoot();
        Path normalized = path.toAbsolutePath().normalize();
        pathResolver.ensureInsideFileStagingRoot(normalized);
        if (!normalized.getParent().equals(fileStagingRoot)) {
            throw new StorageAccessException("File staging paths must identify a direct child.");
        }
        rejectProtocolRoot(normalized.getFileName().toString());
        return normalized.getFileName().toString();
    }

    Path resolveFile(String filename) {
        requireSafeRoot();
        pathResolver.validateSingleName(filename);
        rejectProtocolRoot(filename);
        Path resolved = fileStagingRoot.resolve(filename).normalize();
        pathResolver.ensureInsideFileStagingRoot(resolved);
        return resolved;
    }

    List<StorageService.FileStagingInfo> listFiles() throws IOException {
        requireSafeRoot();
        Files.createDirectories(fileStagingRoot);
        try (Stream<Path> stream = Files.list(fileStagingRoot)) {
            return stream
                    .filter(path -> !path.getFileName().toString().equalsIgnoreCase("resumable-protocol"))
                    .filter(path -> Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)
                            || Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS))
                    .filter(path -> !Files.isSymbolicLink(path))
                    .map(this::toFileStagingInfo)
                    .sorted(Comparator.comparing(StorageService.FileStagingInfo::modifiedAt).reversed())
                    .toList();
        }
    }

    void deleteFile(String filename) throws IOException {
        requireSafeRoot();
        pathResolver.validateSingleName(filename);
        rejectProtocolRoot(filename);
        Path temporaryFile = fileStagingRoot.resolve(filename).normalize();
        pathResolver.ensureInsideFileStagingRoot(temporaryFile);
        assertInactive(temporaryFile);
        if (Files.isDirectory(temporaryFile, LinkOption.NOFOLLOW_LINKS)
                && !Files.isSymbolicLink(temporaryFile)) {
            Files.walkFileTree(temporaryFile, new java.nio.file.SimpleFileVisitor<>() {
                @Override
                public java.nio.file.FileVisitResult visitFile(Path file,
                        java.nio.file.attribute.BasicFileAttributes attributes) throws IOException {
                    assertInactive(file);
                    Files.delete(file);
                    return java.nio.file.FileVisitResult.CONTINUE;
                }

                @Override
                public java.nio.file.FileVisitResult postVisitDirectory(Path directory, IOException failure)
                        throws IOException {
                    if (failure != null) throw failure;
                    assertInactive(directory);
                    Files.delete(directory);
                    return java.nio.file.FileVisitResult.CONTINUE;
                }
            });
        } else if (Files.isRegularFile(temporaryFile, LinkOption.NOFOLLOW_LINKS)
                && !Files.isSymbolicLink(temporaryFile)) {
            Files.deleteIfExists(temporaryFile);
        }
    }

    private void assertInactive(Path path) {
        if (temporaryArtifactRegistry.activeArtifacts().stream()
                .anyMatch(artifact -> artifact.path().startsWith(path) || path.startsWith(artifact.path()))) {
            throw new StorageAccessException("File staging artifact is still in use.");
        }
    }

    private void requireSafeRoot() {
        if (Files.isSymbolicLink(fileStagingRoot) || Files.isSymbolicLink(fileStagingRoot.getParent())) {
            throw new StorageAccessException("File staging storage cannot be a symbolic link.");
        }
    }

    private void rejectProtocolRoot(String filename) {
        if ("resumable-protocol".equalsIgnoreCase(filename)) {
            throw new StorageAccessException("Resumable protocol storage is not a staging unit.");
        }
    }

    private StorageService.FileStagingInfo toFileStagingInfo(Path path) {
        try {
            boolean directory = Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS);
            long size = directory ? 0 : Files.size(path);
            Instant modified = Files.getLastModifiedTime(path).toInstant();
            String activeOperation = temporaryArtifactRegistry.activeArtifacts().stream()
                    .filter(artifact -> artifact.path().startsWith(path) || path.startsWith(artifact.path()))
                    .findFirst()
                    .map(artifact -> artifact.type().label())
                    .orElse(null);
            return new StorageService.FileStagingInfo(
                    path.getFileName().toString(),
                    size,
                    directory ? "Directory" : ByteSizeFormatter.humanSize(size),
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
