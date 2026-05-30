package io.github.fourilla.endervault.storage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.fourilla.endervault.common.StorageAccessException;
import io.github.fourilla.endervault.config.NasProperties;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mock.web.MockMultipartFile;

class StorageServiceTest {

    @TempDir
    Path root;

    private StorageService storageService;

    @BeforeEach
    void setUp() throws Exception {
        NasProperties properties = new NasProperties();
        properties.getStorage().setRoot(root);
        storageService = new StorageService(properties);
        storageService.initialize();
    }

    @Test
    void listsVaultWithoutInternalSystemFolders() throws Exception {
        Files.createDirectories(root.resolve("docs"));
        Files.writeString(root.resolve("note.txt"), "hello");

        DirectoryListing listing = storageService.list(StorageScope.VAULT, "");

        assertThat(listing.directories()).extracting(FileItem::name).containsExactly("docs");
        assertThat(listing.files()).extracting(FileItem::name).containsExactly("note.txt");
    }

    @Test
    void listsVaultFilesWithRequestedSort() throws Exception {
        Files.writeString(root.resolve("small.txt"), "a");
        Files.writeString(root.resolve("large.txt"), "larger");

        DirectoryListing listing = storageService.list(
                StorageScope.VAULT,
                "",
                FileSort.SIZE,
                SortDirection.DESC
        );

        assertThat(listing.files()).extracting(FileItem::name).containsExactly("large.txt", "small.txt");
    }

    @Test
    void listsVaultItemsByModifiedTime() throws Exception {
        Path older = root.resolve("older.txt");
        Path newer = root.resolve("newer.txt");
        Files.writeString(older, "old");
        Files.writeString(newer, "new");
        Files.setLastModifiedTime(older, FileTime.from(Instant.parse("2024-01-01T00:00:00Z")));
        Files.setLastModifiedTime(newer, FileTime.from(Instant.parse("2024-01-02T00:00:00Z")));

        DirectoryListing listing = storageService.list(
                StorageScope.VAULT,
                "",
                FileSort.MODIFIED,
                SortDirection.ASC
        );

        assertThat(listing.files()).extracting(FileItem::name).containsExactly("older.txt", "newer.txt");
    }

    @Test
    void vaultScopeCannotEscapeStorageRoot() {
        assertThatThrownBy(() -> storageService.list(StorageScope.VAULT, "../"))
                .isInstanceOf(StorageAccessException.class);
    }

    @Test
    void sharedDirectoryCannotEscapeSharedRoot() throws Exception {
        Files.createDirectories(root.resolve("shared"));

        assertThatThrownBy(() -> storageService.listSharedDirectory("shared", "../"))
                .isInstanceOf(StorageAccessException.class);
    }

    @Test
    void describesVaultFileDetail() throws Exception {
        Files.writeString(root.resolve("note.txt"), "hello");

        FileDetail detail = storageService.detail(StorageScope.VAULT, "note.txt");

        assertThat(detail.name()).isEqualTo("note.txt");
        assertThat(detail.path()).isEqualTo("note.txt");
        assertThat(detail.parentPath()).isEmpty();
        assertThat(detail.extension()).isEqualTo("txt");
        assertThat(detail.sizeLabel()).isEqualTo("5 B");
        assertThat(detail.previewable()).isTrue();
    }

    @Test
    void rejectsChildNamesWithPathSegments() {
        assertThatThrownBy(() -> storageService.createDirectory("", "../escape"))
                .isInstanceOf(StorageAccessException.class);
    }

    @Test
    void uploadsAndRenamesFilesWithinVault() throws Exception {
        MockMultipartFile file = new MockMultipartFile("files", "demo.txt", "text/plain", "demo".getBytes());

        storageService.upload("", file);
        storageService.rename("", "demo.txt", "renamed.txt");

        assertThat(Files.readString(root.resolve("renamed.txt"))).isEqualTo("demo");
    }

    @Test
    void renamesVaultPathAndReturnsNewPath() throws Exception {
        Files.createDirectories(root.resolve("docs"));
        Files.writeString(root.resolve("docs").resolve("note.txt"), "hello");

        String newPath = storageService.renameVaultPath("docs/note.txt", "renamed.txt");

        assertThat(newPath).isEqualTo("docs/renamed.txt");
        assertThat(root.resolve("docs").resolve("renamed.txt")).exists();
    }

    @Test
    void refusesToDeleteVaultRoot() {
        assertThatThrownBy(() -> storageService.deleteVaultPath(""))
                .isInstanceOf(StorageAccessException.class);
    }

    @Test
    void deletesSelectedItems() throws Exception {
        Files.writeString(root.resolve("a.txt"), "a");
        Files.createDirectories(root.resolve("dir"));
        Files.writeString(root.resolve("dir").resolve("b.txt"), "b");

        storageService.delete("", List.of("a.txt", "dir"));

        assertThat(root.resolve("a.txt")).doesNotExist();
        assertThat(root.resolve("dir")).doesNotExist();
    }
}
