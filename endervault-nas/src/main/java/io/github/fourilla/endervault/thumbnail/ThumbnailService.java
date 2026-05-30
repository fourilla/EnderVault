package io.github.fourilla.endervault.thumbnail;

import io.github.fourilla.endervault.common.StorageAccessException;
import io.github.fourilla.endervault.config.NasProperties;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Locale;
import java.util.HexFormat;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;
import javax.imageio.ImageIO;
import org.bytedeco.javacv.FFmpegFrameGrabber;
import org.bytedeco.javacv.Frame;
import org.bytedeco.javacv.Java2DFrameConverter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

@Service
public class ThumbnailService {

    private static final Logger logger = LoggerFactory.getLogger(ThumbnailService.class);
    private static final int MAX_WIDTH = 480;
    private static final int MAX_HEIGHT = 270;

    private final Path cacheRoot;
    private final Path videoCacheRoot;
    private final Path placeholderFile;
    private final boolean videoEnabled;
    private final ExecutorService executor;
    private final Set<Path> inProgress = ConcurrentHashMap.newKeySet();

    public ThumbnailService(NasProperties nasProperties) {
        NasProperties.Storage storage = nasProperties.getStorage();
        NasProperties.Thumbnails thumbnails = nasProperties.getThumbnails();
        String cacheDirectory = validateDirectoryName(thumbnails.getCacheDirectory());

        this.cacheRoot = storage.getRoot()
                .toAbsolutePath()
                .normalize()
                .resolve(storage.getMetadataDirectory())
                .resolve(cacheDirectory);
        this.videoCacheRoot = cacheRoot.resolve("videos");
        this.placeholderFile = cacheRoot.resolve("video-placeholder.png");
        this.videoEnabled = thumbnails.isVideoEnabled();
        this.executor = Executors.newFixedThreadPool(
                Math.max(1, thumbnails.getGeneratorThreads()),
                thumbnailThreadFactory()
        );
    }

    @PostConstruct
    public void initialize() throws IOException {
        Files.createDirectories(videoCacheRoot);
        if (!Files.exists(placeholderFile)) {
            writePlaceholder();
        }
    }

    @PreDestroy
    public void shutdown() {
        executor.shutdownNow();
    }

    public ThumbnailFile videoThumbnail(Path videoFile, String vaultPath) throws IOException {
        if (!videoEnabled) {
            return placeholder();
        }

        Path cacheFile = videoCacheFile(videoFile, vaultPath);
        if (Files.exists(cacheFile)) {
            return new ThumbnailFile(cacheFile, "image/jpeg", true);
        }

        scheduleGeneration(videoFile.toAbsolutePath().normalize(), cacheFile);
        return placeholder();
    }

    public void migrateVideoThumbnails(Path currentPath, String oldVaultPath, String newVaultPath) {
        if (!videoEnabled || oldVaultPath == null || newVaultPath == null || oldVaultPath.equals(newVaultPath)) {
            return;
        }

        Path normalizedPath = currentPath.toAbsolutePath().normalize();
        try {
            if (!Files.exists(normalizedPath, LinkOption.NOFOLLOW_LINKS)) {
                return;
            }
            if (Files.isRegularFile(normalizedPath, LinkOption.NOFOLLOW_LINKS)) {
                migrateSingleVideoThumbnail(normalizedPath, oldVaultPath, newVaultPath);
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
                        migrateSingleVideoThumbnail(
                                path,
                                childVaultPath(oldVaultPath, relativePath),
                                childVaultPath(newVaultPath, relativePath)
                        );
                    } catch (IOException ex) {
                        logger.warn("Failed to migrate video thumbnail for {}", path, ex);
                    }
                }
            }
        } catch (IOException ex) {
            logger.warn("Failed to migrate video thumbnails from {} to {}", oldVaultPath, newVaultPath, ex);
        }
    }

    private ThumbnailFile placeholder() {
        return new ThumbnailFile(placeholderFile, "image/png", false);
    }

    Path videoCacheFile(Path videoFile, String vaultPath) throws IOException {
        String key = "%s|%d|%d".formatted(
                vaultPath,
                Files.size(videoFile),
                Files.getLastModifiedTime(videoFile).toMillis()
        );
        return videoCacheRoot.resolve(sha256(key) + ".jpg");
    }

    private void migrateSingleVideoThumbnail(Path currentVideoFile, String oldVaultPath, String newVaultPath)
            throws IOException {
        if (!isVideoFile(currentVideoFile)) {
            return;
        }

        Path oldCacheFile = videoCacheFile(currentVideoFile, oldVaultPath);
        if (!Files.exists(oldCacheFile)) {
            return;
        }

        Path newCacheFile = videoCacheFile(currentVideoFile, newVaultPath);
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

    private void scheduleGeneration(Path videoFile, Path cacheFile) {
        if (!inProgress.add(cacheFile)) {
            return;
        }

        executor.submit(() -> {
            try {
                generateVideoThumbnail(videoFile, cacheFile);
            } catch (Exception ex) {
                logger.warn("Failed to generate video thumbnail for {}", videoFile, ex);
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

    private void writePlaceholder() throws IOException {
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
        ImageIO.write(image, "png", placeholderFile.toFile());
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
}
