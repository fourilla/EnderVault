package io.github.fourilla.endervault.thumbnail;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.fourilla.endervault.config.NasProperties;
import java.awt.Color;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Base64;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ThumbnailServiceTest {

    @TempDir
    Path root;

    private ThumbnailService thumbnailService;

    @BeforeEach
    void setUp() throws Exception {
        thumbnailService = newThumbnailService(false);
    }

    @AfterEach
    void tearDown() {
        thumbnailService.shutdown();
    }

    @Test
    void returnsPlaceholderWhenVideoThumbnailsAreDisabled() throws Exception {
        ThumbnailFile thumbnail = thumbnailService.videoThumbnail(root.resolve("missing.mp4"), "missing.mp4");

        assertThat(thumbnail.generated()).isFalse();
        assertThat(thumbnail.mediaType()).isEqualTo("image/png");
        assertThat(thumbnail.path()).exists();
        assertThat(thumbnail.path().getFileName().toString()).isEqualTo("video-placeholder.png");
    }

    @Test
    void migratesCachedThumbnailWhenVideoPathChanges() throws Exception {
        thumbnailService.shutdown();
        thumbnailService = newThumbnailService(true);
        Path video = root.resolve("renamed.mp4");
        Files.writeString(video, "video", StandardCharsets.UTF_8);
        Path oldCache = thumbnailService.videoCacheFile(video, "old.mp4");
        Path newCache = thumbnailService.videoCacheFile(video, "renamed.mp4");
        Files.createDirectories(oldCache.getParent());
        Files.writeString(oldCache, "thumbnail", StandardCharsets.UTF_8);

        thumbnailService.migrateVideoThumbnails(video, "old.mp4", "renamed.mp4");

        assertThat(oldCache).doesNotExist();
        assertThat(newCache).exists();
        assertThat(Files.readString(newCache)).isEqualTo("thumbnail");
    }

    @Test
    void migratesCachedThumbnailsUnderMovedDirectory() throws Exception {
        thumbnailService.shutdown();
        thumbnailService = newThumbnailService(true);
        Path video = root.resolve("renamed").resolve("sub").resolve("movie.mp4");
        Files.createDirectories(video.getParent());
        Files.writeString(video, "video", StandardCharsets.UTF_8);
        Path oldCache = thumbnailService.videoCacheFile(video, "old/sub/movie.mp4");
        Path newCache = thumbnailService.videoCacheFile(video, "renamed/sub/movie.mp4");
        Files.createDirectories(oldCache.getParent());
        Files.writeString(oldCache, "thumbnail", StandardCharsets.UTF_8);

        thumbnailService.migrateVideoThumbnails(root.resolve("renamed"), "old", "renamed");

        assertThat(oldCache).doesNotExist();
        assertThat(newCache).exists();
        assertThat(Files.readString(newCache)).isEqualTo("thumbnail");
    }

    @Test
    void generatesComicThumbnailFromFirstImagePage() throws Exception {
        thumbnailService.shutdown();
        thumbnailService = newThumbnailService(true);
        Path comic = root.resolve("book.cbz");
        writeComic(comic);

        ThumbnailFile firstResponse = thumbnailService.comicThumbnail(comic, "book.cbz");

        assertThat(firstResponse.generated()).isFalse();
        Path cacheFile = thumbnailService.comicCacheFile(comic, "book.cbz");
        waitForFile(cacheFile);

        ThumbnailFile cachedResponse = thumbnailService.comicThumbnail(comic, "book.cbz");
        assertThat(cachedResponse.generated()).isTrue();
        assertThat(cachedResponse.mediaType()).isEqualTo("image/jpeg");
        assertThat(cachedResponse.path()).isEqualTo(cacheFile);
    }

    @Test
    void generatesComicThumbnailFromWebpFirstPageWithFfmpegFallback() throws Exception {
        thumbnailService.shutdown();
        thumbnailService = newThumbnailService(true);
        Path comic = root.resolve("webp-book.cbz");
        writeWebpComic(comic);

        ThumbnailFile firstResponse = thumbnailService.comicThumbnail(comic, "webp-book.cbz");

        assertThat(firstResponse.generated()).isFalse();
        Path cacheFile = thumbnailService.comicCacheFile(comic, "webp-book.cbz");
        waitForFile(cacheFile);

        ThumbnailFile cachedResponse = thumbnailService.comicThumbnail(comic, "webp-book.cbz");
        assertThat(cachedResponse.generated()).isTrue();
        assertThat(cachedResponse.mediaType()).isEqualTo("image/jpeg");
        assertThat(cachedResponse.path()).isEqualTo(cacheFile);
    }

    @Test
    void migratesCachedComicThumbnailWhenPathChanges() throws Exception {
        thumbnailService.shutdown();
        thumbnailService = newThumbnailService(true);
        Path comic = root.resolve("renamed.cbz");
        writeComic(comic);
        Path oldCache = thumbnailService.comicCacheFile(comic, "old.cbz");
        Path newCache = thumbnailService.comicCacheFile(comic, "renamed.cbz");
        Files.createDirectories(oldCache.getParent());
        Files.writeString(oldCache, "thumbnail", StandardCharsets.UTF_8);

        thumbnailService.migrateThumbnails(comic, "old.cbz", "renamed.cbz");

        assertThat(oldCache).doesNotExist();
        assertThat(newCache).exists();
        assertThat(Files.readString(newCache)).isEqualTo("thumbnail");
    }

    @Test
    void previewFrameUsesFivePercentPointForNormalVideos() {
        assertThat(ThumbnailService.previewFrame(100.0d, 30.0d, 3_000)).isEqualTo(150);
    }

    @Test
    void previewFrameUsesMiddleWhenCalculatedFrameExceedsTotalFrames() {
        assertThat(ThumbnailService.previewFrame(100.0d, 60.0d, 100)).isEqualTo(50);
    }

    @Test
    void previewFrameFallsBackToFrameCountWhenFrameRateIsUnknown() {
        assertThat(ThumbnailService.previewFrame(100.0d, 0.0d, 1_000)).isEqualTo(50);
    }

    private ThumbnailService newThumbnailService(boolean videoEnabled) throws Exception {
        NasProperties properties = new NasProperties();
        properties.getStorage().setRoot(root);
        properties.getThumbnails().setVideoEnabled(videoEnabled);
        ThumbnailService service = new ThumbnailService(properties);
        service.initialize();
        return service;
    }

    private void writeComic(Path comic) throws Exception {
        try (ZipOutputStream zipOutputStream = new ZipOutputStream(Files.newOutputStream(comic))) {
            zipOutputStream.putNextEntry(new ZipEntry("001.png"));
            zipOutputStream.write(pngBytes(Color.RED));
            zipOutputStream.closeEntry();
            zipOutputStream.putNextEntry(new ZipEntry("002.png"));
            zipOutputStream.write(pngBytes(Color.BLUE));
            zipOutputStream.closeEntry();
        }
    }

    private void writeWebpComic(Path comic) throws Exception {
        try (ZipOutputStream zipOutputStream = new ZipOutputStream(Files.newOutputStream(comic))) {
            zipOutputStream.putNextEntry(new ZipEntry("001.webp"));
            zipOutputStream.write(Base64.getDecoder().decode(
                    "UklGRhwAAABXRUJQVlA4TA8AAAAvA8AAAAcQ9Y/+ByKi/wEA"
            ));
            zipOutputStream.closeEntry();
        }
    }

    private byte[] pngBytes(Color color) throws Exception {
        BufferedImage image = new BufferedImage(12, 12, BufferedImage.TYPE_INT_RGB);
        for (int y = 0; y < image.getHeight(); y++) {
            for (int x = 0; x < image.getWidth(); x++) {
                image.setRGB(x, y, color.getRGB());
            }
        }
        ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
        ImageIO.write(image, "png", outputStream);
        return outputStream.toByteArray();
    }

    private void waitForFile(Path file) throws Exception {
        long deadline = System.nanoTime() + Duration.ofSeconds(3).toNanos();
        while (System.nanoTime() < deadline) {
            if (Files.exists(file)) {
                return;
            }
            Thread.sleep(25L);
        }
        assertThat(file).exists();
    }
}
