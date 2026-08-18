package io.github.fourilla.endervault.storage;

import io.github.fourilla.endervault.common.ByteSizeFormatter;
import io.github.fourilla.endervault.common.StorageAccessException;
import io.github.fourilla.endervault.temporary.TemporaryArtifactRegistry;
import io.github.fourilla.endervault.temporary.TemporaryArtifactType;
import java.io.IOException;
import java.io.InputStream;
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
import org.springframework.web.multipart.MultipartFile;

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

    StorageService.StagedUpload stageUpload(MultipartFile file) throws IOException {
        if (file.isEmpty()) {
            return null;
        }
        String filename = safeSubmittedFilename(file);
        Files.createDirectories(fileStagingRoot);
        Path temporaryFile = Files.createTempFile(fileStagingRoot, "upload-", ".tmp");
        boolean staged = false;
        TemporaryArtifactRegistry.Registration registration = temporaryArtifactRegistry.register(
                temporaryFile,
                TemporaryArtifactType.UPLOAD,
                filename
        );
        try (InputStream inputStream = file.getInputStream()) {
            Files.copy(inputStream, temporaryFile, StandardCopyOption.REPLACE_EXISTING);
            StorageService.StagedUpload stagedUpload =
                    new StorageService.StagedUpload(temporaryFile, filename, Files.size(temporaryFile));
            staged = true;
            return stagedUpload;
        } finally {
            try {
                if (!staged) {
                    Files.deleteIfExists(temporaryFile);
                }
            } finally {
                registration.close();
            }
        }
    }

    Path createTemporaryFile(String prefix, String suffix) throws IOException {
        Files.createDirectories(fileStagingRoot);
        return Files.createTempFile(fileStagingRoot, prefix, suffix);
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

    private String safeSubmittedFilename(MultipartFile file) {
        String originalFilename = file.getOriginalFilename();
        if (originalFilename == null || originalFilename.isBlank()) {
            throw new StorageAccessException("Uploaded file name is blank.");
        }
        String cleaned = originalFilename.replace('\\', '/');
        String filename = Path.of(cleaned).getFileName().toString();
        pathResolver.validateSingleName(filename);
        return filename;
    }
}
