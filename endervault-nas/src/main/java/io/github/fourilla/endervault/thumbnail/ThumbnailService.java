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
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicInteger;
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
        String cacheFolder = validateFolderName(thumbnails.getCacheFolder());

        this.cacheRoot = storage.getRoot()
                .toAbsolutePath()
                .normalize()
                .resolve(storage.getMetadataFolder())
                .resolve(cacheFolder);
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

    private ThumbnailFile placeholder() {
        return new ThumbnailFile(placeholderFile, "image/png", false);
    }

    private Path videoCacheFile(Path videoFile, String vaultPath) throws IOException {
        String key = "%s|%d|%d".formatted(
                vaultPath,
                Files.size(videoFile),
                Files.getLastModifiedTime(videoFile).toMillis()
        );
        return videoCacheRoot.resolve(sha256(key) + ".jpg");
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
        int lengthInFrames = grabber.getLengthInFrames();
        if (lengthInFrames > 0) {
            grabber.setFrameNumber(Math.max(0, Math.min(lengthInFrames - 1, lengthInFrames / 20)));
        }
    }

    private BufferedImage grabThumbnailFrame(FFmpegFrameGrabber grabber) throws Exception {
        Java2DFrameConverter converter = new Java2DFrameConverter();
        for (int i = 0; i < 30; i++) {
            Frame frame = grabber.grab();
            if (frame != null && frame.image != null) {
                return converter.convert(frame);
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

    private String validateFolderName(String folderName) {
        if (folderName == null || folderName.isBlank()) {
            throw new StorageAccessException("Thumbnail cache folder is required.");
        }
        if (folderName.contains("/") || folderName.contains("\\") || ".".equals(folderName)
                || "..".equals(folderName) || folderName.contains(":")) {
            throw new StorageAccessException("Invalid thumbnail cache folder: " + folderName);
        }
        return folderName;
    }
}
