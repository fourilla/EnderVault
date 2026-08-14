package io.github.fourilla.endervault.storage;

import io.github.fourilla.endervault.common.StorageAccessException;
import io.github.fourilla.endervault.temporary.TemporaryArtifactRegistry;
import io.github.fourilla.endervault.temporary.TemporaryArtifactType;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.stream.Collectors;
import java.util.stream.Stream;

final class StorageTreeOperations {

    private final StoragePathResolver pathResolver;
    private final FileStagingService fileStagingService;
    private final TemporaryArtifactRegistry temporaryArtifactRegistry;

    StorageTreeOperations(
            StoragePathResolver pathResolver,
            FileStagingService fileStagingService,
            TemporaryArtifactRegistry temporaryArtifactRegistry
    ) {
        this.pathResolver = pathResolver;
        this.fileStagingService = fileStagingService;
        this.temporaryArtifactRegistry = temporaryArtifactRegistry;
    }

    void move(Path source, Path target) throws IOException {
        move(source, target, false);
    }

    void move(Path source, Path target, boolean overwrite) throws IOException {
        StandardCopyOption[] options = overwrite
                ? new StandardCopyOption[] { StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING }
                : new StandardCopyOption[] { StandardCopyOption.ATOMIC_MOVE };
        try {
            Files.move(source, target, options);
        } catch (AtomicMoveNotSupportedException ex) {
            if (overwrite) {
                Files.move(source, target, StandardCopyOption.REPLACE_EXISTING);
            } else {
                Files.move(source, target);
            }
        }
    }

    void copyDirectory(Path source, Path target, StorageProgressListener progressListener) throws IOException {
        StorageProgressListener progress = progressListener == null ? StorageProgressListener.NOOP : progressListener;
        try {
            Files.walkFileTree(source, new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult preVisitDirectory(Path directory, BasicFileAttributes attributes)
                        throws IOException {
                    progress.checkCanceled();
                    rejectSymbolicLink(directory);
                    Path relative = source.relativize(directory);
                    Path targetDirectory = target.resolve(relative).normalize();
                    pathResolver.ensureInsideBase(StorageScope.VAULT, targetDirectory);
                    Files.createDirectory(targetDirectory);
                    progress.onItemProcessed();
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attributes) throws IOException {
                    progress.checkCanceled();
                    rejectSymbolicLink(file);
                    Path relative = source.relativize(file);
                    Path targetFile = target.resolve(relative).normalize();
                    pathResolver.ensureInsideBase(StorageScope.VAULT, targetFile);
                    copyFile(file, targetFile, progress, false);
                    return FileVisitResult.CONTINUE;
                }
            });
        } catch (IOException | RuntimeException ex) {
            if (Files.exists(target, LinkOption.NOFOLLOW_LINKS)) {
                deleteRecursively(target);
            }
            throw ex;
        }
    }

    void copyFile(Path source, Path target, StorageProgressListener progressListener, boolean overwrite)
            throws IOException {
        StorageProgressListener progress = progressListener == null ? StorageProgressListener.NOOP : progressListener;
        if (!overwrite) {
            copyFileDirect(source, target, progress);
            return;
        }

        Path temporaryFile = fileStagingService.createTemporaryFile("copy-overwrite-", ".tmp");
        TemporaryArtifactRegistry.Registration registration = temporaryArtifactRegistry.register(
                temporaryFile,
                TemporaryArtifactType.FILE_COPY,
                target.toString()
        );
        try {
            Files.deleteIfExists(temporaryFile);
            copyFileDirect(source, temporaryFile, progress);
            move(temporaryFile, target, true);
            temporaryFile = null;
        } finally {
            try {
                if (temporaryFile != null) {
                    Files.deleteIfExists(temporaryFile);
                }
            } finally {
                registration.close();
            }
        }
    }

    StorageOperationSummary summarize(Path path) throws IOException {
        rejectSymbolicLink(path);
        if (Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) {
            return new StorageOperationSummary(Files.size(path), 1L);
        }

        final long[] totalBytes = {0L};
        final long[] totalItems = {0L};
        Files.walkFileTree(path, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult preVisitDirectory(Path directory, BasicFileAttributes attributes)
                    throws IOException {
                rejectSymbolicLink(directory);
                totalItems[0]++;
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attributes) throws IOException {
                rejectSymbolicLink(file);
                totalItems[0]++;
                totalBytes[0] += attributes.size();
                return FileVisitResult.CONTINUE;
            }
        });
        return new StorageOperationSummary(totalBytes[0], totalItems[0]);
    }

    void deleteRecursively(Path path) throws IOException {
        if (Files.isSymbolicLink(path)) {
            Files.delete(path);
            return;
        }
        if (Files.isDirectory(path)) {
            try (Stream<Path> children = Files.list(path)) {
                for (Path child : children.collect(Collectors.toList())) {
                    deleteRecursively(child);
                }
            }
        }
        Files.deleteIfExists(path);
    }

    void rejectSymbolicLink(Path path) {
        if (Files.isSymbolicLink(path)) {
            throw new StorageAccessException("Symbolic links are not supported by this storage operation.");
        }
    }

    private void copyFileDirect(Path source, Path target, StorageProgressListener progress) throws IOException {
        progress.checkCanceled();
        try (InputStream inputStream = Files.newInputStream(source);
                OutputStream outputStream = Files.newOutputStream(target, StandardOpenOption.CREATE_NEW)) {
            byte[] buffer = new byte[64 * 1024];
            int read;
            while ((read = inputStream.read(buffer)) != -1) {
                progress.checkCanceled();
                outputStream.write(buffer, 0, read);
                progress.onBytesProcessed(read);
            }
        }
        progress.onItemProcessed();
    }
}
