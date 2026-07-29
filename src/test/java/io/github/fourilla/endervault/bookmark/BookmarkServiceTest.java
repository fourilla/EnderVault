package io.github.fourilla.endervault.bookmark;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.sun.net.httpserver.HttpServer;
import io.github.fourilla.endervault.common.StorageAccessException;
import io.github.fourilla.endervault.config.NasProperties;
import io.github.fourilla.endervault.outbound.OutboundHttpClientRegistry;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class BookmarkServiceTest {

    @TempDir
    Path root;

    private NasProperties properties;
    private BookmarkService bookmarkService;

    @BeforeEach
    void setUp() throws Exception {
        properties = new NasProperties();
        properties.getStorage().setRoot(root);
        ObjectMapper objectMapper = JsonMapper.builder().findAndAddModules().build();
        bookmarkService = new BookmarkService(
                objectMapper,
                properties,
                new BookmarkMetadataFetcher(properties, new OutboundHttpClientRegistry())
        );
        bookmarkService.initialize();
    }

    @Test
    void createsDirectoryAndLinkInMetadataRegistry() throws Exception {
        BookmarkItem directory = bookmarkService.createDirectory(null, "Docs");
        BookmarkItem link = bookmarkService.createLink(directory.id(), "Project", "https://example.com/project", "notes");

        assertThat(bookmarkService.list(null, ""))
                .extracting(BookmarkItem::title)
                .containsExactly("Docs");
        assertThat(bookmarkService.list(directory.id(), ""))
                .extracting(BookmarkItem::id)
                .containsExactly(link.id());
        assertThat(root.resolve(".endervault").resolve("bookmarks.json")).exists();
    }

    @Test
    void searchesDescendantsFromCurrentDirectory() throws Exception {
        BookmarkItem directory = bookmarkService.createDirectory(null, "Docs");
        BookmarkItem childDirectory = bookmarkService.createDirectory(directory.id(), "Nested");
        bookmarkService.createLink(childDirectory.id(), "Spring Docs", "https://spring.io", "");

        assertThat(bookmarkService.list(directory.id(), "spring"))
                .extracting(BookmarkItem::title)
                .containsExactly("Spring Docs");
    }

    @Test
    void deletesDirectoryWithDescendants() throws Exception {
        BookmarkItem directory = bookmarkService.createDirectory(null, "Docs");
        BookmarkItem childDirectory = bookmarkService.createDirectory(directory.id(), "Nested");
        bookmarkService.createLink(childDirectory.id(), "Spring Docs", "https://spring.io", "");

        bookmarkService.delete(directory.id());

        assertThat(bookmarkService.list(null, "")).isEmpty();
        assertThat(bookmarkService.list(null, "spring")).isEmpty();
    }

    @Test
    void bulkDeletesSelectedItemsAndDescendants() throws Exception {
        BookmarkItem directory = bookmarkService.createDirectory(null, "Docs");
        BookmarkItem link = bookmarkService.createLink(directory.id(), "Spring Docs", "https://spring.io", "");
        BookmarkItem other = bookmarkService.createLink(null, "Example", "https://example.com", "");

        int deletedCount = bookmarkService.deleteAll(List.of(directory.id(), link.id()));

        assertThat(deletedCount).isEqualTo(2);
        assertThat(bookmarkService.list(null, ""))
                .extracting(BookmarkItem::id)
                .containsExactly(other.id());
    }

    @Test
    void recordsOpenTimestampForLink() throws Exception {
        BookmarkItem link = bookmarkService.createLink(null, "Project", "https://example.com/project", "");

        BookmarkItem opened = bookmarkService.recordOpen(link.id());

        assertThat(opened.lastOpenedAt()).isNotNull();
        assertThat(bookmarkService.list(null, "").getFirst().lastOpenedAt()).isNotNull();
    }

    @Test
    void preservesExistingNoteWhenUpdateFormOmitsNoteField() throws Exception {
        BookmarkItem link = bookmarkService.createLink(null, "Project", "https://example.com/project", "old note");

        BookmarkItem updated = bookmarkService.updateLink(link.id(), "Project docs", "https://example.com/docs", null);

        assertThat(updated.note()).isEqualTo("old note");
    }

    @Test
    void rejectsUnsupportedUrls() {
        assertThatThrownBy(() -> bookmarkService.createLink(null, "Local", "file:///etc/passwd", ""))
                .isInstanceOf(StorageAccessException.class)
                .hasMessageContaining("http or https");
    }

    @Test
    void allowsAppRelativeUrls() throws Exception {
        BookmarkItem link = bookmarkService.createLink(null, "Files", "/files", "");

        assertThat(link.url()).isEqualTo("/files");
    }

    @Test
    void derivesTitleFromUrlWhenTitleIsBlank() throws Exception {
        BookmarkItem link = bookmarkService.createLink(null, "", "https://example.com/docs/reference", "");

        assertThat(link.title()).isEqualTo("example.com / docs/reference");
        assertThat(link.titleSource()).isEqualTo(BookmarkTitleSource.URL_DERIVED);
    }

    @Test
    void fetchesRemoteTitleAndFaviconWhenBookmarkMetadataIsEnabled() throws Exception {
        try (TestBookmarkServer server = TestBookmarkServer.start()) {
            properties.getBookmarks().setMetadataFetchEnabled(true);
            properties.getBookmarks().setBlockPrivateNetworks(false);
            properties.getBookmarks().setAllowedPorts(List.of(server.port()));

            BookmarkItem link = bookmarkService.createLink(null, "", server.pageUrl(), "");
            BookmarkItem otherLink = bookmarkService.createLink(null, "", server.pageUrl() + "?copy=1", "");

            assertThat(link.title()).isEqualTo("Remote Bookmark");
            assertThat(link.titleSource()).isEqualTo(BookmarkTitleSource.REMOTE_TITLE);
            assertThat(link.faviconAvailable()).isTrue();
            assertThat(otherLink.faviconFileName()).isEqualTo(link.faviconFileName());
            assertThat(bookmarkService.favicon(link.id()).contentType()).isEqualTo("image/png");
            assertThat(root.resolve(".endervault").resolve("bookmark-favicons")).isDirectory();
            assertThat(root.resolve(".endervault").resolve("bookmark-favicon-cache.json")).exists();
        }
    }

    @Test
    void keepsManualTitleWhenRemoteMetadataIsFetched() throws Exception {
        try (TestBookmarkServer server = TestBookmarkServer.start()) {
            properties.getBookmarks().setMetadataFetchEnabled(true);
            properties.getBookmarks().setBlockPrivateNetworks(false);
            properties.getBookmarks().setAllowedPorts(List.of(server.port()));

            BookmarkItem link = bookmarkService.createLink(null, "Manual Title", server.pageUrl(), "");

            assertThat(link.title()).isEqualTo("Manual Title");
            assertThat(link.titleSource()).isEqualTo(BookmarkTitleSource.MANUAL);
            assertThat(link.faviconAvailable()).isTrue();
        }
    }

    @Test
    void fetchesOpenGraphTitleBeforeHtmlTitle() throws Exception {
        try (TestBookmarkServer server = TestBookmarkServer.start()) {
            properties.getBookmarks().setMetadataFetchEnabled(true);
            properties.getBookmarks().setBlockPrivateNetworks(false);
            properties.getBookmarks().setAllowedPorts(List.of(server.port()));

            BookmarkItem link = bookmarkService.createLink(null, "", server.metadataTitleUrl(), "");

            assertThat(link.title()).isEqualTo("Open Graph Bookmark");
            assertThat(link.titleSource()).isEqualTo(BookmarkTitleSource.REMOTE_TITLE);
        }
    }

    @Test
    void fetchesWebManifestTitleWhenHtmlHasNoTitle() throws Exception {
        try (TestBookmarkServer server = TestBookmarkServer.start()) {
            properties.getBookmarks().setMetadataFetchEnabled(true);
            properties.getBookmarks().setBlockPrivateNetworks(false);
            properties.getBookmarks().setAllowedPorts(List.of(server.port()));

            BookmarkItem link = bookmarkService.createLink(null, "", server.manifestTitleUrl(), "");

            assertThat(link.title()).isEqualTo("Manifest Bookmark");
            assertThat(link.titleSource()).isEqualTo(BookmarkTitleSource.REMOTE_TITLE);
        }
    }

    @Test
    void bulkCreatesTitleAndUrlPairs() throws Exception {
        bookmarkService.createLinks(null, """
                First
                https://example.com/first

                Second
                /files
                """);

        assertThat(bookmarkService.list(null, ""))
                .extracting(BookmarkItem::title)
                .containsExactly("First", "Second");
    }

    @Test
    void bulkCreatesUrlOnlyAndTitleUrlLines() throws Exception {
        bookmarkService.createLinks(null, """
                https://example.com/auto
                Custom
                https://example.com/custom
                /files
                """);

        assertThat(bookmarkService.list(null, ""))
                .extracting(BookmarkItem::title)
                .containsExactly("Custom", "EnderVault / files", "example.com / auto");
    }

    @Test
    void rejectsBulkTitleWithoutUrl() {
        assertThatThrownBy(() -> bookmarkService.createLinks(null, """
                First
                https://example.com/first
                Second
                """))
                .isInstanceOf(StorageAccessException.class)
                .hasMessageContaining("must be followed by a URL");
    }

    @Test
    void rejectsConsecutiveBulkTitles() {
        assertThatThrownBy(() -> bookmarkService.createLinks(null, """
                First
                Second
                https://example.com/second
                """))
                .isInstanceOf(StorageAccessException.class)
                .hasMessageContaining("consecutive titles");
    }

    @Test
    void rejectsBulkTitleThatIsTooLongBeforeSaving() throws Exception {
        String longTitle = "a".repeat(201);

        assertThatThrownBy(() -> bookmarkService.createLinks(null, longTitle + "\nhttps://example.com"))
                .isInstanceOf(StorageAccessException.class)
                .hasMessageContaining("too long");
        assertThat(bookmarkService.list(null, "")).isEmpty();
    }

    @Test
    void rejectsSchemeRelativeUrls() {
        assertThatThrownBy(() -> bookmarkService.createLink(null, "External", "//example.com", ""))
                .isInstanceOf(StorageAccessException.class);
    }

    private record TestBookmarkServer(HttpServer server, int port) implements AutoCloseable {

        private static TestBookmarkServer start() throws Exception {
            HttpServer server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
            server.createContext("/page", exchange -> {
                byte[] body = """
                        <!doctype html>
                        <html>
                        <head>
                            <title>Remote Bookmark</title>
                            <link rel="icon" href="/favicon.png">
                        </head>
                        <body>bookmark</body>
                        </html>
                        """.getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().set("Content-Type", "text/html; charset=utf-8");
                exchange.sendResponseHeaders(200, body.length);
                exchange.getResponseBody().write(body);
                exchange.close();
            });
            server.createContext("/metadata-title", exchange -> {
                byte[] body = """
                        <!doctype html>
                        <html>
                        <head>
                            <meta property="og:title" content="Open Graph Bookmark">
                            <meta name="twitter:title" content="Twitter Bookmark">
                            <title>HTML Bookmark</title>
                        </head>
                        <body>metadata</body>
                        </html>
                        """.getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().set("Content-Type", "text/html; charset=utf-8");
                exchange.sendResponseHeaders(200, body.length);
                exchange.getResponseBody().write(body);
                exchange.close();
            });
            server.createContext("/manifest-title", exchange -> {
                byte[] body = """
                        <!doctype html>
                        <html>
                        <head>
                            <link rel="manifest" href="/site.webmanifest">
                        </head>
                        <body>manifest</body>
                        </html>
                        """.getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().set("Content-Type", "text/html; charset=utf-8");
                exchange.sendResponseHeaders(200, body.length);
                exchange.getResponseBody().write(body);
                exchange.close();
            });
            server.createContext("/site.webmanifest", exchange -> {
                byte[] body = """
                        {
                          "name": "Manifest Bookmark",
                          "short_name": "Manifest"
                        }
                        """.getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().set("Content-Type", "application/manifest+json");
                exchange.sendResponseHeaders(200, body.length);
                exchange.getResponseBody().write(body);
                exchange.close();
            });
            server.createContext("/favicon.png", exchange -> {
                byte[] body = new byte[]{(byte) 0x89, 'P', 'N', 'G'};
                exchange.getResponseHeaders().set("Content-Type", "image/png");
                exchange.sendResponseHeaders(200, body.length);
                exchange.getResponseBody().write(body);
                exchange.close();
            });
            server.start();
            return new TestBookmarkServer(server, server.getAddress().getPort());
        }

        private String pageUrl() {
            return "http://127.0.0.1:" + port + "/page";
        }

        private String metadataTitleUrl() {
            return "http://127.0.0.1:" + port + "/metadata-title";
        }

        private String manifestTitleUrl() {
            return "http://127.0.0.1:" + port + "/manifest-title";
        }

        @Override
        public void close() {
            server.stop(0);
        }
    }
}
