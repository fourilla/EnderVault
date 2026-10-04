package io.github.fourilla.endervault.recent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.fourilla.endervault.search.SearchQueryException;
import io.github.fourilla.endervault.storage.FileItem;
import java.time.Instant;
import org.junit.jupiter.api.Test;

class RecentSearchSchemaTest {
    @Test
    void sizeUsesExistingMetadataAndExtensionIsExactNotMediaTypeOrSubstring() {
        var item = entry("photo.PDF", false, 1024);
        assertThat(RecentSearchSchema.compile("extension:pdf size:1KB..1KiB").test(item)).isTrue();
        assertThat(RecentSearchSchema.compile("size:>=1MiB").test(item)).isFalse();
        assertThat(RecentSearchSchema.compile("extension:pd").test(item)).isFalse();
        assertThat(RecentSearchSchema.compile("extension:gz").test(entry("archive.tar.gz", false, 0))).isTrue();
        assertThat(RecentSearchSchema.compile("extension:pdf").test(entry(".pdf", false, 1))).isFalse();
        assertThat(RecentSearchSchema.compile("extension:pdf").test(entry("folder.pdf", true, 0))).isFalse();
        assertThat(RecentSearchSchema.compile("size:0").test(entry("folder", true, 0))).isFalse();
        assertThat(RecentSearchSchema.compile("size:>=0").test(entry("folder", true, 0))).isFalse();
        assertThat(RecentSearchSchema.compile("size:0").test(entry("empty.pdf", false, 0))).isTrue();
    }

    @Test
    void invalidSizeAndExtensionOperatorsDoNotBecomeBroadQueries() {
        assertThatThrownBy(() -> RecentSearchSchema.compile("photo || size:-1")).isInstanceOf(SearchQueryException.class);
        assertThatThrownBy(() -> RecentSearchSchema.compile("photo || extension:>pdf")).isInstanceOf(SearchQueryException.class);
    }

    private RecentListItem entry(String name, boolean directory, long size) {
        return new RecentListItem(new FileItem(name, "docs/" + name, directory, size, "display", "display", Instant.EPOCH,
                "application/octet-stream", false, false, false), Instant.EPOCH);
    }
}
