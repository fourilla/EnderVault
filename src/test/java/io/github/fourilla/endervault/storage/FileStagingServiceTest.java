package io.github.fourilla.endervault.storage;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import io.github.fourilla.endervault.filesystem.FileSystemOperations;
import io.github.fourilla.endervault.temporary.TemporaryArtifactRegistry;
import java.io.IOException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class FileStagingServiceTest {
    @TempDir Path root;
    Path staging;
    FileSystemOperations fileSystem;
    FileStagingService service;

    @BeforeEach void setup() throws Exception {
        staging = Files.createDirectories(root.resolve("metadata/file-staging"));
        var paths = new StoragePathResolver(root, root.resolve("trash"), root.resolve("metadata"), staging);
        fileSystem = mock(FileSystemOperations.class);
        service = new FileStagingService(staging, paths, new TemporaryArtifactRegistry(), fileSystem);
    }

    @Test void unsupportedClaimUsesTheExistingAtomicPreferenceWithoutReplacingATarget() throws Exception {
        Path source = Files.writeString(service.resumableProtocolRoot().resolve("data"), "upload content");
        String session = UUID.randomUUID().toString();
        Path target = service.resumableStagingFile(session);
        when(fileSystem.moveNoReplace(source, target)).thenReturn(FileSystemOperations.MoveResult.UNSUPPORTED);
        doAnswer(call -> Files.move(source, target)).when(fileSystem).moveWithAtomicPreference(source, target);
        assertThat(service.claimResumableUpload(source, session)).isEqualTo(target).hasContent("upload content");
        assertThat(source).doesNotExist();
        verify(fileSystem).moveWithAtomicPreference(source, target);
        verify(fileSystem, never()).replace(any(), any(), any());
    }

    @Test void successfulClaimUsesOnlyOneMove() throws Exception {
        Path source = Files.writeString(service.resumableProtocolRoot().resolve("data"), "upload content");
        String session = UUID.randomUUID().toString();
        Path target = service.resumableStagingFile(session);
        when(fileSystem.moveNoReplace(source, target)).thenAnswer(call -> {
            Files.move(source, target);
            return FileSystemOperations.MoveResult.MOVED;
        });
        assertThat(service.claimResumableUpload(source, session)).isEqualTo(target).hasContent("upload content");
        assertThat(source).doesNotExist();
        verify(fileSystem, never()).moveWithAtomicPreference(any(), any());
    }

    @Test void uncertainClaimFailureRetainsTheProtocolFileAndDoesNotFallback() throws Exception {
        Path source = Files.writeString(service.resumableProtocolRoot().resolve("data"), "upload content");
        String session = UUID.randomUUID().toString();
        Path target = service.resumableStagingFile(session);
        when(fileSystem.moveNoReplace(source, target)).thenThrow(new IOException("uncertain claim outcome"));
        assertThatThrownBy(() -> service.claimResumableUpload(source, session)).isInstanceOf(IOException.class);
        assertThat(source).hasContent("upload content");
        assertThat(target).doesNotExist();
        verify(fileSystem, never()).moveWithAtomicPreference(any(), any());
    }

    @Test void claimTargetAppearingDuringTheMoveKeepsBothFiles() throws Exception {
        Path source = Files.writeString(service.resumableProtocolRoot().resolve("data"), "upload content");
        String session = UUID.randomUUID().toString();
        Path target = service.resumableStagingFile(session);
        when(fileSystem.moveNoReplace(source, target)).thenAnswer(call -> {
            Files.writeString(target, "external target");
            throw new FileAlreadyExistsException(target.toString());
        });
        assertThatThrownBy(() -> service.claimResumableUpload(source, session)).isInstanceOf(FileAlreadyExistsException.class);
        assertThat(source).hasContent("upload content");
        assertThat(target).hasContent("external target");
        verify(fileSystem, never()).moveWithAtomicPreference(any(), any());
    }

    @Test void temporaryFileClaimIntentionallyReplacesItsReservedEmptyPlaceholder() throws Exception {
        Path source = Files.writeString(root.resolve("temporary-input"), "temporary content");
        doAnswer(call -> {
            Path target = call.getArgument(1, Path.class);
            assertThat(target).isEmptyFile();
            Files.move(source, target, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            return null;
        }).when(fileSystem).replace(eq(source), any(), eq(FileSystemOperations.Atomicity.PREFERRED));
        Path target = service.claimTemporaryFile(source, "claim-", ".tmp");
        assertThat(target).hasParent(staging).hasContent("temporary content");
        assertThat(source).doesNotExist();
        verify(fileSystem, never()).moveNoReplace(any(), any());
    }

    @Test void replacementFailureAfterPublicationKeepsTheClaimedStagingFile() throws Exception {
        Path source = Files.writeString(root.resolve("temporary-input"), "temporary content");
        var claimedTarget = new AtomicReference<Path>();
        doAnswer(call -> {
            Path target = call.getArgument(1, Path.class);
            claimedTarget.set(target);
            Files.move(source, target, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            throw new IOException("unknown outcome after replacement");
        }).when(fileSystem).replace(eq(source), any(), eq(FileSystemOperations.Atomicity.PREFERRED));
        assertThatThrownBy(() -> service.claimTemporaryFile(source, "claim-", ".tmp"))
                .isInstanceOf(IOException.class).hasMessage("unknown outcome after replacement");
        assertThat(source).doesNotExist();
        assertThat(claimedTarget.get()).hasParent(staging).hasContent("temporary content");
        verify(fileSystem, never()).moveNoReplace(any(), any());
    }

    @Test void replacementFailureBeforePublicationKeepsTheSourceAndReservedPlaceholder() throws Exception {
        Path source = Files.writeString(root.resolve("temporary-input"), "temporary content");
        var reservedTarget = new AtomicReference<Path>();
        doAnswer(call -> {
            reservedTarget.set(call.getArgument(1, Path.class));
            throw new IOException("replacement failed before publication");
        }).when(fileSystem).replace(eq(source), any(), eq(FileSystemOperations.Atomicity.PREFERRED));
        assertThatThrownBy(() -> service.claimTemporaryFile(source, "claim-", ".tmp"))
                .isInstanceOf(IOException.class).hasMessage("replacement failed before publication");
        assertThat(source).hasContent("temporary content");
        assertThat(reservedTarget.get()).hasParent(staging).isEmptyFile();
        verify(fileSystem, never()).moveNoReplace(any(), any());
    }
}
