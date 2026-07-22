package io.github.fourilla.endervault.web.support;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.fourilla.endervault.storage.FileItem;
import java.time.Instant;
import org.junit.jupiter.api.Test;

class FilePreviewSupportTest {

    private final FilePreviewSupport filePreviewSupport = new FilePreviewSupport();

    @Test
    void sharedFileComicPreviewUsesComicEndpoint() {
        FileItem item = item("book.cbz", "comics/book.cbz", "application/octet-stream", false);

        assertThat(filePreviewSupport.previewable(item)).isTrue();
        assertThat(filePreviewSupport.sharedFilePreviewUrl("guest", item))
                .isEqualTo("/s/guest/comic/preview");
    }

    @Test
    void sharedDirectoryComicPreviewKeepsDirectoryContext() {
        FileItem item = item("book 01.cbz", "series/book 01.cbz", "application/octet-stream", false);

        assertThat(filePreviewSupport.sharedDirectoryPreviewUrl("guest", item))
                .isEqualTo("/s/guest/comic/preview?path=series&item=book%2001.cbz");
    }

    @Test
    void sharedDirectoryImagePreviewUsesExistingPreviewEndpoint() {
        FileItem item = item("cover.jpg", "series/cover.jpg", "image/jpeg", true);

        assertThat(filePreviewSupport.sharedDirectoryPreviewUrl("guest", item))
                .isEqualTo("/s/guest/preview?path=series&item=cover.jpg");
    }

    @Test
    void sharedDirectoryFileUrlKeepsDirectoryContextForLandingPage() {
        FileItem item = item("cover.jpg", "series/cover.jpg", "image/jpeg", true);

        assertThat(filePreviewSupport.sharedDirectoryFileUrl("guest", item))
                .isEqualTo("/s/guest/file?item=cover.jpg&path=series");
    }

    @Test
    void sharedDownloadUrlIncludesFilenameForCommandLineClients() {
        FileItem item = item("demo file, 01.mp4", "series/demo file, 01.mp4", "video/mp4", true);

        assertThat(filePreviewSupport.sharedDirectoryDownloadUrl("guest", item))
                .isEqualTo("/s/guest/download/demo%20file,%2001.mp4?path=series&item=demo%20file,%2001.mp4");
    }

    private FileItem item(String name, String path, String mediaType, boolean previewable) {
        return new FileItem(
                name,
                path,
                false,
                1L,
                "1 B",
                "now",
                Instant.EPOCH,
                mediaType,
                previewable,
                false,
                false
        );
    }
}
