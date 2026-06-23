package io.github.fourilla.endervault.web.support;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.fourilla.endervault.config.NasProperties;
import org.junit.jupiter.api.Test;

class BookmarkLinkClickActionTest {

    @Test
    void normalizesOnlyDetailToDetail() {
        assertThat(BookmarkLinkClickAction.normalize("detail")).isEqualTo("detail");
        assertThat(BookmarkLinkClickAction.normalize("DETAIL")).isEqualTo("detail");
        assertThat(BookmarkLinkClickAction.normalize("open")).isEqualTo("open");
        assertThat(BookmarkLinkClickAction.normalize("unexpected")).isEqualTo("open");
        assertThat(BookmarkLinkClickAction.normalize(null)).isEqualTo("open");
    }

    @Test
    void readsActionFromNasProperties() {
        NasProperties properties = new NasProperties();
        properties.getBookmarks().setLinkClickAction("detail");

        assertThat(BookmarkLinkClickAction.from(properties)).isEqualTo("detail");
        assertThat(BookmarkLinkClickAction.from(null)).isEqualTo("open");
    }
}
