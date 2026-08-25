package io.github.fourilla.endervault.web.bookmark;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;

class BookmarkRoutesTest {

    @Test
    void buildsBookmarkRedirectWithNormalizedParameters() {
        assertThat(BookmarkRoutes.bookmarksUrl(" docs ", " spring docs "))
                .isEqualTo("/files/bookmarks?directory=docs&q=spring%20docs");
        assertThat(BookmarkRoutes.redirectToBookmarks(" docs ", " spring docs "))
                .isEqualTo("redirect:/files/bookmarks?directory=docs&q=spring%20docs");
        assertThat(BookmarkRoutes.redirectToBookmarks("", ""))
                .isEqualTo("redirect:/files/bookmarks");
        assertThat(BookmarkRoutes.bookmarkDetailUrl("bookmark id"))
                .isEqualTo("/files/bookmarks/detail?id=bookmark%20id");
    }

    @Test
    void normalizesSelectedIds() {
        assertThat(BookmarkRoutes.safeIds(List.of(" a ", "", "a", "b")))
                .containsExactly("a", "b");
    }

    @Test
    void fallsBackWhenMediaTypeIsInvalid() {
        assertThat(BookmarkRoutes.mediaType("image/png")).isEqualTo(MediaType.IMAGE_PNG);
        assertThat(BookmarkRoutes.mediaType("not a type")).isEqualTo(MediaType.APPLICATION_OCTET_STREAM);
        assertThat(BookmarkRoutes.mediaType(null)).isEqualTo(MediaType.APPLICATION_OCTET_STREAM);
    }
}
