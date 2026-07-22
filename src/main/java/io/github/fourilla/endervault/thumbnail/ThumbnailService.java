package io.github.fourilla.endervault.thumbnail;

import io.github.fourilla.endervault.common.StorageAccessException;
import io.github.fourilla.endervault.config.NasProperties;
import io.github.fourilla.endervault.filetool.comic.ComicArchiveService;
import io.github.fourilla.endervault.task.TaskContext;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicInteger;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

@Service
public class ThumbnailService {

    private static final Logger logger = LoggerFactory.getLogger(ThumbnailService.class);

    private final boolean videoEnabled;
    private final boolean comicEnabled;
    private final boolean pdfEnabled;
    private final ExecutorService executor;
    private final Set<Path> inProgress = ConcurrentHashMap.newKeySet();
    private final ThumbnailCacheStore cacheStore;
    private final ThumbnailClassifier classifier = new ThumbnailClassifier();
    private final ThumbnailGenerator thumbnailGenerator;

    @Autowired
    public ThumbnailService(NasProperties nasProperties, ComicArchiveService comicArchiveService) {
        NasProperties.Storage storage = nasProperties.getStorage();
        NasProperties.Thumbnails thumbnails = nasProperties.getThumbnails();
        String cacheDirectory = validateDirectoryName(thumbnails.getCacheDirectory());

        Path vaultRoot = storage.getRoot().toAbsolutePath().normalize();
        Path trashRoot = vaultRoot.resolve(storage.getTrashDirectory()).normalize();
        Path metadataRoot = vaultRoot.resolve(storage.getMetadataDirectory()).normalize();
        this.cacheStore = new ThumbnailCacheStore(vaultRoot, trashRoot, metadataRoot, cacheDirectory);
        this.videoEnabled = thumbnails.isVideoEnabled();
        this.comicEnabled = thumbnails.isComicEnabled();
        this.pdfEnabled = thumbnails.isPdfEnabled();
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
        cacheStore.initialize();
        if (!Files.exists(cacheStore.videoPlaceholderFile())) {
            thumbnailGenerator.writeVideoPlaceholder(cacheStore.cacheRoot(), cacheStore.videoPlaceholderFile());
        }
        if (!Files.exists(cacheStore.comicPlaceholderFile())) {
            thumbnailGenerator.writeComicPlaceholder(cacheStore.cacheRoot(), cacheStore.comicPlaceholderFile());
        }
        if (!Files.exists(cacheStore.pdfPlaceholderFile())) {
            thumbnailGenerator.writePdfPlaceholder(cacheStore.cacheRoot(), cacheStore.pdfPlaceholderFile());
        }
    }

    @PreDestroy
    public void shutdown() {
        executor.shutdownNow();
    }

    public ThumbnailFile videoThumbnail(Path videoFile, String vaultPath) throws IOException {
        if (!videoEnabled) {
            return cacheStore.videoPlaceholder();
        }

        Path cacheFile = cacheStore.videoCacheFile(videoFile, vaultPath);
        if (Files.exists(cacheFile)) {
            return new ThumbnailFile(cacheFile, "image/jpeg", true);
        }

        scheduleGeneration(videoFile.toAbsolutePath().normalize(), cacheFile, thumbnailGenerator::generateVideoThumbnail);
        return cacheStore.videoPlaceholder();
    }

    public ThumbnailFile comicThumbnail(Path comicFile, String vaultPath) throws IOException {
        if (!comicEnabled) {
            return cacheStore.comicPlaceholder();
        }

        Path cacheFile = cacheStore.comicCacheFile(comicFile, vaultPath);
        if (Files.exists(cacheFile)) {
            return new ThumbnailFile(cacheFile, "image/jpeg", true);
        }

        scheduleGeneration(comicFile.toAbsolutePath().normalize(), cacheFile, thumbnailGenerator::generateComicThumbnail);
        return cacheStore.comicPlaceholder();
    }

    public ThumbnailFile pdfThumbnail(Path pdfFile, String vaultPath) throws IOException {
        if (!pdfEnabled) {
            return cacheStore.pdfPlaceholder();
        }

        Path cacheFile = cacheStore.pdfCacheFile(pdfFile, vaultPath);
        if (Files.exists(cacheFile)) {
            return new ThumbnailFile(cacheFile, "image/jpeg", true);
        }

        scheduleGeneration(pdfFile.toAbsolutePath().normalize(), cacheFile, thumbnailGenerator::generatePdfThumbnail);
        return cacheStore.pdfPlaceholder();
    }

    public ThumbnailFile thumbnail(Path file, String vaultPath) throws IOException {
        if (classifier.isVideoFile(file)) {
            return videoThumbnail(file, vaultPath);
        }
        if (classifier.isComicFile(file)) {
            return comicThumbnail(file, vaultPath);
        }
        if (classifier.isPdfFile(file)) {
            return pdfThumbnail(file, vaultPath);
        }
        throw new NoSuchFileException(vaultPath);
    }

    public boolean supportsThumbnail(Path file) throws IOException {
        return classifier.supports(file);
    }

    public ThumbnailCacheScan scanCache() throws IOException {
        return scanCache(null);
    }

    public ThumbnailCacheScan scanCache(TaskContext context) throws IOException {
        return cacheStore.scan(context, classifier, videoEnabled, comicEnabled, pdfEnabled);
    }

    public void deleteCacheFile(String relativePath) throws IOException {
        cacheStore.deleteCacheFile(relativePath);
    }

    public ThumbnailCacheStats cacheStats() throws IOException {
        return cacheStore.stats(videoEnabled, comicEnabled, pdfEnabled, inProgress.size());
    }

    public void migrateThumbnails(Path currentPath, String oldVaultPath, String newVaultPath) {
        cacheStore.migrateThumbnails(
                currentPath,
                oldVaultPath,
                newVaultPath,
                classifier,
                videoEnabled,
                comicEnabled,
                pdfEnabled
        );
    }

    public void migrateVideoThumbnails(Path currentPath, String oldVaultPath, String newVaultPath) {
        migrateThumbnails(currentPath, oldVaultPath, newVaultPath);
    }

    Path videoCacheFile(Path videoFile, String vaultPath) throws IOException {
        return cacheStore.videoCacheFile(videoFile, vaultPath);
    }

    Path comicCacheFile(Path comicFile, String vaultPath) throws IOException {
        return cacheStore.comicCacheFile(comicFile, vaultPath);
    }

    Path pdfCacheFile(Path pdfFile, String vaultPath) throws IOException {
        return cacheStore.pdfCacheFile(pdfFile, vaultPath);
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
