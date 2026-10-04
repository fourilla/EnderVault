package io.github.fourilla.endervault.storage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.CALLS_REAL_METHODS;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;

import io.github.fourilla.endervault.common.StorageAccessException;
import io.github.fourilla.endervault.config.NasProperties;
import java.nio.file.AccessDeniedException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.FileTime;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.MockedStatic;
import org.mockito.Mockito;

class StorageSearchOptimizationTest {
    @TempDir
    Path root;
    private StorageService storage;

    @BeforeEach
    void setUp() throws Exception {
        NasProperties properties = new NasProperties();
        properties.getStorage().setRoot(root);
        storage = new StorageService(properties);
        storage.initialize();
    }

    @Test
    void readsEachSortKeyOnceAndRefreshesItBeforeTraversal() throws Exception {
        List<Path> children = new ArrayList<>();
        for (int i = 0; i < 64; i++) {
            Path child = root.resolve("report" + i * 47 % 64 + ".txt");
            children.add(Files.writeString(child, "report"));
        }
        try (MockedStatic<Files> files = Mockito.mockStatic(Files.class, CALLS_REAL_METHODS)) {
            assertThat(storage.search(StorageScope.VAULT, "", "name:missing", true)).isEmpty();
            for (Path child : children) {
                files.verify(() -> Files.isDirectory(child, LinkOption.NOFOLLOW_LINKS), times(1));
                files.verify(() -> Files.isDirectory(child), times(1));
                files.verify(() -> Files.probeContentType(child), never());
            }
        }
    }

    @Test
    void refreshesTraversalTypeWhenADirectoryBecomesAFileWhileSorting() throws Exception {
        Path changed = Files.createDirectory(root.resolve("changed"));
        Path trigger = Files.writeString(root.resolve("trigger.txt"), "trigger");
        try (MockedStatic<Files> files = Mockito.mockStatic(Files.class, CALLS_REAL_METHODS)) {
            files.when(() -> Files.list(root)).thenAnswer(invocation -> Stream.of(changed, trigger));
            files.when(() -> Files.isDirectory(trigger)).thenAnswer(invocation -> {
                if (Files.isDirectory(changed)) {
                    Files.delete(changed);
                    Files.writeString(changed, "now a file");
                }
                return false;
            });
            assertThat(storage.search(StorageScope.VAULT, "", "type:file name:changed", true))
                    .extracting(FileItem::directory).containsExactly(false);
            files.verify(() -> Files.list(changed), never());
        }
    }

    @Test
    void usesOneFreshAttributeReadForResultSizeAndModifiedTime() throws Exception {
        Path file = Files.writeString(root.resolve("report.txt"), "report");
        try (MockedStatic<Files> files = Mockito.mockStatic(Files.class, CALLS_REAL_METHODS)) {
            assertThat(storage.search(StorageScope.VAULT, "", "size:>=1 modified:>=2000-01-01"))
                    .extracting(FileItem::size).containsExactly(6L);
            files.verify(() -> Files.readAttributes(file, BasicFileAttributes.class), times(1));
            files.verify(() -> Files.size(file), never());
            files.verify(() -> Files.getLastModifiedTime(file), never());
        }
    }

    @Test
    void refreshesResultMetadataEvenWhenPredicateAlreadyReadIt() throws Exception {
        Path file = Files.writeString(root.resolve("report.txt"), "a");
        Instant changed = Instant.parse("2026-10-04T00:00:00Z");
        try (MockedStatic<Files> files = Mockito.mockStatic(Files.class, CALLS_REAL_METHODS)) {
            files.when(() -> Files.probeContentType(file)).thenAnswer(invocation -> {
                Files.writeString(file, "changed");
                Files.setLastModifiedTime(file, FileTime.from(changed));
                return "text/plain";
            });
            var results = storage.search(StorageScope.VAULT, "", "size:1");
            assertThat(results).extracting(FileItem::size).containsExactly(7L);
            assertThat(results).extracting(FileItem::modifiedAt).containsExactly(changed);
        }
    }

    @Test
    void stillRejectsAFileDeletedAfterPredicateEvaluation() throws Exception {
        Path file = Files.writeString(root.resolve("report.txt"), "a");
        try (MockedStatic<Files> files = Mockito.mockStatic(Files.class, CALLS_REAL_METHODS)) {
            files.when(() -> Files.probeContentType(file)).thenAnswer(invocation -> {
                Files.delete(file);
                return "text/plain";
            });
            assertThatThrownBy(() -> storage.search(StorageScope.VAULT, "", "size:1"))
                    .isInstanceOf(StorageAccessException.class).hasMessage("Failed to read file metadata.");
        }
    }

    @Test
    void preservesResultAccessErrorsInsteadOfReturningTheCachedPredicateSnapshot() throws Exception {
        Path file = Files.writeString(root.resolve("report.txt"), "a");
        try (MockedStatic<Files> files = Mockito.mockStatic(Files.class, CALLS_REAL_METHODS)) {
            files.when(() -> Files.readAttributes(file, BasicFileAttributes.class))
                    .thenThrow(new AccessDeniedException(file.toString()));
            assertThatThrownBy(() -> storage.search(StorageScope.VAULT, "", "size:1"))
                    .isInstanceOf(StorageAccessException.class).hasMessage("Failed to read file metadata.")
                    .hasCauseInstanceOf(AccessDeniedException.class);
        }
    }

    @Test
    void keepsDirectoryFirstNaturalTraversalAndFinalNaturalPathOrdering() throws Exception {
        Files.createDirectories(root.resolve("Q11"));
        Files.createDirectories(root.resolve("Q2"));
        Path nested11 = Files.writeString(root.resolve("Q11/report.txt"), "11");
        Path nested2 = Files.writeString(root.resolve("Q2/report.txt"), "2");
        Path top11 = Files.writeString(root.resolve("report11.txt"), "11");
        Path top2 = Files.writeString(root.resolve("report2.txt"), "2");
        List<Path> visited = new ArrayList<>();
        try (MockedStatic<Files> files = Mockito.mockStatic(Files.class, CALLS_REAL_METHODS)) {
            for (Path file : List.of(nested11, nested2, top11, top2)) {
                files.when(() -> Files.probeContentType(file)).thenAnswer(invocation -> {
                    visited.add(file);
                    return "text/plain";
                });
            }
            assertThat(storage.search(StorageScope.VAULT, "", "report"))
                    .extracting(FileItem::path).containsExactly(
                            "Q2/report.txt", "Q11/report.txt", "report2.txt", "report11.txt");
            assertThat(visited).containsExactly(nested2, nested11, top2, top11);
        }
    }

    @Test
    void rereadsCandidatesAndResultsOnTheNextSearch() throws Exception {
        Path file = Files.writeString(root.resolve("report.txt"), "a");
        assertThat(storage.search(StorageScope.VAULT, "", "size:1")).hasSize(1);
        Files.writeString(file, "changed");
        assertThat(storage.search(StorageScope.VAULT, "", "size:1")).isEmpty();
        assertThat(storage.search(StorageScope.VAULT, "", "size:7"))
                .extracting(FileItem::size).containsExactly(7L);
    }

    @Test
    void keepsSearchPayloadIdenticalToTheExistingDescriptionForFilesAndDirectories() throws Exception {
        Files.createDirectory(root.resolve("sample-directory"));
        Files.writeString(root.resolve("sample-report.txt"), "report");
        Files.write(root.resolve("sample-video.mp4"), new byte[] {1, 2, 3});
        Files.write(root.resolve("sample-hidden.pdf"), new byte[] {1, 2, 3});
        storage.setHiddenVaultPath("sample-hidden.pdf", true, ConflictPolicy.CANCEL);
        var results = storage.search(StorageScope.VAULT, "", "name:sample", true);
        assertThat(results).hasSize(4);
        for (FileItem item : results) {
            assertThat(item).isEqualTo(storage.describeVaultPath(item.path()));
        }
        assertThat(storage.search(StorageScope.VAULT, "", "name:sample", false))
                .noneMatch(FileItem::hidden).hasSize(3);
    }
}
