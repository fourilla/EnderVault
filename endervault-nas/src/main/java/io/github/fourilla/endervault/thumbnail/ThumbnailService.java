package io.github.fourilla.endervault.thumbnail;

import io.github.fourilla.endervault.common.ByteSizeFormatter;
import io.github.fourilla.endervault.common.StorageAccessException;
import io.github.fourilla.endervault.config.NasProperties;
import io.github.fourilla.endervault.filetool.ComicArchiveService;
import io.github.fourilla.endervault.task.TaskContext;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
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
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

@Service
public class ThumbnailService {

    private static final Logger logger = LoggerFactory.getLogger(ThumbnailService.class);
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
    private final boolean videoEnabled;
    private final boolean comicEnabled;
    private final ExecutorService executor;
    private final Set<Path> inProgress = ConcurrentHashMap.newKeySet();
    private final ThumbnailGenerator thumbnailGenerator;

    @Autowired
    public ThumbnailService(NasProperties nasProperties, ComicArchiveService comicArchiveService) {
        NasProperties.Storage storage = nasProperties.getStorage();
        NasProperties.Thumbnails thumbnails = nasProperties.getThumbnails();
        String cacheDirectory = validateDirectoryName(thumbnails.getCacheDirectory());

        this.vaultRoot = storage.getRoot().toAbsolutePath().normalize();
        this.trashRoot = vaultRoot.resolve(storage.getTrashDirectory()).normalize();
        this.metadataRoot = vaultRoot.resolve(storage.getMetadataDirectory()).normalize();
        this.cacheRoot = metadataRoot.resolve(cacheDirectory);
        this.videoCacheRoot = cacheRoot.resolve("videos");
        this.comicCacheRoot = cacheRoot.resolve("comics");
        this.videoPlaceholderFile = cacheRoot.resolve("video-placeholder.png");
        this.comicPlaceholderFile = cacheRoot.resolve("comic-placeholder.png");
        this.videoEnabled = thumbnails.isVideoEnabled();
        this.comicEnabled = thumbnails.isComicEnabled();
        this.executor = Executors.newFixedThreadPool(
                Math.max(1, thumbnails.getGeneratorThreads()),
                thumbnailThreadFactory()
        );
        this.thumbnailGenerator = new ThumbnailGenerator(comicArchiveService);
    }

    ThumbnailService(NasProperties nasProperties) {
        this(nasProperties, new ComicArchiveService(nasProperties));
    }

    @PostConstruct
    public void initialize() throws IOException {
        Files.createDirectories(videoCacheRoot);
        Files.createDirectories(comicCacheRoot);
        if (!Files.exists(videoPlaceholderFile)) {
            thumbnailGenerator.writeVideoPlaceholder(cacheRoot, videoPlaceholderFile);
        }
        if (!Files.exists(comicPlaceholderFile)) {
            thumbnailGenerator.writeComicPlaceholder(cacheRoot, comicPlaceholderFile);
        }
    }

    @PreDestroy
    public void shutdown() {
        executor.shutdownNow();
    }

    public ThumbnailFile videoThumbnail(Path videoFile, String vaultPath) throws IOException {
        if (!videoEnabled) {
            return videoPlaceholder();
        }

        Path cacheFile = videoCacheFile(videoFile, vaultPath);
        if (Files.exists(cacheFile)) {
            return new ThumbnailFile(cacheFile, "image/jpeg", true);
        }

        scheduleGeneration(videoFile.toAbsolutePath().normalize(), cacheFile, thumbnailGenerator::generateVideoThumbnail);
        return videoPlaceholder();
    }

    public ThumbnailFile comicThumbnail(Path comicFile, String vaultPath) throws IOException {
        if (!comicEnabled) {
            return comicPlaceholder();
        }

        Path cacheFile = comicCacheFile(comicFile, vaultPath);
        if (Files.exists(cacheFile)) {
            return new ThumbnailFile(cacheFile, "image/jpeg", true);
        }

        scheduleGeneration(comicFile.toAbsolutePath().normalize(), cacheFile, thumbnailGenerator::generateComicThumbnail);
        return comicPlaceholder();
    }

    public ThumbnailFile thumbnail(Path file, String vaultPath) throws IOException {
        if (isVideoFile(file)) {
            return videoThumbnail(file, vaultPath);
        }
        if (isComicFile(file)) {
            return comicThumbnail(file, vaultPath);
        }
        throw new NoSuchFileException(vaultPath);
    }

    public boolean supportsThumbnail(Path file) throws IOException {
        return isVideoFile(file) || isComicFile(file);
    }

    public ThumbnailCacheScan scanCache() throws IOException {
        return scanCache(null);
    }

    public ThumbnailCacheScan scanCache(TaskContext context) throws IOException {
        Set<Path> expectedFiles = expectedCacheFiles(context);
        List<ThumbnailCacheFile> orphanFiles = new ArrayList<>();
        List<ThumbnailCacheFile> temporaryFiles = new ArrayList<>();

        for (Path thumbnailRoot : List.of(videoCacheRoot, comicCacheRoot)) {
            checkCanceled(context);
            if (!Files.exists(thumbnailRoot)) {
                continue;
            }
            message(context, "Scanning thumbnail cache: " + cacheRoot.relativize(thumbnailRoot).toString().replace('\\', '/'));
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

    public void deleteCacheFile(String relativePath) throws IOException {
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

    public ThumbnailCacheStats cacheStats() throws IOException {
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
                inProgress.size()
        );
    }

    private Set<Path> expectedCacheFiles() throws IOException {
        return expectedCacheFiles(null);
    }

    private Set<Path> expectedCacheFiles(TaskContext context) throws IOException {
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
                if (videoEnabled && isVideoFile(file)) {
                    expected.add(videoCacheFile(file, vaultPath).toAbsolutePath().normalize());
                }
                if (comicEnabled && isComicFile(file)) {
                    expected.add(comicCacheFile(file, vaultPath).toAbsolutePath().normalize());
                }
                return FileVisitResult.CONTINUE;
            }
        });
        return expected;
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

    public void migrateThumbnails(Path currentPath, String oldVaultPath, String newVaultPath) {
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
                migrateSingleThumbnail(normalizedPath, oldVaultPath, newVaultPath);
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
                                childVaultPath(newVaultPath, relativePath)
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

    public void migrateVideoThumbnails(Path currentPath, String oldVaultPath, String newVaultPath) {
        migrateThumbnails(currentPath, oldVaultPath, newVaultPath);
    }

    private ThumbnailFile videoPlaceholder() {
        return new ThumbnailFile(videoPlaceholderFile, "image/png", false);
    }

    private ThumbnailFile comicPlaceholder() {
        return new ThumbnailFile(comicPlaceholderFile, "image/png", false);
    }

    Path videoCacheFile(Path videoFile, String vaultPath) throws IOException {
        return cacheFile(videoCacheRoot, videoFile, vaultPath);
    }

    Path comicCacheFile(Path comicFile, String vaultPath) throws IOException {
        return cacheFile(comicCacheRoot, comicFile, vaultPath);
    }

    private Path cacheFile(Path root, Path file, String vaultPath) throws IOException {
        String key = "%s|%d|%d".formatted(
                vaultPath,
                Files.size(file),
                Files.getLastModifiedTime(file).toMillis()
        );
        return root.resolve(sha256(key) + ".jpg");
    }

    private void migrateSingleThumbnail(Path currentFile, String oldVaultPath, String newVaultPath)
            throws IOException {
        Path cacheRootForFile;
        if (isVideoFile(currentFile)) {
            cacheRootForFile = videoCacheRoot;
        } else if (isComicFile(currentFile)) {
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

    private boolean isVideoFile(Path file) throws IOException {
        String mediaType = Files.probeContentType(file);
        if (mediaType != null && mediaType.startsWith("video/")) {
            return true;
        }
        String name = file.getFileName().toString().toLowerCase(Locale.ROOT);
        return name.endsWith(".mp4")
                || name.endsWith(".m4v")
                || name.endsWith(".mov")
                || name.endsWith(".mkv")
                || name.endsWith(".webm")
                || name.endsWith(".avi");
    }

    private boolean isComicFile(Path file) {
        return file.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".cbz");
    }

    private void scheduleGeneration(Path sourceFile, Path cacheFile, ThumbnailGeneration generation) {
        if (!inProgress.add(cacheFile)) {
            return;
        }

        executor.submit(() -> {
            try {
                generation.generate(sourceFile, cacheFile);
            } catch (Exception ex) {
                logger.warn("Failed to generate thumbnail for {}", sourceFile, ex);
            } finally {
                inProgress.remove(cacheFile);
            }
        });
    }

    static int previewFrame(double durationSeconds, double frameRate, int lengthInFrames) {
        return ThumbnailGenerator.previewFrame(durationSeconds, frameRate, lengthInFrames);
    }

    private String sha256(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 is not available.", ex);
        }
    }

    private ThreadFactory thumbnailThreadFactory() {
        AtomicInteger index = new AtomicInteger();
        return runnable -> {
            Thread thread = new Thread(runnable, "endervault-thumbnail-" + index.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        };
    }

    private String validateDirectoryName(String directoryName) {
        if (directoryName == null || directoryName.isBlank()) {
            throw new StorageAccessException("Thumbnail cache directory is required.");
        }
        if (directoryName.contains("/") || directoryName.contains("\\") || ".".equals(directoryName)
                || "..".equals(directoryName) || directoryName.contains(":")) {
            throw new StorageAccessException("Invalid thumbnail cache directory: " + directoryName);
        }
        return directoryName;
    }

    @FunctionalInterface
    private interface ThumbnailGeneration {
        void generate(Path sourceFile, Path cacheFile) throws IOException;
    }
}
