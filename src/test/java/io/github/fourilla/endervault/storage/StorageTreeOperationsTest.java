package io.github.fourilla.endervault.storage;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import io.github.fourilla.endervault.common.StorageAccessException;
import io.github.fourilla.endervault.filesystem.FileSystemOperations;
import io.github.fourilla.endervault.temporary.TemporaryArtifactRegistry;
import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class StorageTreeOperationsTest {
    @TempDir Path root;
    FileSystemOperations fileSystem;
    StorageTreeOperations operations;
    Path staging;
    Path destination;

    @BeforeEach void setup() throws Exception {
        staging = Files.createDirectories(root.resolve("metadata/file-staging"));
        destination = Files.createDirectories(root.resolve("destination"));
        var paths = new StoragePathResolver(root, root.resolve("trash"), root.resolve("metadata"), staging);
        var registry = new TemporaryArtifactRegistry();
        fileSystem = mock(FileSystemOperations.class);
        when(fileSystem.sameFileSystem(any(), any())).thenReturn(true);
        when(fileSystem.moveNoReplace(any(), any())).thenReturn(FileSystemOperations.MoveResult.UNSUPPORTED);
        operations = new StorageTreeOperations(paths, new FileStagingService(staging, paths, registry), registry, fileSystem);
    }

    @Test void unsupportedNoReplaceUsesTheExistingHardLinkAndUnlinkCommit() throws Exception {
        Path source = Files.writeString(staging.resolve("source.tmp"), "content");
        Path target = destination.resolve("published.txt");
        operations.commitRegularFileNoReplace(source, target);
        assertThat(source).doesNotExist();
        assertThat(target).hasContent("content");
        verify(fileSystem).syncDirectory(destination);
        verify(fileSystem).syncDirectory(staging);
        verify(fileSystem, never()).moveAtomically(any(), any());
        verify(fileSystem, never()).moveWithAtomicPreference(any(), any());
    }

    @Test void alreadyLinkedCommitResumesWithoutRepeatingTheMove() throws Exception {
        Path source = Files.writeString(staging.resolve("source.tmp"), "content");
        Path target = Files.createLink(destination.resolve("published.txt"), source);
        assertThat(Files.isSameFile(source, target)).isTrue();
        operations.commitRegularFileNoReplace(source, target);
        assertThat(source).doesNotExist();
        assertThat(target).hasContent("content");
        verify(fileSystem, never()).moveNoReplace(any(), any());
    }

    @Test void movedFileDoesNotAttemptToUnlinkTheAbsentSourceAgain() throws Exception {
        Path source = Files.writeString(staging.resolve("source.tmp"), "content");
        Path target = destination.resolve("published.txt");
        when(fileSystem.moveNoReplace(source, target)).thenAnswer(call -> {
            Files.move(source, target);
            return FileSystemOperations.MoveResult.MOVED;
        });
        operations.commitRegularFileNoReplace(source, target);
        assertThat(source).doesNotExist();
        assertThat(target).hasContent("content");
        verify(fileSystem).syncDirectory(destination);
        verify(fileSystem).syncDirectory(staging);
        verify(fileSystem, never()).moveAtomically(any(), any());
    }

    @Test void uncertainMoveFailureDoesNotPublishUsingTheHardLinkFallback() throws Exception {
        Path source = Files.writeString(staging.resolve("source.tmp"), "content");
        Path target = destination.resolve("published.txt");
        when(fileSystem.moveNoReplace(source, target)).thenThrow(new IOException("uncertain move outcome"));
        assertThatThrownBy(() -> operations.commitRegularFileNoReplace(source, target)).isInstanceOf(IOException.class);
        assertThat(source).hasContent("content");
        assertThat(target).doesNotExist();
        verify(fileSystem, never()).syncDirectory(any());
    }

    @Test void failureReportedAfterAFileWasMovedKeepsThePublishedFileForJournalRecovery() throws Exception {
        Path source = Files.writeString(staging.resolve("source.tmp"), "content");
        Path target = destination.resolve("published.txt");
        when(fileSystem.moveNoReplace(source, target)).thenAnswer(call -> {
            Files.move(source, target);
            throw new IOException("unknown outcome after rename");
        });
        assertThatThrownBy(() -> operations.commitRegularFileNoReplace(source, target)).isInstanceOf(IOException.class);
        assertThat(source).doesNotExist();
        assertThat(target).hasContent("content");
        verify(fileSystem, never()).moveAtomically(any(), any());
        verify(fileSystem, never()).moveWithAtomicPreference(any(), any());
    }

    @Test void destinationAppearingDuringMoveIsNeverOverwrittenOrDeleted() throws Exception {
        Path source = Files.writeString(staging.resolve("source.tmp"), "content");
        Path target = destination.resolve("published.txt");
        when(fileSystem.moveNoReplace(source, target)).thenAnswer(call -> {
            Files.writeString(target, "external content");
            throw new FileAlreadyExistsException(target.toString());
        });
        assertThatThrownBy(() -> operations.commitRegularFileNoReplace(source, target))
                .isInstanceOf(FileAlreadyExistsException.class);
        assertThat(source).hasContent("content");
        assertThat(target).hasContent("external content");
    }

    @Test void unsupportedDirectoryMoveRetainsTheRequiredAtomicFallback() throws Exception {
        Path source = Files.createDirectory(staging.resolve("directory"));
        Files.writeString(source.resolve("file.txt"), "content");
        Path target = destination.resolve("directory");
        doAnswer(call -> Files.move(source, target)).when(fileSystem).moveAtomically(source, target);
        operations.commitEntryNoReplace(source, target);
        assertThat(source).doesNotExist();
        assertThat(target.resolve("file.txt")).hasContent("content");
        verify(fileSystem).moveAtomically(source, target);
        verify(fileSystem, never()).moveWithAtomicPreference(any(), any());
    }

    @Test void directoryAtomicFallbackCannotDowngradeToAnOrdinaryMove() throws Exception {
        Path source = Files.createDirectory(staging.resolve("directory"));
        Path target = destination.resolve("directory");
        doThrow(new AtomicMoveNotSupportedException(source.toString(), target.toString(), "unsupported"))
                .when(fileSystem).moveAtomically(source, target);
        assertThatThrownBy(() -> operations.commitEntryNoReplace(source, target))
                .isInstanceOf(StorageAccessException.class).hasCauseInstanceOf(AtomicMoveNotSupportedException.class);
        assertThat(source).isDirectory();
        assertThat(target).doesNotExist();
        verify(fileSystem, never()).moveWithAtomicPreference(any(), any());
    }

    @Test void uncertainDirectoryMoveFailureDoesNotTryTheAtomicFallback() throws Exception {
        Path source = Files.createDirectory(staging.resolve("directory"));
        Path target = destination.resolve("directory");
        when(fileSystem.moveNoReplace(source, target)).thenThrow(new IOException("uncertain move outcome"));
        assertThatThrownBy(() -> operations.commitEntryNoReplace(source, target)).isInstanceOf(IOException.class);
        assertThat(source).isDirectory();
        assertThat(target).doesNotExist();
        verify(fileSystem, never()).moveAtomically(any(), any());
    }

    @Test void failureReportedAfterADirectoryWasMovedDoesNotTouchThePublishedTree() throws Exception {
        Path source = Files.createDirectory(staging.resolve("directory"));
        Files.writeString(source.resolve("file.txt"), "content");
        Path target = destination.resolve("directory");
        when(fileSystem.moveNoReplace(source, target)).thenAnswer(call -> {
            Files.move(source, target);
            throw new IOException("unknown outcome after rename");
        });
        assertThatThrownBy(() -> operations.commitEntryNoReplace(source, target)).isInstanceOf(IOException.class);
        assertThat(source).doesNotExist();
        assertThat(target.resolve("file.txt")).hasContent("content");
        verify(fileSystem, never()).moveAtomically(any(), any());
    }

    @Test void replacementKeepsTheBackupLinkAndRequiresAtomicReplacement() throws Exception {
        Path source = Files.writeString(staging.resolve("source.tmp"), "new content");
        Path target = Files.writeString(destination.resolve("published.txt"), "old content");
        Path backup = staging.resolve("backup.tmp");
        operations.createRegularFileReplacementBackup(target, backup);
        assertThat(Files.isSameFile(target, backup)).isTrue();
        doAnswer(call -> Files.move(source, target, java.nio.file.StandardCopyOption.REPLACE_EXISTING))
                .when(fileSystem).replace(source, target, FileSystemOperations.Atomicity.REQUIRED);
        operations.commitRegularFileReplace(source, target);
        assertThat(target).hasContent("new content");
        assertThat(backup).hasContent("old content");
        verify(fileSystem, never()).moveNoReplace(any(), any());
    }

    @Test void ordinaryMoveFallsBackOnlyWhenThePrimitiveReportsUnsupported() throws Exception {
        Path source = Files.writeString(staging.resolve("source.tmp"), "content");
        Path target = destination.resolve("published.txt");
        doAnswer(call -> Files.move(source, target)).when(fileSystem).moveWithAtomicPreference(source, target);
        operations.move(source, target);
        assertThat(source).doesNotExist();
        assertThat(target).hasContent("content");
        verify(fileSystem).moveWithAtomicPreference(source, target);
    }

    @Test void ordinaryMoveDoesNotFallbackAfterAnUnknownFailure() throws Exception {
        Path source = Files.writeString(staging.resolve("source.tmp"), "content");
        Path target = destination.resolve("published.txt");
        when(fileSystem.moveNoReplace(source, target)).thenThrow(new IOException("uncertain move outcome"));
        assertThatThrownBy(() -> operations.move(source, target)).isInstanceOf(IOException.class);
        assertThat(source).hasContent("content");
        assertThat(target).doesNotExist();
        verify(fileSystem, never()).moveWithAtomicPreference(any(), any());
    }

    @Test void differentMountedFilesystemsAreRejectedBeforePublishing() throws Exception {
        Path source = Files.writeString(staging.resolve("source.tmp"), "content");
        Path target = destination.resolve("published.txt");
        when(fileSystem.sameFileSystem(source, destination)).thenReturn(false);
        assertThatThrownBy(() -> operations.commitRegularFileNoReplace(source, target))
                .isInstanceOf(StorageAccessException.class).hasMessageContaining("one filesystem");
        assertThat(source).hasContent("content");
        assertThat(target).doesNotExist();
        verify(fileSystem, never()).moveNoReplace(any(), any());
    }
}
