package io.github.fourilla.endervault.web.share;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class SharedFileRoutesTest {

    @Test
    void itemVaultPathCleansPathSegments() {
        assertThat(SharedFileRoutes.itemVaultPath("/shared/", "\\chapter one\\", "/page.cbz"))
                .isEqualTo("shared/chapter one/page.cbz");
    }

    @Test
    void comicPageUrlPrefixKeepsDirectoryContext() {
        assertThat(SharedFileRoutes.comicPageUrlPrefix("token 1", "a/b", "comic.cbz"))
                .isEqualTo("/s/token%201/comic/page?path=a%2Fb&item=comic.cbz&page=");
    }

    @Test
    void comicRoutesEncodeLiteralPlusAndQueryDelimiters() {
        assertThat(SharedFileRoutes.comicManifestUrl("token", "a+b", "book & 1.cbz"))
                .isEqualTo("/s/token/comic/manifest?path=a%2Bb&item=book%20%26%201.cbz");
        assertThat(SharedFileRoutes.comicPageUrl("token", null, null)).isEqualTo("/s/token/comic/page");
    }

    @Test
    void directoryUrlOmitsBlankPath() {
        assertThat(SharedFileRoutes.directoryUrl("token", " "))
                .isEqualTo("/s/token");
    }

    @Test
    void detectsComicByExtension() {
        assertThat(SharedFileRoutes.isComic(Path.of("Sample.CBZ"))).isTrue();
        assertThat(SharedFileRoutes.isComic(Path.of("Sample.zip"))).isFalse();
    }
}
