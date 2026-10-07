package io.github.fourilla.endervault.web.support;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.fourilla.endervault.storage.FileDetail;
import io.github.fourilla.endervault.storage.FileItem;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Arrays;
import org.junit.jupiter.api.Test;

class FilePreviewSupportTest {

    private final FilePreviewSupport filePreviewSupport = new FilePreviewSupport();

    @Test
    void adminPreviewActionUsesTrackedOpenEndpoint() {
        FileItem item = item("demo file.mp4", "series/demo file.mp4", "video/mp4", true);

        assertThat(filePreviewSupport.previewUrl(item))
                .isEqualTo("/files/open?path=series/demo%20file.mp4");
    }

    @Test
    void cardMediaUsesSideEffectFreeListEndpoints() {
        FileItem image = item("cover image.jpg", "series/cover image.jpg", "image/jpeg", true);
        FileItem video = item("episode 01.mp4", "series/episode 01.mp4", "video/mp4", true);

        assertThat(filePreviewSupport.cardMediaUrl(image))
                .isEqualTo("/files/preview?path=series&item=cover%20image.jpg");
        assertThat(filePreviewSupport.cardMediaUrl(video))
                .isEqualTo("/files/thumbnail?path=series&item=episode%2001.mp4");
    }

    @Test
    void previewContentUsesListEndpointForRegularFilesAndViewerForComics() {
        FileDetail video = detail("episode.mp4", "series/episode.mp4", "video/mp4", "mp4", true);
        FileDetail comic = detail("book.cbz", "series/book.cbz", "application/zip", "cbz", true);

        assertThat(filePreviewSupport.previewContentUrl(video))
                .isEqualTo("/files/preview?path=series&item=episode.mp4");
        assertThat(filePreviewSupport.previewContentUrl(comic))
                .isEqualTo("/files/detail/comic/preview?path=series/book.cbz");
    }

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
                .isEqualTo("/s/guest/download/demo%20file%2C%2001.mp4?path=series&item=demo%20file%2C%2001.mp4");
    }

    @Test
    void sharedUrlsRoundTripLiteralPlusPercentDelimitersAndUnicode() {
        String path = "chapter + & 1/50% 한글";
        String name = "note + 1%.txt";
        FileItem item = item(name, path + "/" + name, "text/plain", true);

        for (String url : new String[] {
                filePreviewSupport.sharedDirectoryFileUrl("guest", item),
                filePreviewSupport.sharedDirectoryPreviewUrl("guest", item),
                filePreviewSupport.sharedDirectoryDownloadUrl("guest", item)
        }) {
            assertThat(url).contains("%2B", "%25", "%26", "%2F");
            assertThat(decodedQuery(url, "path")).isEqualTo(path);
            assertThat(decodedQuery(url, "item")).isEqualTo(name);
        }
        String download = filePreviewSupport.sharedFileDownloadUrl("guest", item);
        assertThat(URI.create(download).getRawPath()).isEqualTo("/s/guest/download");
        assertThat(URI.create(download).getRawQuery()).isNull();
    }

    @Test
    void sharedDownloadsKeepFirewallRestrictedFilenamesInQueryOnlyScope() {
        String path = "chapter + & 1";
        for (String name : new String[] {"50%.txt", "semi;colon.txt"}) {
            FileItem item = item(name, path + "/" + name, "text/plain", true);
            String directoryDownload = filePreviewSupport.sharedDirectoryDownloadUrl("guest", item);
            assertThat(URI.create(directoryDownload).getRawPath()).isEqualTo("/s/guest/download");
            assertThat(decodedQuery(directoryDownload, "path")).isEqualTo(path);
            assertThat(decodedQuery(directoryDownload, "item")).isEqualTo(name);
            assertThat(filePreviewSupport.sharedFileDownloadUrl("guest", item)).isEqualTo("/s/guest/download");
        }
    }

    private String decodedQuery(String url, String key) {
        return Arrays.stream(URI.create(url).getRawQuery().split("&"))
                .filter(part -> part.startsWith(key + "="))
                .map(part -> URLDecoder.decode(part.substring(key.length() + 1), StandardCharsets.UTF_8))
                .findFirst().orElseThrow();
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

    private FileDetail detail(
            String name,
            String path,
            String mediaType,
            String extension,
            boolean previewable
    ) {
        int separator = path.lastIndexOf('/');
        String parentPath = separator < 0 ? "" : path.substring(0, separator);
        return new FileDetail(
                name,
                path,
                parentPath,
                false,
                1L,
                "1 B",
                0L,
                "now",
                "now",
                "now",
                mediaType,
                extension,
                previewable,
                false,
                false
        );
    }
}
