package io.github.fourilla.endervault.bookmark;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import io.github.fourilla.endervault.common.StorageAccessException;
import io.github.fourilla.endervault.config.NasProperties;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class BookmarkServiceTest {

    @TempDir
    Path root;

    private BookmarkService bookmarkService;

    @BeforeEach
    void setUp() throws Exception {
        NasProperties properties = new NasProperties();
        properties.getStorage().setRoot(root);
        ObjectMapper objectMapper = JsonMapper.builder().findAndAddModules().build();
        bookmarkService = new BookmarkService(objectMapper, properties);
        bookmarkService.initialize();
    }

    @Test
    void createsFolderAndLinkInMetadataRegistry() throws Exception {
        BookmarkItem folder = bookmarkService.createFolder(null, "Docs");
        BookmarkItem link = bookmarkService.createLink(folder.id(), "Project", "https://example.com/project", "notes");

        assertThat(bookmarkService.list(null, ""))
                .extracting(BookmarkItem::title)
                .containsExactly("Docs");
        assertThat(bookmarkService.list(folder.id(), ""))
                .extracting(BookmarkItem::id)
                .containsExactly(link.id());
        assertThat(root.resolve(".endervault").resolve("bookmarks.json")).exists();
    }

    @Test
    void searchesDescendantsFromCurrentFolder() throws Exception {
        BookmarkItem folder = bookmarkService.createFolder(null, "Docs");
        BookmarkItem childFolder = bookmarkService.createFolder(folder.id(), "Nested");
        bookmarkService.createLink(childFolder.id(), "Spring Docs", "https://spring.io", "");

        assertThat(bookmarkService.list(folder.id(), "spring"))
                .extracting(BookmarkItem::title)
                .containsExactly("Spring Docs");
    }

    @Test
    void deletesFolderWithDescendants() throws Exception {
        BookmarkItem folder = bookmarkService.createFolder(null, "Docs");
        BookmarkItem childFolder = bookmarkService.createFolder(folder.id(), "Nested");
        bookmarkService.createLink(childFolder.id(), "Spring Docs", "https://spring.io", "");

        bookmarkService.delete(folder.id());

        assertThat(bookmarkService.list(null, "")).isEmpty();
        assertThat(bookmarkService.list(null, "spring")).isEmpty();
    }

    @Test
    void bulkDeletesSelectedItemsAndDescendants() throws Exception {
        BookmarkItem folder = bookmarkService.createFolder(null, "Docs");
        BookmarkItem link = bookmarkService.createLink(folder.id(), "Spring Docs", "https://spring.io", "");
        BookmarkItem other = bookmarkService.createLink(null, "Example", "https://example.com", "");

        int deletedCount = bookmarkService.deleteAll(List.of(folder.id(), link.id()));

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
    void rejectsOddBulkLines() {
        assertThatThrownBy(() -> bookmarkService.createLinks(null, """
                First
                https://example.com/first
                Second
                """))
                .isInstanceOf(StorageAccessException.class)
                .hasMessageContaining("title and URL pairs");
    }

    @Test
    void rejectsSchemeRelativeUrls() {
        assertThatThrownBy(() -> bookmarkService.createLink(null, "External", "//example.com", ""))
                .isInstanceOf(StorageAccessException.class);
    }
}
