package io.github.fourilla.endervault.storage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.fourilla.endervault.common.StorageAccessException;
import io.github.fourilla.endervault.config.NasProperties;
import io.github.fourilla.endervault.task.TaskCanceledException;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
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
    void filtersDirectoryListingsBeforeCreatingFileItems() throws Exception {
        Files.createDirectories(root.resolve("docs"));
        Files.writeString(root.resolve("note.txt"), "hello");

        DirectoryListing listing = storageService.list(
                StorageScope.VAULT,
                "",
                FileSort.NAME,
                SortDirection.ASC,
                false,
                StorageEntryFilter.DIRECTORIES
        );

        assertThat(listing.directories()).extracting(FileItem::name).containsExactly("docs");
        assertThat(listing.files()).isEmpty();
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
                false,
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
    void listsVaultFilesWithNaturalNameOrderingInBothDirections() throws Exception {
        Files.writeString(root.resolve("Q11.txt"), "eleven");
        Files.writeString(root.resolve("Q2-3.txt"), "two");
        Files.writeString(root.resolve("Q1.txt"), "one");

        DirectoryListing ascending = storageService.list(
                StorageScope.VAULT,
                "",
                FileSort.NAME,
                SortDirection.ASC
        );
        DirectoryListing descending = storageService.list(
                StorageScope.VAULT,
                "",
                FileSort.NAME,
                SortDirection.DESC
        );

        assertThat(ascending.files()).extracting(FileItem::name)
                .containsExactly("Q1.txt", "Q2-3.txt", "Q11.txt");
        assertThat(descending.files()).extracting(FileItem::name)
                .containsExactly("Q11.txt", "Q2-3.txt", "Q1.txt");
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
    void searchResultsUseNaturalPathOrdering() throws Exception {
        Files.writeString(root.resolve("report11.txt"), "eleven");
        Files.writeString(root.resolve("report3.txt"), "three");

        List<FileItem> results = storageService.search(StorageScope.VAULT, "", "report");

        assertThat(results).extracting(FileItem::path).containsExactly("report3.txt", "report11.txt");
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
    void vaultListAllowsDirectHiddenDirectoryAccessButFiltersHiddenChildren() throws Exception {
        Files.createDirectories(root.resolve("secret"));
        Files.writeString(root.resolve("secret").resolve("visible.txt"), "visible");
        Files.writeString(root.resolve("secret").resolve("hidden.txt"), "hidden");
        String hiddenDirectory = storageService.setHiddenVaultPath("secret", true, ConflictPolicy.CANCEL);
        storageService.setHiddenVaultPath(hiddenDirectory + "/hidden.txt", true, ConflictPolicy.CANCEL);

        DirectoryListing rootListing = storageService.list(
                StorageScope.VAULT,
                "",
                FileSort.NAME,
                SortDirection.ASC,
                false
        );
        DirectoryListing hiddenListing = storageService.list(
                StorageScope.VAULT,
                hiddenDirectory,
                FileSort.NAME,
                SortDirection.ASC,
                false
        );

        assertThat(rootListing.directories()).extracting(FileItem::path).doesNotContain(hiddenDirectory);
        assertThat(hiddenListing.files()).extracting(FileItem::name).containsExactly("visible.txt");
    }

    @Test
    void vaultSearchRejectsHiddenRootWhenHiddenItemsAreNotVisible() throws Exception {
        Files.createDirectories(root.resolve("secret"));
        Files.writeString(root.resolve("secret").resolve("visible-match.txt"), "visible");
        String hiddenDirectory = storageService.setHiddenVaultPath("secret", true, ConflictPolicy.CANCEL);

        assertThatThrownBy(() -> storageService.search(StorageScope.VAULT, hiddenDirectory, "match", false))
                .isInstanceOf(NoSuchFileException.class);
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
    void sharedDirectoryRejectsDirectHiddenDirectoryAccess() throws Exception {
        Files.createDirectories(root.resolve("shared").resolve("secret"));
        Files.writeString(root.resolve("shared").resolve("secret").resolve("visible.txt"), "visible");
        String hiddenDirectory = storageService.setHiddenVaultPath("shared/secret", true, ConflictPolicy.CANCEL);
        String hiddenSharedPath = hiddenDirectory.substring("shared/".length());

        assertThatThrownBy(() -> storageService.listSharedDirectory("shared", hiddenSharedPath))
                .isInstanceOf(NoSuchFileException.class);
        assertThatThrownBy(() -> storageService.resolveSharedFile("shared", hiddenSharedPath, "visible.txt"))
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
    void vaultScopeRejectsTraversalAliasesBeforeNormalization() throws Exception {
        Files.createDirectories(root.resolve("docs"));
        Files.writeString(root.resolve("visible.txt"), "visible");

        assertThatThrownBy(() -> storageService.detail(StorageScope.VAULT, "docs/../visible.txt"))
                .isInstanceOf(StorageAccessException.class);
    }

    @Test
    void vaultScopeRejectsInvalidRelativePathSegments() {
        assertThatThrownBy(() -> storageService.detail(StorageScope.VAULT, "CON.txt"))
                .isInstanceOf(StorageAccessException.class);
        assertThatThrownBy(() -> storageService.detail(StorageScope.VAULT, "docs/note.txt."))
                .isInstanceOf(StorageAccessException.class);
        assertThatThrownBy(() -> storageService.detail(StorageScope.VAULT, "docs/note.txt "))
                .isInstanceOf(StorageAccessException.class);
    }

    @Test
    void sharedDirectoryCannotEscapeSharedRoot() throws Exception {
        Files.createDirectories(root.resolve("shared"));

        assertThatThrownBy(() -> storageService.listSharedDirectory("shared", "../"))
                .isInstanceOf(StorageAccessException.class);
    }

    @Test
    void sharedDirectoryRejectsTraversalAliasesBeforeNormalization() throws Exception {
        Files.createDirectories(root.resolve("shared").resolve("docs"));
        Files.writeString(root.resolve("shared").resolve("visible.txt"), "visible");

        assertThatThrownBy(() -> storageService.listSharedDirectory("shared", "docs/../"))
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
    void createsEmptyVaultFile() throws Exception {
        storageService.createFile("", "note.txt");

        assertThat(root.resolve("note.txt")).isRegularFile();
        assertThat(Files.size(root.resolve("note.txt"))).isZero();
    }

    @Test
    void rejectsWindowsReservedChildNames() {
        assertThatThrownBy(() -> storageService.createFile("", "con.txt"))
                .isInstanceOf(StorageAccessException.class);
    }

    @Test
    void rejectsChildNamesWithInvalidCharacters() {
        assertThatThrownBy(() -> storageService.createFile("", "bad|name.txt"))
                .isInstanceOf(StorageAccessException.class);
    }

    @Test
    void rejectsChildNamesWithTrailingDotOrWhitespace() {
        assertThatThrownBy(() -> storageService.createFile("", "note.txt."))
                .isInstanceOf(StorageAccessException.class);
        assertThatThrownBy(() -> storageService.createFile("", "note.txt "))
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
        try (Stream<Path> temporaryFiles = Files.list(root.resolve(".endervault").resolve("file-staging"))) {
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

    @Test
    void writesSelectedFilesAndDirectoriesToZipWithProgress() throws Exception {
        Files.createDirectories(root.resolve("docs").resolve("empty"));
        Files.writeString(root.resolve("docs").resolve("guide.txt"), "guide");
        Files.writeString(root.resolve("root.txt"), "root");
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        AtomicLong processedBytes = new AtomicLong();
        AtomicLong processedItems = new AtomicLong();

        storageService.writeZip(
                StorageScope.VAULT,
                "",
                List.of("docs", "root.txt"),
                output,
                new StorageProgressListener() {
                    @Override
                    public void onBytesProcessed(long bytes) {
                        processedBytes.addAndGet(bytes);
                    }

                    @Override
                    public void onItemProcessed() {
                        processedItems.incrementAndGet();
                    }
                }
        );

        Map<String, String> entries = zipEntries(output.toByteArray());
        assertThat(entries.keySet()).containsExactly(
                "docs/",
                "docs/empty/",
                "docs/guide.txt",
                "root.txt"
        );
        assertThat(entries.get("docs/guide.txt")).isEqualTo("guide");
        assertThat(entries.get("root.txt")).isEqualTo("root");
        assertThat(processedBytes).hasValue(9L);
        assertThat(processedItems).hasValue(4L);
    }

    @Test
    void writesDirectoryChildrenToZipInNaturalNameOrder() throws Exception {
        Files.createDirectories(root.resolve("docs"));
        Files.writeString(root.resolve("docs").resolve("page11.txt"), "eleven");
        Files.writeString(root.resolve("docs").resolve("page3.txt"), "three");
        ByteArrayOutputStream output = new ByteArrayOutputStream();

        storageService.writeZip(StorageScope.VAULT, "", List.of("docs"), output);

        assertThat(zipEntries(output.toByteArray()).keySet()).containsExactly(
                "docs/",
                "docs/page3.txt",
                "docs/page11.txt"
        );
    }

    @Test
    void zipWritingChecksCancellationWhileReadingFileContent() throws Exception {
        Files.write(root.resolve("large.bin"), new byte[192 * 1024]);
        ByteArrayOutputStream output = new ByteArrayOutputStream();

        assertThatThrownBy(() -> storageService.writeZip(
                StorageScope.VAULT,
                "",
                List.of("large.bin"),
                output,
                new StorageProgressListener() {
                    private long processed;

                    @Override
                    public void onBytesProcessed(long bytes) {
                        processed += bytes;
                        if (processed >= 64 * 1024) {
                            throw new TaskCanceledException();
                        }
                    }
                }
        )).isInstanceOf(TaskCanceledException.class);
    }

    @Test
    void commitsArchiveTopLevelEntriesDirectlyIntoDestination() throws Exception {
        Files.createDirectories(root.resolve("target"));
        Path workspace = storageService.createArchiveExtractionWorkspace();
        Path content = Files.createDirectory(workspace.resolve("content"));
        Files.createDirectories(content.resolve("docs"));
        Files.writeString(content.resolve("docs").resolve("guide.txt"), "guide");
        Files.writeString(content.resolve("readme.txt"), "readme");

        StorageBatchCommitResult result = storageService.commitArchiveContentsIntoVault(
                content,
                "target",
                List.of(
                        new StorageBatchEntry("docs", true),
                        new StorageBatchEntry("readme.txt", false)
                ),
                ConflictPolicy.CANCEL,
                StorageProgressListener.NOOP
        );

        assertThat(result.committedPaths()).containsExactly("target/docs", "target/readme.txt");
        assertThat(root.resolve("target/docs/guide.txt")).hasContent("guide");
        assertThat(root.resolve("target/readme.txt")).hasContent("readme");
        assertThat(content).isEmptyDirectory();
    }

    @Test
    void rejectsKnownDirectArchiveConflictDuringPreflight() throws Exception {
        Files.writeString(root.resolve("readme.txt"), "existing");

        assertThatThrownBy(() -> storageService.preflightArchiveExtraction(
                "",
                false,
                "ignored",
                List.of(new StorageBatchEntry("readme.txt", false)),
                ConflictPolicy.CANCEL
        )).isInstanceOf(FileAlreadyExistsException.class);

        assertThat(root.resolve("readme.txt")).hasContent("existing");
    }

    @Test
    void directArchiveRenamePolicyReservesEveryPlannedTarget() throws Exception {
        Files.writeString(root.resolve("report.txt"), "existing");
        Path workspace = storageService.createArchiveExtractionWorkspace();
        Path content = Files.createDirectory(workspace.resolve("content"));
        Files.writeString(content.resolve("report.txt"), "first");
        Files.writeString(content.resolve("report - 1.txt"), "second");

        StorageBatchCommitResult result = storageService.commitArchiveContentsIntoVault(
                content,
                "",
                List.of(
                        new StorageBatchEntry("report.txt", false),
                        new StorageBatchEntry("report - 1.txt", false)
                ),
                ConflictPolicy.RENAME,
                StorageProgressListener.NOOP
        );

        assertThat(result.committedPaths()).containsExactly("report - 1.txt", "report - 1 - 1.txt");
        assertThat(root.resolve("report.txt")).hasContent("existing");
        assertThat(root.resolve("report - 1.txt")).hasContent("first");
        assertThat(root.resolve("report - 1 - 1.txt")).hasContent("second");
    }

    @Test
    void directArchiveCommitRollsBackAlreadyMovedEntriesWhenCanceled() throws Exception {
        Path workspace = storageService.createArchiveExtractionWorkspace();
        Path content = Files.createDirectory(workspace.resolve("content"));
        Files.writeString(content.resolve("a.txt"), "a");
        Files.writeString(content.resolve("b.txt"), "b");
        StorageProgressListener cancelBeforeSecondMove = new StorageProgressListener() {
            @Override
            public void checkCanceled() {
                if (Files.exists(root.resolve("a.txt"))) {
                    throw new TaskCanceledException();
                }
            }
        };

        assertThatThrownBy(() -> storageService.commitArchiveContentsIntoVault(
                content,
                "",
                List.of(
                        new StorageBatchEntry("a.txt", false),
                        new StorageBatchEntry("b.txt", false)
                ),
                ConflictPolicy.CANCEL,
                cancelBeforeSecondMove
        )).isInstanceOf(TaskCanceledException.class);

        assertThat(content.resolve("a.txt")).hasContent("a");
        assertThat(content.resolve("b.txt")).hasContent("b");
        assertThat(root.resolve("a.txt")).doesNotExist();
        assertThat(root.resolve("b.txt")).doesNotExist();
    }

    @Test
    void directArchiveCommitLeavesChangedTargetAndReportsPartialFailure() throws Exception {
        Path workspace = storageService.createArchiveExtractionWorkspace();
        Path content = Files.createDirectory(workspace.resolve("content"));
        Files.writeString(content.resolve("a.txt"), "a");
        Files.writeString(content.resolve("b.txt"), "b");
        StorageProgressListener mutateBeforeSecondMove = new StorageProgressListener() {
            @Override
            public void checkCanceled() {
                if (Files.exists(root.resolve("a.txt"))) {
                    try {
                        Files.writeString(root.resolve("a.txt"), "changed after commit");
                    } catch (java.io.IOException ex) {
                        throw new IllegalStateException(ex);
                    }
                    throw new TaskCanceledException();
                }
            }
        };

        assertThatThrownBy(() -> storageService.commitArchiveContentsIntoVault(
                content,
                "",
                List.of(
                        new StorageBatchEntry("a.txt", false),
                        new StorageBatchEntry("b.txt", false)
                ),
                ConflictPolicy.CANCEL,
                mutateBeforeSecondMove
        )).isInstanceOfSatisfying(PartialStorageCommitException.class, failure ->
                assertThat(failure.remainingVaultPaths()).containsExactly("a.txt")
        );

        assertThat(root.resolve("a.txt")).hasContent("changed after commit");
        assertThat(content.resolve("a.txt")).doesNotExist();
        assertThat(content.resolve("b.txt")).hasContent("b");
    }

    private Map<String, String> zipEntries(byte[] archive) throws Exception {
        Map<String, String> entries = new LinkedHashMap<>();
        try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(archive))) {
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                entries.put(entry.getName(), new String(zip.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8));
            }
        }
        return entries;
    }
}
