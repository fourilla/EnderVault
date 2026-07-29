package io.github.fourilla.endervault.favorite;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import io.github.fourilla.endervault.bookmark.BookmarkItem;
import io.github.fourilla.endervault.bookmark.BookmarkMetadataFetcher;
import io.github.fourilla.endervault.bookmark.BookmarkService;
import io.github.fourilla.endervault.common.StorageAccessException;
import io.github.fourilla.endervault.config.NasProperties;
import io.github.fourilla.endervault.outbound.OutboundHttpClientRegistry;
import io.github.fourilla.endervault.outbound.vpn.VpnProxyHealthService;
import io.github.fourilla.endervault.storage.StorageService;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class FavoriteServiceTest {

    @TempDir
    Path root;

    private FavoriteService favoriteService;
    private BookmarkService bookmarkService;

    @BeforeEach
    void setUp() throws Exception {
        NasProperties properties = new NasProperties();
        properties.getStorage().setRoot(root);
        StorageService storageService = new StorageService(properties);
        storageService.initialize();

        ObjectMapper objectMapper = JsonMapper.builder().findAndAddModules().build();
        bookmarkService = new BookmarkService(
                objectMapper,
                properties,
                new BookmarkMetadataFetcher(
                        properties,
                        new OutboundHttpClientRegistry(new VpnProxyHealthService(properties))
                )
        );
        bookmarkService.initialize();
        favoriteService = new FavoriteService(storageService, bookmarkService, objectMapper, properties);
        favoriteService.initialize();
    }

    @Test
    void togglesFileFavoriteInMetadataRegistry() throws Exception {
        Files.writeString(root.resolve("note.txt"), "hello");

        FavoriteItem favorite = favoriteService.toggle("note.txt");

        assertThat(favorite.path()).isEqualTo("note.txt");
        assertThat(favorite.type()).isEqualTo(FavoriteTargetType.FILE);
        assertThat(favoriteService.isFavorite("note.txt")).isTrue();
        assertThat(root.resolve(".endervault").resolve("favorites.json")).exists();

        FavoriteItem removed = favoriteService.toggle("note.txt");

        assertThat(removed).isNull();
        assertThat(favoriteService.list()).isEmpty();
    }

    @Test
    void createsDirectoryFavorite() throws Exception {
        Files.createDirectories(root.resolve("docs"));

        FavoriteItem favorite = favoriteService.toggle("docs");

        assertThat(favorite.type()).isEqualTo(FavoriteTargetType.DIRECTORY);
        assertThat(favorite.directory()).isTrue();
    }

    @Test
    void togglesBookmarkFavorite() throws Exception {
        BookmarkItem bookmark = bookmarkService.createLink(null, "Docs", "https://example.com/docs", "");

        FavoriteItem favorite = favoriteService.toggleBookmark(bookmark.id());

        assertThat(favorite.path()).isEqualTo("bookmark:" + bookmark.id());
        assertThat(favorite.type()).isEqualTo(FavoriteTargetType.BOOKMARK_LINK);
        assertThat(favorite.name()).isEqualTo("Docs");
        assertThat(favorite.openUrl()).isEqualTo("/files/bookmarks/open?id=" + bookmark.id());
        assertThat(favoriteService.favoriteBookmarkIds()).containsExactly(bookmark.id());

        FavoriteItem removed = favoriteService.toggleBookmark(bookmark.id());

        assertThat(removed).isNull();
        assertThat(favoriteService.list()).isEmpty();
    }

    @Test
    void rejectsRootFavorite() {
        assertThatThrownBy(() -> favoriteService.toggle(""))
                .isInstanceOf(StorageAccessException.class);
    }

    @Test
    void movesFavoritesForPathAndDescendants() throws Exception {
        Files.createDirectories(root.resolve("docs").resolve("sub"));
        Files.writeString(root.resolve("docs").resolve("sub").resolve("note.txt"), "hello");
        favoriteService.toggle("docs");
        favoriteService.toggle("docs/sub/note.txt");

        Files.move(root.resolve("docs"), root.resolve("renamed"));
        favoriteService.moveVaultPath("docs", "renamed");

        assertThat(favoriteService.list())
                .extracting(FavoriteItem::path)
                .containsExactly("renamed", "renamed/sub/note.txt");
    }

    @Test
    void removesFavoritesForPathAndDescendants() throws Exception {
        Files.createDirectories(root.resolve("docs"));
        Files.writeString(root.resolve("docs").resolve("note.txt"), "hello");
        favoriteService.toggle("docs");
        favoriteService.toggle("docs/note.txt");

        favoriteService.removeVaultPath("docs");

        assertThat(favoriteService.list()).isEmpty();
    }

    @Test
    void reordersFavorites() throws Exception {
        Files.writeString(root.resolve("a.txt"), "a");
        Files.writeString(root.resolve("b.txt"), "b");
        favoriteService.toggle("a.txt");
        favoriteService.toggle("b.txt");

        favoriteService.move("b.txt", "up");

        assertThat(favoriteService.list())
                .extracting(FavoriteItem::path)
                .containsExactly("b.txt", "a.txt");
    }
}
