package io.github.fourilla.endervault.recent;

import static org.assertj.core.api.Assertions.assertThat;

import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;
import io.github.fourilla.endervault.config.NasProperties;
import io.github.fourilla.endervault.storage.SortDirection;
import io.github.fourilla.endervault.storage.StorageService;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class RecentServiceTest {

    @TempDir
    Path root;

    private NasProperties properties;
    private RecentService recentService;

    @BeforeEach
    void setUp() throws Exception {
        properties = new NasProperties();
        properties.getStorage().setRoot(root);
        StorageService storageService = new StorageService(properties);
        storageService.initialize();

        ObjectMapper objectMapper = JsonMapper.builder().findAndAddModules().build();
        recentService = new RecentService(storageService, objectMapper, properties);
        recentService.initialize();
    }

    @Test
    void recordsRecentPathWithoutDuplicates() throws Exception {
        Files.writeString(root.resolve("note.txt"), "hello");

        recentService.recordVaultPath("note.txt");
        recentService.recordVaultPath("note.txt");

        assertThat(recentService.storedItems())
                .extracting(RecentItem::path)
                .containsExactly("note.txt");
        assertThat(root.resolve(".endervault").resolve("recent-items.json")).exists();
    }

    @Test
    void recordsMostRecentFirst() throws Exception {
        Files.writeString(root.resolve("first.txt"), "first");
        Files.writeString(root.resolve("second.txt"), "second");

        recentService.recordVaultPath("first.txt");
        recentService.recordVaultPath("second.txt");

        assertThat(recentService.list("", RecentSort.RECENT, SortDirection.DESC))
                .extracting(RecentListItem::path)
                .containsExactly("second.txt", "first.txt");
    }

    @Test
    void sortsRecentItemsByNaturalNameOrder() throws Exception {
        Files.writeString(root.resolve("chapter11.txt"), "eleven");
        Files.writeString(root.resolve("chapter3.txt"), "three");
        recentService.recordVaultPath("chapter11.txt");
        recentService.recordVaultPath("chapter3.txt");

        assertThat(recentService.list("", RecentSort.NAME, SortDirection.ASC))
                .extracting(RecentListItem::name)
                .containsExactly("chapter3.txt", "chapter11.txt");
    }

    @Test
    void trimsRecentItemsToConfiguredLimit() throws Exception {
        properties.getRecent().setMaxItems(1);
        Files.writeString(root.resolve("first.txt"), "first");
        Files.writeString(root.resolve("second.txt"), "second");

        recentService.recordVaultPath("first.txt");
        recentService.recordVaultPath("second.txt");

        assertThat(recentService.storedItems())
                .extracting(RecentItem::path)
                .containsExactly("second.txt");
    }

    @Test
    void canSkipDirectoryRecordsWhenConfigured() throws Exception {
        properties.getRecent().setRecordDirectories(false);
        Files.createDirectories(root.resolve("docs"));
        Files.writeString(root.resolve("note.txt"), "hello");

        recentService.recordVaultPath("docs");
        recentService.recordVaultPath("note.txt");

        assertThat(recentService.storedItems())
                .extracting(RecentItem::path)
                .containsExactly("note.txt");
    }

    @Test
    void movesAndRemovesDescendantRecentItems() throws Exception {
        Files.createDirectories(root.resolve("docs"));
        Files.writeString(root.resolve("docs").resolve("note.txt"), "hello");
        recentService.recordVaultPath("docs");
        recentService.recordVaultPath("docs/note.txt");

        Files.move(root.resolve("docs"), root.resolve("renamed"));
        recentService.moveVaultPath("docs", "renamed");

        assertThat(recentService.storedItems())
                .extracting(RecentItem::path)
                .containsExactly("renamed/note.txt", "renamed");

        recentService.removeVaultPath("renamed");

        assertThat(recentService.storedItems()).isEmpty();
    }

    @Test
    void filtersRecentItemsByNameOrPath() throws Exception {
        Files.createDirectories(root.resolve("docs"));
        Files.writeString(root.resolve("docs").resolve("note.txt"), "hello");
        Files.writeString(root.resolve("other.txt"), "other");
        recentService.recordVaultPath("docs/note.txt");
        recentService.recordVaultPath("other.txt");

        assertThat(recentService.list("docs", RecentSort.RECENT, SortDirection.DESC))
                .extracting(RecentListItem::path)
                .containsExactly("docs/note.txt");
    }

    @Test
    void exposesComicMetadataForRecentGridCards() throws Exception {
        Files.writeString(root.resolve("book.cbz"), "comic");
        recentService.recordVaultPath("book.cbz");

        RecentListItem item = recentService.list("", RecentSort.RECENT, SortDirection.DESC).getFirst();

        assertThat(item.comic()).isTrue();
        assertThat(item.typeLabel()).isEqualTo("Comic");
        assertThat(item.extensionLabel()).isEqualTo("CBZ");
    }
}
