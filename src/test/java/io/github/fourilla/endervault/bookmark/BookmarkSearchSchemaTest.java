package io.github.fourilla.endervault.bookmark;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.fourilla.endervault.search.SearchQueryException;
import java.time.Instant;
import org.junit.jupiter.api.Test;

class BookmarkSearchSchemaTest {

    @Test
    void defaultSearchMatchesTitleAndUrlButNotNoteAndTermsMayMatchDifferentFields() {
        BookmarkItem link = link();
        assertThat(BookmarkSearchSchema.compile("manual EXAMPLE").test(link)).isTrue();
        assertThat(BookmarkSearchSchema.compile("name:example").test(link)).isFalse();
        assertThat(BookmarkSearchSchema.compile("secret").test(link)).isFalse();
        assertThat(BookmarkSearchSchema.compile("note:secret").test(link)).isTrue();
        assertThat(BookmarkSearchSchema.compile("url:https://example.com").test(link)).isTrue();
    }

    @Test
    void typesAndDatesUseOriginalValuesAndNeverOpenedDoesNotMatchADate() {
        BookmarkItem link = link();
        assertThat(BookmarkSearchSchema.compile(
                "type:LINK created:2024-01-01T00:00:00Z updated:>=2024-01-02T00:00:00Z").test(link)).isTrue();
        assertThat(BookmarkSearchSchema.compile("type:directory || accessed:>=2024-01-01T00:00:00Z")
                .test(link)).isFalse();
    }

    @Test
    void invalidFieldsEnumsAndDatesAreRejectedEvenInAnOrExpression() {
        for (String query : new String[] {"manual || path:docs", "type:file", "created:2024-02-30"}) {
            assertThatThrownBy(() -> BookmarkSearchSchema.compile(query)).isInstanceOf(SearchQueryException.class);
        }
    }

    private BookmarkItem link() {
        return new BookmarkItem("id", BookmarkItemType.LINK, null, "Summer manual", "https://example.com/docs",
                "Secret note", BookmarkTitleSource.MANUAL, null, null, null, null,
                Instant.parse("2024-01-01T00:00:00Z"), Instant.parse("2024-01-02T00:00:00Z"), null);
    }
}
