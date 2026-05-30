package io.github.fourilla.endervault.thumbnail;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.fourilla.endervault.config.NasProperties;
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
        NasProperties properties = new NasProperties();
        properties.getStorage().setRoot(root);
        properties.getThumbnails().setVideoEnabled(false);
        thumbnailService = new ThumbnailService(properties);
        thumbnailService.initialize();
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
}
