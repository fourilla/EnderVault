package io.github.fourilla.endervault.thumbnail;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.fourilla.endervault.config.NasProperties;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
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
}
