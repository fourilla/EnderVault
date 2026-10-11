package io.github.fourilla.endervault.web.api.v1.fs;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.fourilla.endervault.recent.RecentListItem;
import io.github.fourilla.endervault.storage.FileItem;
import io.github.fourilla.endervault.web.support.FilePreviewSupport;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Arrays;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class FileBrowserEntryPayloadTest {

    private final FilePreviewSupport filePreviewSupport = new FilePreviewSupport();

    @ParameterizedTest
    @ValueSource(strings = {
            "a+b.jpg",
            "a b.jpg",
            "literal%2B.jpg",
            "parent+/child+/a+b.jpg",
            "한글/한글 + & = # ? %.jpg",
            "directory/{literal}/[file]+;.jpg"
    })
    void browserUrlsRoundTripRawFilePaths(String path) {
        FileItem item = item(path, false);
        FileBrowserEntryPayload payload = FileBrowserEntryPayload.from(item, false, filePreviewSupport);

        assertThat(decodedQuery(payload.detailUrl(), "path")).isEqualTo(path);
        assertThat(decodedQuery(payload.downloadUrl(), "path")).isEqualTo(path);
        assertThat(decodedQuery(payload.previewUrl(), "path")).isEqualTo(path);
        assertThat(decodedQuery(payload.thumbnailUrl(), "item")).isEqualTo(item.name());
        if (item.parentPath().isEmpty()) {
            assertThat(URI.create(payload.thumbnailUrl()).getRawQuery()).doesNotContain("path=");
        } else {
            assertThat(decodedQuery(payload.thumbnailUrl(), "path")).isEqualTo(item.parentPath());
        }
    }

    @Test
    void literalPlusAndSpaceRemainDistinctInGeneratedLinks() {
        FileBrowserEntryPayload plus = FileBrowserEntryPayload.from(item("a+b.jpg", false), false, filePreviewSupport);
        FileBrowserEntryPayload space = FileBrowserEntryPayload.from(item("a b.jpg", false), false, filePreviewSupport);

        assertThat(plus.detailUrl()).isNotEqualTo(space.detailUrl());
        assertThat(plus.downloadUrl()).isNotEqualTo(space.downloadUrl());
        assertThat(decodedQuery(plus.detailUrl(), "path")).isEqualTo("a+b.jpg");
        assertThat(decodedQuery(space.detailUrl(), "path")).isEqualTo("a b.jpg");
    }

    @Test
    void recentPayloadRetainsEncodedBrowserUrlsAndAccessTimestamp() {
        FileItem item = item("parent+/child+/literal%2B +.jpg", false);
        Instant accessedAt = Instant.parse("2026-10-11T01:23:45Z");
        FileBrowserEntryPayload browser = FileBrowserEntryPayload.from(item, true, filePreviewSupport);
        FileBrowserEntryPayload recent = FileBrowserEntryPayload.from(
                new RecentListItem(item, accessedAt), true, filePreviewSupport);

        assertThat(recent.detailUrl()).isEqualTo(browser.detailUrl());
        assertThat(recent.downloadUrl()).isEqualTo(browser.downloadUrl());
        assertThat(recent.previewUrl()).isEqualTo(browser.previewUrl());
        assertThat(recent.thumbnailUrl()).isEqualTo(browser.thumbnailUrl());
        assertThat(decodedQuery(recent.detailUrl(), "path")).isEqualTo(item.path());
        assertThat(recent.accessedAt()).isEqualTo(accessedAt);
        assertThat(recent.favorite()).isTrue();
    }

    @Test
    void directoryDetailUsesRawPathWithoutAddingFileOnlyLinks() {
        FileItem item = item("parent+/child +%2B", true);
        FileBrowserEntryPayload payload = FileBrowserEntryPayload.from(item, false, filePreviewSupport);

        assertThat(decodedQuery(payload.detailUrl(), "path")).isEqualTo(item.path());
        assertThat(payload.downloadUrl()).isNull();
        assertThat(payload.previewUrl()).isNull();
        assertThat(payload.thumbnailUrl()).isNull();
    }

    private String decodedQuery(String url, String key) {
        return Arrays.stream(URI.create(url).getRawQuery().split("&"))
                .filter(part -> part.startsWith(key + "="))
                .map(part -> URLDecoder.decode(part.substring(key.length() + 1), StandardCharsets.UTF_8))
                .findFirst().orElseThrow();
    }

    private FileItem item(String path, boolean directory) {
        int separator = path.lastIndexOf('/');
        String name = separator < 0 ? path : path.substring(separator + 1);
        return new FileItem(
                name, path, directory, 1L, "1 B", "now", Instant.EPOCH,
                directory ? "inode/directory" : "image/jpeg", !directory, false, false);
    }
}
