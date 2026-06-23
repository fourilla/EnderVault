package io.github.fourilla.endervault.storage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.fourilla.endervault.common.StorageAccessException;
import io.github.fourilla.endervault.config.NasProperties;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Instant;
import java.util.List;
import java.util.stream.Stream;
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
    void listsVaultWithoutInternalSystemDirectories() throws Exception {
        Files.createDirectories(root.resolve("docs"));
        Files.writeString(root.resolve("note.txt"), "hello");

        DirectoryListing listing = storageService.list(StorageScope.VAULT, "");

        assertThat(listing.directories()).extracting(FileItem::name).containsExactly("docs");
        assertThat(listing.files()).extracting(FileItem::name).containsExactly("note.txt");
    }

    @Test
    void fileItemReportsExtensionLabelForGridBadges() {
        FileItem archive = new FileItem(
                "backup.zip",
                "backup.zip",
                false,
                0,
                "0 B",
                "-",
                Instant.EPOCH,
                "application/zip",
                false,
                false
        );
        FileItem withoutExtension = new FileItem(
                "README",
                "README",
                false,
                0,
                "0 B",
                "-",
                Instant.EPOCH,
                "text/plain",
                true,
                false
        );

        assertThat(archive.extensionLabel()).isEqualTo("ZIP");
        assertThat(withoutExtension.extensionLabel()).isEqualTo("Text");
    }

    @Test
    void listsTrashDirectorySeparately() throws Exception {
        Files.writeString(root.resolve(".trash").resolve("deleted.txt"), "deleted");

        DirectoryListing listing = storageService.listTrash();

        assertThat(listing.path()).isEmpty();
        assertThat(listing.files()).extracting(FileItem::name).containsExactly("deleted.txt");
    }

    @Test
    void reportsStorageUsageForRootDisk() {
        StorageUsage usage = storageService.storageUsage();

        assertThat(usage.totalBytes()).isGreaterThanOrEqualTo(0L);
        assertThat(usage.usedBytes()).isGreaterThanOrEqualTo(0L);
        assertThat(usage.usedLabel()).isNotBlank();
        assertThat(usage.totalLabel()).isNotBlank();
        assertThat(usage.usedPercent()).isBetween(0, 100);
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
    void searchesVaultRecursivelyFromRequestedRoot() throws Exception {
        Files.createDirectories(root.resolve("docs").resolve("nested"));
        Files.createDirectories(root.resolve("other"));
        Files.writeString(root.resolve("docs").resolve("nested").resolve("report.txt"), "report");
        Files.writeString(root.resolve("other").resolve("report.txt"), "outside");

        List<FileItem> results = storageService.search(StorageScope.VAULT, "docs", "REPORT");

        assertThat(results).extracting(FileItem::path).containsExactly("docs/nested/report.txt");
    }

    @Test
    void searchReturnsMatchingDirectoriesAndFiles() throws Exception {
        Files.createDirectories(root.resolve("photos-match"));
        Files.writeString(root.resolve("match-note.txt"), "hello");

        List<FileItem> results = storageService.search(StorageScope.VAULT, "", "match");

        assertThat(results).extracting(FileItem::path).containsExactly("match-note.txt", "photos-match");
        assertThat(results).extracting(FileItem::typeLabel).containsExactly("Text", "Directory");
    }

    @Test
    void searchSkipsInternalSystemDirectories() throws Exception {
        Files.writeString(root.resolve(".trash").resolve("secret-match.txt"), "deleted");
        Files.writeString(root.resolve(".endervault").resolve("metadata-match.txt"), "metadata");
        Files.writeString(root.resolve("visible-match.txt"), "visible");

        List<FileItem> results = storageService.search(StorageScope.VAULT, "", "match");

        assertThat(results).extracting(FileItem::path).containsExactly("visible-match.txt");
    }

    @Test
    void sharedDirectoryHidesInternalSystemDirectories() throws Exception {
        Files.writeString(root.resolve(".trash").resolve("deleted.txt"), "deleted");
        Files.writeString(root.resolve(".endervault").resolve("metadata.json"), "metadata");
        Files.writeString(root.resolve("visible.txt"), "visible");

        DirectoryListing listing = storageService.listSharedDirectory("", "");

        assertThat(listing.directories()).extracting(FileItem::name).isEmpty();
        assertThat(listing.files()).extracting(FileItem::name).containsExactly("visible.txt");
    }

    @Test
    void sharedDirectoryRejectsDirectInternalSystemDirectoryAccess() {
        assertThatThrownBy(() -> storageService.listSharedDirectory("", ".endervault"))
                .isInstanceOf(NoSuchFileException.class);
    }

    @Test
    void sharedDirectoryRejectsDirectInternalSystemDirectoryDescendants() throws Exception {
        Files.writeString(root.resolve(".endervault").resolve("metadata.json"), "metadata");

        assertThatThrownBy(() -> storageService.listSharedDirectory("", ".endervault/metadata.json"))
                .isInstanceOf(NoSuchFileException.class);
    }

    @Test
    void vaultDirectPathRejectsInternalSystemDirectoryAccess() {
        assertThatThrownBy(() -> storageService.detail(StorageScope.VAULT, ".endervault"))
                .isInstanceOf(NoSuchFileException.class);
    }

    @Test
    void vaultDirectPathRejectsInternalSystemDirectoryDescendants() throws Exception {
        Files.writeString(root.resolve(".endervault").resolve("shared-links.json"), "[]");

        assertThatThrownBy(() -> storageService.resolveVaultFile(".endervault/shared-links.json"))
                .isInstanceOf(NoSuchFileException.class);
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
    void uploadDoesNotLeaveInternalTemporaryFiles() throws Exception {
        MockMultipartFile file = new MockMultipartFile("files", "clean.txt", "text/plain", "clean".getBytes());

        storageService.upload("", file);

        assertThat(Files.readString(root.resolve("clean.txt"))).isEqualTo("clean");
        try (Stream<Path> temporaryFiles = Files.list(root.resolve(".endervault").resolve("uploads"))) {
            assertThat(temporaryFiles).isEmpty();
        }
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
    void copiesVaultFileToTargetDirectory() throws Exception {
        Files.createDirectories(root.resolve("target"));
        Files.writeString(root.resolve("note.txt"), "hello");

        String copiedPath = storageService.copyVaultPath("note.txt", "target");

        assertThat(copiedPath).isEqualTo("target/note.txt");
        assertThat(Files.readString(root.resolve("note.txt"))).isEqualTo("hello");
        assertThat(Files.readString(root.resolve("target").resolve("note.txt"))).isEqualTo("hello");
    }

    @Test
    void copiesVaultDirectoryRecursivelyToTargetDirectory() throws Exception {
        Files.createDirectories(root.resolve("docs").resolve("nested"));
        Files.createDirectories(root.resolve("target"));
        Files.writeString(root.resolve("docs").resolve("nested").resolve("note.txt"), "hello");

        String copiedPath = storageService.copyVaultPath("docs", "target");

        assertThat(copiedPath).isEqualTo("target/docs");
        assertThat(Files.readString(root.resolve("target").resolve("docs").resolve("nested").resolve("note.txt")))
                .isEqualTo("hello");
    }

    @Test
    void refusesToCopyDirectoryIntoItself() throws Exception {
        Files.createDirectories(root.resolve("docs").resolve("nested"));

        assertThatThrownBy(() -> storageService.copyVaultPath("docs", "docs/nested"))
                .isInstanceOf(StorageAccessException.class);
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
