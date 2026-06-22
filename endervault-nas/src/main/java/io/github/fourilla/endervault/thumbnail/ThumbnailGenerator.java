package io.github.fourilla.endervault.thumbnail;

import io.github.fourilla.endervault.filetool.ComicArchiveService;
import io.github.fourilla.endervault.filetool.ComicPageResource;
import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Locale;
import javax.imageio.ImageIO;
import org.bytedeco.ffmpeg.global.avutil;
import org.bytedeco.javacv.FFmpegFrameGrabber;
import org.bytedeco.javacv.Frame;
import org.bytedeco.javacv.Java2DFrameConverter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

final class ThumbnailGenerator {

    private static final Logger logger = LoggerFactory.getLogger(ThumbnailGenerator.class);
    private static final int MAX_WIDTH = 480;
    private static final int MAX_HEIGHT = 270;

    private final ComicArchiveService comicArchiveService;

    ThumbnailGenerator(ComicArchiveService comicArchiveService) {
        this.comicArchiveService = comicArchiveService;
    }

    void generateVideoThumbnail(Path videoFile, Path cacheFile) throws IOException {
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

    void generateComicThumbnail(Path comicFile, Path cacheFile) throws IOException {
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

    void writeVideoPlaceholder(Path cacheRoot, Path videoPlaceholderFile) throws IOException {
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

    void writeComicPlaceholder(Path cacheRoot, Path comicPlaceholderFile) throws IOException {
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

    private void moveIntoPlace(Path tempFile, Path cacheFile) throws IOException {
        try {
            Files.move(tempFile, cacheFile, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException ex) {
            Files.move(tempFile, cacheFile, StandardCopyOption.REPLACE_EXISTING);
        }
    }
}
