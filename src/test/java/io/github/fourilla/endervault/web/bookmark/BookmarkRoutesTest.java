package io.github.fourilla.endervault.web.bookmark;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
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
        assertThat(BookmarkRoutes.bookmarkOpenUrl("bookmark id"))
                .isEqualTo("/files/bookmarks/open?id=bookmark%20id");
        assertThat(BookmarkRoutes.bookmarkFaviconUrl("bookmark id"))
                .isEqualTo("/files/bookmarks/favicon?id=bookmark%20id");
    }

    @Test
    void normalizesSelectedIds() {
        assertThat(BookmarkRoutes.safeIds(List.of(" a ", "", "a", "b")))
                .containsExactly("a", "b");
    }

    @Test
    void bookmarkQueryPreservesPlusAndEncodedLookingTextAfterNormalization() {
        String url = BookmarkRoutes.bookmarksUrl(" dir+name ", " parent+name/file%2Bname.txt ");

        assertThat(url).isEqualTo(
                "/files/bookmarks?directory=dir%2Bname&q=parent%2Bname%2Ffile%252Bname.txt");
        assertThat(decodedQueryValue(url, "directory")).isEqualTo("dir+name");
        assertThat(decodedQueryValue(url, "q")).isEqualTo("parent+name/file%2Bname.txt");
    }

    @Test
    void bookmarkItemLinksPreserveRawIdentifierReservedCharacters() {
        String id = "bookmark+id /%2B&?#한글";

        List<String> urls = List.of(
                BookmarkRoutes.bookmarkDetailUrl(id),
                BookmarkRoutes.bookmarkOpenUrl(id),
                BookmarkRoutes.bookmarkFaviconUrl(id)
        );
        for (String url : urls) {
            assertThat(decodedQueryValue(url, "id")).isEqualTo(id);
            assertThat(URI.create(url).getFragment()).isNull();
        }
    }

    @Test
    void fallsBackWhenMediaTypeIsInvalid() {
        assertThat(BookmarkRoutes.mediaType("image/png")).isEqualTo(MediaType.IMAGE_PNG);
        assertThat(BookmarkRoutes.mediaType("not a type")).isEqualTo(MediaType.APPLICATION_OCTET_STREAM);
        assertThat(BookmarkRoutes.mediaType(null)).isEqualTo(MediaType.APPLICATION_OCTET_STREAM);
    }

    private static String decodedQueryValue(String url, String name) {
        for (String parameter : URI.create(url).getRawQuery().split("&")) {
            String[] parts = parameter.split("=", 2);
            if (URLDecoder.decode(parts[0], StandardCharsets.UTF_8).equals(name)) {
                return URLDecoder.decode(parts[1], StandardCharsets.UTF_8);
            }
        }
        throw new AssertionError("Missing query parameter: " + name);
    }
}
