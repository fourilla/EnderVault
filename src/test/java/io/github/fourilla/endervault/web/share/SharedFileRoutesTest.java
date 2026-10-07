package io.github.fourilla.endervault.web.share;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
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
    void directoryAndZipRoutesRoundTripLiteralQueryCharacters() {
        String path = "a+b/50% & 한글";
        for (String url : new String[] {
                SharedFileRoutes.directoryUrl("token", path),
                SharedFileRoutes.downloadZipUrl("token", path)
        }) {
            assertThat(url).contains("%2B", "%2F", "%25", "%26");
            String query = URI.create(url).getRawQuery();
            assertThat(URLDecoder.decode(query.substring("path=".length()), StandardCharsets.UTF_8)).isEqualTo(path);
        }
        assertThat(SharedFileRoutes.downloadZipUrl("token", null)).isEqualTo("/s/token/download.zip");
    }

    @Test
    void detectsComicByExtension() {
        assertThat(SharedFileRoutes.isComic(Path.of("Sample.CBZ"))).isTrue();
        assertThat(SharedFileRoutes.isComic(Path.of("Sample.zip"))).isFalse();
    }
}
