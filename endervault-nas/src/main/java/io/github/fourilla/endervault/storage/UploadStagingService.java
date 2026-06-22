package io.github.fourilla.endervault.storage;

import io.github.fourilla.endervault.common.ByteSizeFormatter;
import io.github.fourilla.endervault.common.StorageAccessException;
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

final class UploadStagingService {

    private static final DateTimeFormatter MODIFIED_FORMATTER =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm").withZone(ZoneId.systemDefault());

    private final Path uploadTempRoot;
    private final StoragePathResolver pathResolver;

    UploadStagingService(Path uploadTempRoot, StoragePathResolver pathResolver) {
        this.uploadTempRoot = uploadTempRoot;
        this.pathResolver = pathResolver;
    }

    StorageService.StagedUpload stageUpload(MultipartFile file) throws IOException {
        if (file.isEmpty()) {
            return null;
        }
        String filename = safeSubmittedFilename(file);
        Files.createDirectories(uploadTempRoot);
        Path temporaryFile = Files.createTempFile(uploadTempRoot, "upload-", ".tmp");
        boolean staged = false;
        try (InputStream inputStream = file.getInputStream()) {
            Files.copy(inputStream, temporaryFile, StandardCopyOption.REPLACE_EXISTING);
            StorageService.StagedUpload stagedUpload =
                    new StorageService.StagedUpload(temporaryFile, filename, Files.size(temporaryFile));
            staged = true;
            return stagedUpload;
        } finally {
            if (!staged) {
                Files.deleteIfExists(temporaryFile);
            }
        }
    }

    Path createTemporaryFile(String prefix, String suffix) throws IOException {
        Files.createDirectories(uploadTempRoot);
        return Files.createTempFile(uploadTempRoot, prefix, suffix);
    }

    List<StorageService.TemporaryFileInfo> listTemporaryFiles() throws IOException {
        Files.createDirectories(uploadTempRoot);
        try (Stream<Path> stream = Files.list(uploadTempRoot)) {
            return stream
                    .filter(path -> Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS))
                    .filter(path -> !Files.isSymbolicLink(path))
                    .map(this::toTemporaryFileInfo)
                    .sorted(Comparator.comparing(StorageService.TemporaryFileInfo::modifiedAt).reversed())
                    .toList();
        }
    }

    void deleteTemporaryFile(String filename) throws IOException {
        pathResolver.validateSingleName(filename);
        Path temporaryFile = uploadTempRoot.resolve(filename).normalize();
        pathResolver.ensureInsideUploadTempRoot(temporaryFile);
        if (Files.isRegularFile(temporaryFile, LinkOption.NOFOLLOW_LINKS)
                && !Files.isSymbolicLink(temporaryFile)) {
            Files.deleteIfExists(temporaryFile);
        }
    }

    private StorageService.TemporaryFileInfo toTemporaryFileInfo(Path path) {
        try {
            long size = Files.size(path);
            Instant modified = Files.getLastModifiedTime(path).toInstant();
            return new StorageService.TemporaryFileInfo(
                    path.getFileName().toString(),
                    size,
                    ByteSizeFormatter.humanSize(size),
                    modified,
                    MODIFIED_FORMATTER.format(modified)
            );
        } catch (IOException ex) {
            throw new StorageAccessException("Failed to read temporary upload metadata.", ex);
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
