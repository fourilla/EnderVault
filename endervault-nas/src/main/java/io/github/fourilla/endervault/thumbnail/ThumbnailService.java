package io.github.fourilla.endervault.thumbnail;

import io.github.fourilla.endervault.common.ByteSizeFormatter;
import io.github.fourilla.endervault.common.StorageAccessException;
import io.github.fourilla.endervault.config.NasProperties;
import io.github.fourilla.endervault.filetool.ComicArchiveService;
import io.github.fourilla.endervault.filetool.ComicPageResource;
import io.github.fourilla.endervault.task.TaskContext;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.nio.ByteBuffer;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
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
import javax.imageio.ImageIO;
import org.bytedeco.ffmpeg.global.avutil;
import org.bytedeco.javacv.FFmpegFrameGrabber;
import org.bytedeco.javacv.Frame;
import org.bytedeco.javacv.Java2DFrameConverter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

@Service
public class ThumbnailService {

    private static final Logger logger = LoggerFactory.getLogger(ThumbnailService.class);
    private static final int MAX_WIDTH = 480;
    private static final int MAX_HEIGHT = 270;
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
    private final ComicArchiveService comicArchiveService;

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
        this.comicArchiveService = comicArchiveService;
    }

    ThumbnailService(NasProperties nasProperties) {
        this(nasProperties, new ComicArchiveService(nasProperties));
    }

    @PostConstruct
    public void initialize() throws IOException {
        Files.createDirectories(videoCacheRoot);
        Files.createDirectories(comicCacheRoot);
        if (!Files.exists(videoPlaceholderFile)) {
            writeVideoPlaceholder();
        }
        if (!Files.exists(comicPlaceholderFile)) {
            writeComicPlaceholder();
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

        scheduleGeneration(videoFile.toAbsolutePath().normalize(), cacheFile, this::generateVideoThumbnail);
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

        scheduleGeneration(comicFile.toAbsolutePath().normalize(), cacheFile, this::generateComicThumbnail);
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

    private void generateVideoThumbnail(Path videoFile, Path cacheFile) throws IOException {
        if (!Files.exists(videoFile) || Files.exists(cacheFile)) {
            return;
        }

        Files.createDirectories(cacheFile.getParent());
        Path tempFile = cacheFile.resolveSibling(cacheFile.getFileName() + ".tmp");
        FFmpegFrameGrabber grabber = new FFmpegFrameGrabber(videoFile.toFile());
        try {
            grabber.start();
            seekToPreviewPoint(grabber);

            BufferedImage thumbnail = grabThumbnailFrame(grabber);
            if (thumbnail == null) {
                throw new IOException("No image frame could be read from " + videoFile);
            }

            ImageIO.write(scaleForThumbnail(thumbnail), "jpg", tempFile.toFile());
            moveIntoPlace(tempFile, cacheFile);
        } catch (Exception ex) {
            Files.deleteIfExists(tempFile);
            throw new IOException("Failed to generate thumbnail.", ex);
        } finally {
            try {
                grabber.stop();
            } catch (Exception ex) {
                logger.debug("Failed to stop FFmpegFrameGrabber.", ex);
            }
            try {
                grabber.release();
            } catch (Exception ex) {
                logger.debug("Failed to release FFmpegFrameGrabber.", ex);
            }
        }
    }

    private void generateComicThumbnail(Path comicFile, Path cacheFile) throws IOException {
        if (!Files.exists(comicFile) || Files.exists(cacheFile)) {
            return;
        }

        Files.createDirectories(cacheFile.getParent());
        Path tempFile = cacheFile.resolveSibling(cacheFile.getFileName() + ".tmp");
        try {
            ComicPageResource firstPage = comicArchiveService.openPage(comicFile, 0);
            BufferedImage thumbnail;
            try (InputStream inputStream = firstPage.resource().getInputStream()) {
                thumbnail = decodeComicPage(inputStream.readAllBytes(), firstPage.filename());
            }
            if (thumbnail == null) {
                throw new IOException("No readable image page could be read from " + comicFile);
            }

            ImageIO.write(scaleForThumbnail(thumbnail), "jpg", tempFile.toFile());
            moveIntoPlace(tempFile, cacheFile);
        } catch (Exception ex) {
            Files.deleteIfExists(tempFile);
            throw new IOException("Failed to generate comic thumbnail.", ex);
        }
    }

    private BufferedImage decodeComicPage(byte[] imageBytes, String filename) throws IOException {
        try (ByteArrayInputStream inputStream = new ByteArrayInputStream(imageBytes)) {
            BufferedImage image = ImageIO.read(inputStream);
            if (image != null) {
                return image;
            }
        }
        return decodeImageWithFfmpeg(imageBytes, filename);
    }

    private BufferedImage decodeImageWithFfmpeg(byte[] imageBytes, String filename) throws IOException {
        Path tempImage = Files.createTempFile("endervault-comic-page-", extensionSuffix(filename));
        try {
            Files.write(tempImage, imageBytes);
            return decodeImageFileWithFfmpeg(tempImage);
        } finally {
            Files.deleteIfExists(tempImage);
        }
    }

    private BufferedImage decodeImageFileWithFfmpeg(Path imageFile) throws IOException {
        FFmpegFrameGrabber grabber = new FFmpegFrameGrabber(imageFile.toFile());
        try {
            grabber.setPixelFormat(avutil.AV_PIX_FMT_BGR24);
            grabber.start();
            return grabFfmpegImageFrame(grabber);
        } catch (Exception ex) {
            throw new IOException("Failed to decode image with FFmpeg.", ex);
        } finally {
            try {
                grabber.stop();
            } catch (Exception ex) {
                logger.debug("Failed to stop image FFmpegFrameGrabber.", ex);
            }
            try {
                grabber.release();
            } catch (Exception ex) {
                logger.debug("Failed to release image FFmpegFrameGrabber.", ex);
            }
        }
    }

    private BufferedImage grabFfmpegImageFrame(FFmpegFrameGrabber grabber) throws Exception {
        for (int i = 0; i < 10; i++) {
            Frame frame = grabber.grabImage();
            if (frame != null && frame.image != null) {
                BufferedImage image = bgrFrameToImage(frame);
                if (image != null) {
                    return image;
                }
            }
        }
        return null;
    }

    private BufferedImage bgrFrameToImage(Frame frame) {
        if (frame.image.length == 0
                || !(frame.image[0] instanceof ByteBuffer source)
                || frame.imageWidth <= 0
                || frame.imageHeight <= 0
                || frame.imageStride < frame.imageWidth * 3) {
            return null;
        }

        ByteBuffer buffer = source.duplicate();
        BufferedImage image = new BufferedImage(frame.imageWidth, frame.imageHeight, BufferedImage.TYPE_INT_RGB);
        for (int y = 0; y < frame.imageHeight; y++) {
            int rowStart = y * frame.imageStride;
            for (int x = 0; x < frame.imageWidth; x++) {
                int index = rowStart + (x * 3);
                int blue = buffer.get(index) & 0xff;
                int green = buffer.get(index + 1) & 0xff;
                int red = buffer.get(index + 2) & 0xff;
                image.setRGB(x, y, (red << 16) | (green << 8) | blue);
            }
        }
        return image;
    }

    private String extensionSuffix(String filename) {
        if (filename == null) {
            return ".img";
        }
        int index = filename.lastIndexOf('.');
        if (index < 0 || index == filename.length() - 1) {
            return ".img";
        }
        String extension = filename.substring(index + 1).toLowerCase(Locale.ROOT);
        if (!extension.matches("[a-z0-9]{1,10}")) {
            return ".img";
        }
        return "." + extension;
    }

    private void seekToPreviewPoint(FFmpegFrameGrabber grabber) throws Exception {
        double durationSeconds = grabber.getLengthInTime() / 1_000_000.0;
        double frameRate = grabber.getFrameRate();
        int lengthInFrames = grabber.getLengthInFrames();

        if (lengthInFrames > 0) {
            int targetFrame = previewFrame(durationSeconds, frameRate, lengthInFrames);
            grabber.setFrameNumber(targetFrame);
        }
    }

    static int previewFrame(double durationSeconds, double frameRate, int lengthInFrames) {
        double targetSecond = durationSeconds >= 1.0d ? durationSeconds * 0.05d : durationSeconds / 2.0d;
        int targetFrame = frameRate > 0.0d && Double.isFinite(frameRate)
                ? (int) (targetSecond * frameRate)
                : lengthInFrames / 20;
        if (targetFrame >= lengthInFrames) {
            targetFrame = lengthInFrames / 2;
        }
        return Math.max(0, Math.min(lengthInFrames - 1, targetFrame));
    }

    private BufferedImage grabThumbnailFrame(FFmpegFrameGrabber grabber) throws Exception {
        Java2DFrameConverter converter = new Java2DFrameConverter();
        for (int i = 0; i < 10; i++) {
            Frame frame = grabber.grabImage();
            if (frame != null) {
                BufferedImage image = converter.convert(frame);
                if (image != null) {
                    return image;
                }
            }
        }
        return null;
    }

    private BufferedImage scaleForThumbnail(BufferedImage source) {
        double scale = Math.min(
                (double) MAX_WIDTH / source.getWidth(),
                (double) MAX_HEIGHT / source.getHeight()
        );
        if (scale >= 1.0d) {
            return toRgbImage(source);
        }

        int width = Math.max(1, (int) Math.round(source.getWidth() * scale));
        int height = Math.max(1, (int) Math.round(source.getHeight() * scale));
        BufferedImage scaled = new BufferedImage(width, height, BufferedImage.TYPE_3BYTE_BGR);
        Graphics2D graphics = scaled.createGraphics();
        try {
            graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
            graphics.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
            graphics.drawImage(source, 0, 0, width, height, null);
            return scaled;
        } finally {
            graphics.dispose();
        }
    }

    private BufferedImage toRgbImage(BufferedImage source) {
        BufferedImage image = new BufferedImage(source.getWidth(), source.getHeight(), BufferedImage.TYPE_3BYTE_BGR);
        Graphics2D graphics = image.createGraphics();
        try {
            graphics.drawImage(source, 0, 0, null);
            return image;
        } finally {
            graphics.dispose();
        }
    }

    private void writeVideoPlaceholder() throws IOException {
        Files.createDirectories(cacheRoot);
        BufferedImage image = new BufferedImage(MAX_WIDTH, MAX_HEIGHT, BufferedImage.TYPE_INT_RGB);
        Graphics2D graphics = image.createGraphics();
        try {
            graphics.setColor(new Color(238, 242, 245));
            graphics.fillRect(0, 0, MAX_WIDTH, MAX_HEIGHT);
            graphics.setColor(new Color(33, 110, 112));
            graphics.fillRoundRect(172, 84, 136, 78, 12, 12);
            graphics.setColor(Color.WHITE);
            int[] x = {220, 220, 268};
            int[] y = {104, 142, 123};
            graphics.fillPolygon(x, y, 3);
            graphics.setColor(new Color(101, 113, 132));
            graphics.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 18));
            graphics.drawString("Preparing thumbnail", 146, 194);
        } finally {
            graphics.dispose();
        }
        ImageIO.write(image, "png", videoPlaceholderFile.toFile());
    }

    private void writeComicPlaceholder() throws IOException {
        Files.createDirectories(cacheRoot);
        BufferedImage image = new BufferedImage(MAX_WIDTH, MAX_HEIGHT, BufferedImage.TYPE_INT_RGB);
        Graphics2D graphics = image.createGraphics();
        try {
            graphics.setColor(new Color(238, 242, 245));
            graphics.fillRect(0, 0, MAX_WIDTH, MAX_HEIGHT);
            graphics.setColor(new Color(82, 67, 170));
            graphics.fillRoundRect(162, 62, 156, 116, 14, 14);
            graphics.setColor(new Color(210, 205, 255));
            graphics.fillRect(186, 84, 108, 70);
            graphics.setColor(Color.WHITE);
            graphics.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 34));
            graphics.drawString("CBZ", 200, 132);
            graphics.setColor(new Color(101, 113, 132));
            graphics.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 18));
            graphics.drawString("Preparing thumbnail", 146, 212);
        } finally {
            graphics.dispose();
        }
        ImageIO.write(image, "png", comicPlaceholderFile.toFile());
    }

    private void moveIntoPlace(Path tempFile, Path cacheFile) throws IOException {
        try {
            Files.move(tempFile, cacheFile, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException ex) {
            Files.move(tempFile, cacheFile, StandardCopyOption.REPLACE_EXISTING);
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
