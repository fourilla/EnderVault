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
                .isEqualTo("/s/token%201/comic/page?path=a/b&item=comic.cbz&page=");
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
