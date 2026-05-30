package io.github.fourilla.endervault.storage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.fourilla.endervault.common.StorageAccessException;
import io.github.fourilla.endervault.config.NasProperties;
import java.nio.file.Files;
import java.nio.file.Path;
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

        assertThat(listing.directories()).extracting(FileItem::name).containsExactly("docs", "public");
        assertThat(listing.files()).extracting(FileItem::name).containsExactly("note.txt");
    }

    @Test
    void publicScopeCannotEscapePublicFolder() {
        assertThatThrownBy(() -> storageService.list(StorageScope.PUBLIC, "../"))
                .isInstanceOf(StorageAccessException.class);
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
    void deletesSelectedItems() throws Exception {
        Files.writeString(root.resolve("a.txt"), "a");
        Files.createDirectories(root.resolve("dir"));
        Files.writeString(root.resolve("dir").resolve("b.txt"), "b");

        storageService.delete("", List.of("a.txt", "dir"));

        assertThat(root.resolve("a.txt")).doesNotExist();
        assertThat(root.resolve("dir")).doesNotExist();
    }
}

