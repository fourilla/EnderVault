package io.github.fourilla.endervault.metadata;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.github.fourilla.endervault.bookmark.BookmarkFaviconTemporaryFile;
import io.github.fourilla.endervault.bookmark.BookmarkService;
import io.github.fourilla.endervault.config.NasProperties;
import io.github.fourilla.endervault.temporary.TemporaryArtifactRetentionPolicy;
import io.github.fourilla.endervault.thumbnail.ThumbnailCacheFile;
import io.github.fourilla.endervault.thumbnail.ThumbnailCacheScan;
import io.github.fourilla.endervault.thumbnail.ThumbnailService;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class TemporaryArtifactInspectorPolicyTest {

    private TemporaryArtifactRetentionPolicy retentionPolicy;

    @BeforeEach
    void setUp() {
        NasProperties properties = new NasProperties();
        properties.getTemporaryArtifacts().setStaleAfterMinutes(60);
        retentionPolicy = new TemporaryArtifactRetentionPolicy(properties);
    }

    @Test
    void bookmarkFaviconTemporaryFilesRespectAgeAndActiveState() throws Exception {
        BookmarkService bookmarkService = mock(BookmarkService.class);
        when(bookmarkService.storedItems()).thenReturn(List.of());
        when(bookmarkService.orphanFaviconCacheFiles()).thenReturn(List.of());
        when(bookmarkService.temporaryFaviconCacheFiles()).thenReturn(List.of(
                faviconTemporaryFile("fresh.tmp", Instant.now(), false),
                faviconTemporaryFile("stale.tmp", Instant.now().minusSeconds(7200), false),
                faviconTemporaryFile("active.tmp", Instant.now().minusSeconds(7200), true)
        ));

        List<MetadataIssue> issues = new BookmarkMetadataInspector(bookmarkService, retentionPolicy).inspect();

        assertThat(issue(issues, "fresh.tmp").action()).isEqualTo(MetadataIssueAction.NONE);
        assertThat(issue(issues, "stale.tmp").action())
                .isEqualTo(MetadataIssueAction.DELETE_BOOKMARK_FAVICON_CACHE);
        assertThat(issue(issues, "active.tmp").action()).isEqualTo(MetadataIssueAction.NONE);
    }

    @Test
    void thumbnailTemporaryFilesBecomeRepairableOnlyAfterTheSharedGracePeriod() throws Exception {
        ThumbnailService thumbnailService = mock(ThumbnailService.class);
        when(thumbnailService.scanCache(null)).thenReturn(new ThumbnailCacheScan(
                List.of(),
                List.of(
                        thumbnailTemporaryFile("fresh.tmp", Instant.now()),
                        thumbnailTemporaryFile("stale.tmp", Instant.now().minusSeconds(7200))
                )
        ));

        List<MetadataIssue> issues = new ThumbnailMetadataInspector(thumbnailService, retentionPolicy).inspect();

        assertThat(issue(issues, "fresh.tmp").action()).isEqualTo(MetadataIssueAction.NONE);
        assertThat(issue(issues, "stale.tmp").action()).isEqualTo(MetadataIssueAction.DELETE_THUMBNAIL_CACHE);
    }

    private BookmarkFaviconTemporaryFile faviconTemporaryFile(String name, Instant modifiedAt, boolean active) {
        return new BookmarkFaviconTemporaryFile(
                name,
                1L,
                "1 B",
                modifiedAt,
                "2026-08-14 12:00",
                active,
                active ? "Bookmark favicon" : null
        );
    }

    private ThumbnailCacheFile thumbnailTemporaryFile(String name, Instant modifiedAt) {
        return new ThumbnailCacheFile(name, 1L, "1 B", modifiedAt, "2026-08-14 12:00");
    }

    private MetadataIssue issue(List<MetadataIssue> issues, String subject) {
        return issues.stream()
                .filter(issue -> subject.equals(issue.subject()))
                .findFirst()
                .orElseThrow();
    }
}
