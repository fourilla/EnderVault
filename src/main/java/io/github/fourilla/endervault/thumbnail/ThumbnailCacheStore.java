package io.github.fourilla.endervault.thumbnail;

import io.github.fourilla.endervault.common.ByteSizeFormatter;
import io.github.fourilla.endervault.common.StorageAccessException;
import io.github.fourilla.endervault.task.TaskContext;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Stream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

final class ThumbnailCacheStore {

    private static final Logger logger = LoggerFactory.getLogger(ThumbnailCacheStore.class);
    private static final DateTimeFormatter MODIFIED_FORMATTER =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm").withZone(ZoneId.systemDefault());

    private final Path vaultRoot;
    private final Path trashRoot;
    private final Path metadataRoot;
    private final Path cacheRoot;
    private final Path videoCacheRoot;
    private final Path comicCacheRoot;
    private final Path videoPlaceholderFile;
    private final Path comicPlaceholderFile;

    ThumbnailCacheStore(Path vaultRoot, Path trashRoot, Path metadataRoot, String cacheDirectory) {
        this.vaultRoot = vaultRoot;
        this.trashRoot = trashRoot;
        this.metadataRoot = metadataRoot;
        this.cacheRoot = metadataRoot.resolve(cacheDirectory);
        this.videoCacheRoot = cacheRoot.resolve("videos");
        this.comicCacheRoot = cacheRoot.resolve("comics");
        this.videoPlaceholderFile = cacheRoot.resolve("video-placeholder.png");
        this.comicPlaceholderFile = cacheRoot.resolve("comic-placeholder.png");
    }

    void initialize() throws IOException {
        Files.createDirectories(videoCacheRoot);
        Files.createDirectories(comicCacheRoot);
    }

    Path cacheRoot() {
        return cacheRoot;
    }

    Path videoPlaceholderFile() {
        return videoPlaceholderFile;
    }

    Path comicPlaceholderFile() {
        return comicPlaceholderFile;
    }

    ThumbnailFile videoPlaceholder() {
        return new ThumbnailFile(videoPlaceholderFile, "image/png", false);
    }

    ThumbnailFile comicPlaceholder() {
        return new ThumbnailFile(comicPlaceholderFile, "image/png", false);
    }

    Path videoCacheFile(Path videoFile, String vaultPath) throws IOException {
        return cacheFile(videoCacheRoot, videoFile, vaultPath);
    }

    Path comicCacheFile(Path comicFile, String vaultPath) throws IOException {
        return cacheFile(comicCacheRoot, comicFile, vaultPath);
    }

    ThumbnailCacheScan scan(
            TaskContext context,
            ThumbnailClassifier classifier,
            boolean videoEnabled,
            boolean comicEnabled
    ) throws IOException {
        Set<Path> expectedFiles = expectedCacheFiles(context, classifier, videoEnabled, comicEnabled);
        List<ThumbnailCacheFile> orphanFiles = new ArrayList<>();
        List<ThumbnailCacheFile> temporaryFiles = new ArrayList<>();

        for (Path thumbnailRoot : List.of(videoCacheRoot, comicCacheRoot)) {
            checkCanceled(context);
            if (!Files.exists(thumbnailRoot)) {
                continue;
            }
            message(
                    context,
                    "Scanning thumbnail cache: " + cacheRoot.relativize(thumbnailRoot).toString().replace('\\', '/')
            );
            try (Stream<Path> paths = Files.walk(thumbnailRoot)) {
                Iterator<Path> iterator = paths.iterator();
                while (iterator.hasNext()) {
                    Path cacheFile = iterator.next();
                    checkCanceled(context);
                    if (!Files.isRegularFile(cacheFile, LinkOption.NOFOLLOW_LINKS) || Files.isSymbolicLink(cacheFile)) {
                        continue;
                    }
                    String filename = cacheFile.getFileName().toString().toLowerCase(Locale.ROOT);
                    if (filename.endsWith(".tmp")) {
                        temporaryFiles.add(toCacheFile(cacheFile));
                        continue;
                    }
                    if (filename.endsWith(".jpg") && !expectedFiles.contains(cacheFile.toAbsolutePath().normalize())) {
                        orphanFiles.add(toCacheFile(cacheFile));
                    }
                }
            }
        }

        return new ThumbnailCacheScan(
                orphanFiles.stream().sorted(ThumbnailCacheFile::compareTo).toList(),
                temporaryFiles.stream().sorted(ThumbnailCacheFile::compareTo).toList()
        );
    }

    void deleteCacheFile(String relativePath) throws IOException {
        if (relativePath == null || relativePath.isBlank()) {
            throw new StorageAccessException("Thumbnail cache path is required.");
        }
        Path cacheFile = cacheRoot.resolve(relativePath.replace('\\', '/')).normalize();
        Path normalized = cacheFile.toAbsolutePath().normalize();
        if (!normalized.startsWith(videoCacheRoot) && !normalized.startsWith(comicCacheRoot)) {
            throw new StorageAccessException("Path is outside thumbnail cache.");
        }
        if (Files.isRegularFile(normalized, LinkOption.NOFOLLOW_LINKS) && !Files.isSymbolicLink(normalized)) {
            Files.deleteIfExists(normalized);
        }
    }

    ThumbnailCacheStats stats(boolean videoEnabled, boolean comicEnabled, int inProgressCount) throws IOException {
        long cachedFiles = 0L;
        long sizeBytes = 0L;

        for (Path thumbnailRoot : List.of(videoCacheRoot, comicCacheRoot)) {
            if (!Files.exists(thumbnailRoot)) {
                continue;
            }
            try (Stream<Path> paths = Files.walk(thumbnailRoot)) {
                List<Path> files = paths
                        .filter(path -> Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS))
                        .filter(path -> !Files.isSymbolicLink(path))
                        .toList();
                cachedFiles += files.size();
                for (Path file : files) {
                    sizeBytes += Files.size(file);
                }
            }
        }

        return new ThumbnailCacheStats(
                videoEnabled,
                comicEnabled,
                cachedFiles,
                sizeBytes,
                ByteSizeFormatter.humanSize(sizeBytes),
                inProgressCount
        );
    }

    void migrateThumbnails(
            Path currentPath,
            String oldVaultPath,
            String newVaultPath,
            ThumbnailClassifier classifier,
            boolean videoEnabled,
            boolean comicEnabled
    ) {
        if ((!videoEnabled && !comicEnabled)
                || oldVaultPath == null
                || newVaultPath == null
                || oldVaultPath.equals(newVaultPath)) {
            return;
        }

        Path normalizedPath = currentPath.toAbsolutePath().normalize();
        try {
            if (!Files.exists(normalizedPath, LinkOption.NOFOLLOW_LINKS)) {
                return;
            }
            if (Files.isRegularFile(normalizedPath, LinkOption.NOFOLLOW_LINKS)) {
                migrateSingleThumbnail(normalizedPath, oldVaultPath, newVaultPath, classifier);
                return;
            }
            if (!Files.isDirectory(normalizedPath, LinkOption.NOFOLLOW_LINKS)) {
                return;
            }

            try (Stream<Path> paths = Files.walk(normalizedPath)) {
                for (Path path : paths
                        .filter(candidate -> Files.isRegularFile(candidate, LinkOption.NOFOLLOW_LINKS))
                        .filter(candidate -> !Files.isSymbolicLink(candidate))
                        .toList()) {
                    Path relativePath = normalizedPath.relativize(path);
                    try {
                        migrateSingleThumbnail(
                                path,
                                childVaultPath(oldVaultPath, relativePath),
                                childVaultPath(newVaultPath, relativePath),
                                classifier
                        );
                    } catch (IOException ex) {
                        logger.warn("Failed to migrate thumbnail for {}", path, ex);
                    }
                }
            }
        } catch (IOException ex) {
            logger.warn("Failed to migrate thumbnails from {} to {}", oldVaultPath, newVaultPath, ex);
        }
    }

    private Set<Path> expectedCacheFiles(
            TaskContext context,
            ThumbnailClassifier classifier,
            boolean videoEnabled,
            boolean comicEnabled
    ) throws IOException {
        Set<Path> expected = new HashSet<>();
        if (!Files.exists(vaultRoot)) {
            return expected;
        }

        message(context, "Scanning vault for expected thumbnail cache files.");
        Files.walkFileTree(vaultRoot, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult preVisitDirectory(Path directory, BasicFileAttributes attributes) {
                checkCanceled(context);
                if (!directory.equals(vaultRoot) && isVaultSystemPath(directory)) {
                    return FileVisitResult.SKIP_SUBTREE;
                }
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attributes) throws IOException {
                checkCanceled(context);
                if (!attributes.isRegularFile() || Files.isSymbolicLink(file) || isVaultSystemPath(file)) {
                    return FileVisitResult.CONTINUE;
                }
                String vaultPath = vaultRoot.relativize(file).toString().replace('\\', '/');
                if (videoEnabled && classifier.isVideoFile(file)) {
                    expected.add(videoCacheFile(file, vaultPath).toAbsolutePath().normalize());
                }
                if (comicEnabled && classifier.isComicFile(file)) {
                    expected.add(comicCacheFile(file, vaultPath).toAbsolutePath().normalize());
                }
                return FileVisitResult.CONTINUE;
            }
        });
        return expected;
    }

    private boolean isVaultSystemPath(Path path) {
        Path normalized = path.toAbsolutePath().normalize();
        return normalized.startsWith(trashRoot) || normalized.startsWith(metadataRoot);
    }

    private ThumbnailCacheFile toCacheFile(Path path) {
        try {
            long size = Files.size(path);
            Instant modified = Files.getLastModifiedTime(path).toInstant();
            return new ThumbnailCacheFile(
                    cacheRoot.relativize(path).toString().replace('\\', '/'),
                    size,
                    ByteSizeFormatter.humanSize(size),
                    modified,
                    MODIFIED_FORMATTER.format(modified)
            );
        } catch (IOException ex) {
            throw new StorageAccessException("Failed to read thumbnail cache metadata.", ex);
        }
    }

    private Path cacheFile(Path root, Path file, String vaultPath) throws IOException {
        String key = "%s|%d|%d".formatted(
                vaultPath,
                Files.size(file),
                Files.getLastModifiedTime(file).toMillis()
        );
        return root.resolve(sha256(key) + ".jpg");
    }

    private void migrateSingleThumbnail(
            Path currentFile,
            String oldVaultPath,
            String newVaultPath,
            ThumbnailClassifier classifier
    ) throws IOException {
        Path cacheRootForFile;
        if (classifier.isVideoFile(currentFile)) {
            cacheRootForFile = videoCacheRoot;
        } else if (classifier.isComicFile(currentFile)) {
            cacheRootForFile = comicCacheRoot;
        } else {
            return;
        }

        Path oldCacheFile = cacheFile(cacheRootForFile, currentFile, oldVaultPath);
        if (!Files.exists(oldCacheFile)) {
            return;
        }

        Path newCacheFile = cacheFile(cacheRootForFile, currentFile, newVaultPath);
        if (oldCacheFile.equals(newCacheFile)) {
            return;
        }

        Files.createDirectories(newCacheFile.getParent());
        try {
            Files.move(
                    oldCacheFile,
                    newCacheFile,
                    StandardCopyOption.REPLACE_EXISTING,
                    StandardCopyOption.ATOMIC_MOVE
            );
        } catch (AtomicMoveNotSupportedException ex) {
            Files.move(oldCacheFile, newCacheFile, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private String childVaultPath(String baseVaultPath, Path relativePath) {
        String suffix = relativePath.toString().replace('\\', '/');
        if (suffix.isBlank()) {
            return baseVaultPath;
        }
        if (baseVaultPath.isBlank()) {
            return suffix;
        }
        return baseVaultPath + "/" + suffix;
    }

    private void checkCanceled(TaskContext context) {
        if (context != null) {
            context.checkCanceled();
        }
    }

    private void message(TaskContext context, String message) {
        if (context != null) {
            context.message(message);
        }
    }

    private String sha256(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 is not available.", ex);
        }
    }
}
