package io.github.fourilla.endervault.web.support;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class InternalUrlBuilderTest {

    @ParameterizedTest
    @ValueSource(strings = {"a+b.txt", "a b.txt", "literal%2B.txt", "50%.txt", "a&b=c#d.txt",
            "parent+name/child + 한글/file+%2B.txt", "brace{path} + ?.txt", "semi;colon.txt"})
    void queryValuesRoundTripExactlyOnce(String path) {
        String url = InternalUrlBuilder.fromPath("/files/detail").queryParam("path", path).build();

        assertThat(URI.create(url).getRawPath()).isEqualTo("/files/detail");
        assertThat(queryValues(url, "path")).containsExactly(path);
        assertThat(URI.create(url).getRawQuery()).doesNotContain("+");
    }

    @Test
    void plusSpaceAndEncodedLookingNamesProduceDifferentUrls() {
        List<String> urls = List.of("a+b.txt", "a b.txt", "a%2Bb.txt").stream()
                .map(path -> InternalUrlBuilder.fromPath("/files/detail").queryParam("path", path).build())
                .toList();

        assertThat(urls).doesNotHaveDuplicates();
        assertThat(urls).containsExactly("/files/detail?path=a%2Bb.txt", "/files/detail?path=a%20b.txt",
                "/files/detail?path=a%252Bb.txt");
    }

    @Test
    void repeatedAndEmptyParametersRetainOrderAndValues() {
        String url = InternalUrlBuilder.fromPath("/files/download.zip")
                .queryParam("path", "parent+folder")
                .queryParam("paths", "parent+folder/a+b.txt", "parent+folder/a b.txt")
                .queryParam("paths", "parent+folder/%2B.txt")
                .queryParam("empty", "")
                .queryParam("flag")
                .queryParam("page", 2)
                .build();

        assertThat(queryValues(url, "paths")).containsExactly("parent+folder/a+b.txt", "parent+folder/a b.txt",
                "parent+folder/%2B.txt");
        assertThat(url).endsWith("&empty=&flag&page=2");
        assertThat(queryValues(url, "path")).containsExactly("parent+folder");
    }

    @Test
    void routeVariablesAndQueryValuesHaveSeparateNamesAndEncoding() {
        String url = InternalUrlBuilder.fromPath("/s/{token}/download")
                .pathSegment("{name}")
                .pathVariable("token", "guest")
                .pathVariable("name", "book + 1.txt")
                .queryParam("path", "chapter + 1/part & 2")
                .queryParam("item", "book + 1.txt")
                .build();

        assertThat(URI.create(url).getRawPath()).isEqualTo("/s/guest/download/book%20%2B%201.txt");
        assertThat(queryValues(url, "path")).containsExactly("chapter + 1/part & 2");
        assertThat(queryValues(url, "item")).containsExactly("book + 1.txt");
    }

    @Test
    void rebuildingDoesNotEncodeValuesAgain() {
        var builder = InternalUrlBuilder.fromPath("/files").queryParam("path", "a+%2B");
        String first = builder.build();

        assertThat(builder.build()).isEqualTo(first);
        assertThat(queryValues(first, "path")).containsExactly("a+%2B");
        assertThat(builder.queryParam("page", 2).build()).isEqualTo(first + "&page=2");
    }

    @Test
    void externalOrAlreadyComposedUrlsAreOutsideThisContract() {
        for (String route : List.of("https://example.test/files", "//example.test/files", "/files?path=a+b",
                "/files#section")) {
            assertThatThrownBy(() -> InternalUrlBuilder.fromPath(route)).isInstanceOf(IllegalArgumentException.class);
        }
    }

    private List<String> queryValues(String url, String name) {
        return Arrays.stream(URI.create(url).getRawQuery().split("&"))
                .filter(part -> part.startsWith(name + "="))
                .map(part -> URLDecoder.decode(part.substring(name.length() + 1), StandardCharsets.UTF_8))
                .toList();
    }
}
