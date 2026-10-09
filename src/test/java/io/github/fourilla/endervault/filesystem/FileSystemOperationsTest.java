package io.github.fourilla.endervault.filesystem;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.io.IOException;
import java.nio.file.AccessDeniedException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.CopyOption;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class FileSystemOperationsTest {

    @TempDir
    Path root;

    @Test
    void explicitlyDisabledNativeAccessNeverCallsTheLoader() {
        AtomicInteger calls = new AtomicInteger();

        NativeFileSystem backend = FileSystemOperationsProvider.nativeBackend(false, () -> {
            calls.incrementAndGet();
            throw new AssertionError("A disabled backend must not load native classes.");
        });

        assertThat(backend).isNull();
        assertThat(calls.get()).isZero();
    }

    @Test
    void allNonfatalBootstrapFailuresLeaveTheNioBackendAvailable() throws Exception {
        for (Throwable failure : new Throwable[] {new UnsatisfiedLinkError("missing library"),
                new NoClassDefFoundError("missing JNA"), new Error("native version mismatch"),
                new IllegalStateException("blocked loading")}) {
            NativeFileSystem backend = FileSystemOperationsProvider.nativeBackend(true, () -> {
                throwUnchecked(failure);
                return null;
            });
            assertThat(backend).isNull();
            Path source = Files.writeString(root.resolve("source-" + failure.getClass().getSimpleName()), "content");
            Path target = source.resolveSibling(source.getFileName() + ".moved");
            FileSystemOperations operations = new DefaultFileSystemOperations(backend, new NioFileSystem());

            assertThat(operations.supportsNoReplaceMove(source, root)).isFalse();
            assertThat(operations.moveNoReplace(source, target)).isEqualTo(FileSystemOperations.MoveResult.UNSUPPORTED);
            assertThat(source).exists();
            assertThat(target).doesNotExist();
            operations.moveWithAtomicPreference(source, target);
            assertThat(Files.readString(target)).isEqualTo("content");
        }
    }

    @Test
    void fatalBootstrapErrorsAreNeverConvertedToUnsupported() throws Exception {
        for (Error failure : new Error[] {new OutOfMemoryError("fatal"), new ThreadDeath()}) {
            assertFatalRethrown(failure, () -> FileSystemOperationsProvider.nativeBackend(true, () -> {
                throw failure;
            }));
        }
    }

    @Test
    void unsupportedBackendDoesNotMutateOrSecretlyUseNio() throws Exception {
        NioFileSystem nio = mock(NioFileSystem.class);
        FileSystemOperations operations = new DefaultFileSystemOperations(null, nio);
        Path source = Files.writeString(root.resolve("source"), "content");
        Path target = root.resolve("target");

        assertThat(operations.supportsNoReplaceMove(source, root)).isFalse();
        assertThat(operations.moveNoReplace(source, target)).isEqualTo(FileSystemOperations.MoveResult.UNSUPPORTED);

        verifyNoInteractions(nio);
        assertThat(Files.readString(source)).isEqualTo("content");
        assertThat(target).doesNotExist();
    }

    @Test
    void unexpectedCapabilityFailuresAreConservativeBeforeAnyMutation() throws Exception {
        NativeFileSystem backend = mock(NativeFileSystem.class);
        NioFileSystem nio = mock(NioFileSystem.class);
        FileSystemOperations operations = new DefaultFileSystemOperations(backend, nio);
        Path source = Files.writeString(root.resolve("source"), "content");
        when(backend.supportsNoReplaceMove(source, root)).thenThrow(new Error("query failed"));

        assertThat(operations.supportsNoReplaceMove(source, root)).isFalse();
        assertThat(operations.moveNoReplace(source, root.resolve("target")))
                .isEqualTo(FileSystemOperations.MoveResult.UNSUPPORTED);

        verifyNoInteractions(nio);
        assertThat(source).exists();
        assertThat(root.resolve("target")).doesNotExist();
    }

    @Test
    void checkedPermissionFailuresCannotSelectAnotherPipeline() throws Exception {
        NativeFileSystem backend = mock(NativeFileSystem.class);
        FileSystemOperations operations = new DefaultFileSystemOperations(backend, mock(NioFileSystem.class));
        Path source = Files.writeString(root.resolve("source"), "content");
        when(backend.supportsNoReplaceMove(source, root)).thenThrow(new AccessDeniedException(source.toString()));

        assertThatThrownBy(() -> operations.moveNoReplace(source, root.resolve("target")))
                .isInstanceOf(AccessDeniedException.class);
        assertThat(source).exists();
    }

    @Test
    void mutationErrorsNeverAuthorizeFallbackEvenWhenTheRenameAlreadySucceeded() throws Exception {
        Path source = Files.writeString(root.resolve("source"), "content");
        Path target = root.resolve("target");
        NioFileSystem nio = mock(NioFileSystem.class);
        for (Throwable failure : new Throwable[] {new IllegalStateException("runtime"),
                new UnsatisfiedLinkError("linkage"), new Error("nonfatal native failure")}) {
            if (!Files.exists(source)) {
                Files.move(target, source);
            }
            NativeFileSystem backend = mock(NativeFileSystem.class);
            when(backend.supportsNoReplaceMove(source, root)).thenReturn(true);
            when(backend.moveNoReplace(source, target)).thenAnswer(invocation -> {
                Files.move(source, target);
                throwUnchecked(failure);
                return null;
            });
            FileSystemOperations operations = new DefaultFileSystemOperations(backend, nio);

            assertThatThrownBy(() -> operations.moveNoReplace(source, target))
                    .isInstanceOf(IOException.class).hasCause(failure)
                    .hasMessageContaining("outcome could not be determined");

            assertThat(source).doesNotExist();
            assertThat(Files.readString(target)).isEqualTo("content");
            verifyNoInteractions(nio);
        }
    }

    @Test
    void fatalMutationErrorsAreRethrown() throws Exception {
        Path source = Files.writeString(root.resolve("source"), "content");
        NativeFileSystem backend = mock(NativeFileSystem.class);
        when(backend.supportsNoReplaceMove(source, root)).thenReturn(true);
        FileSystemOperations operations = new DefaultFileSystemOperations(backend, mock(NioFileSystem.class));
        for (Error failure : new Error[] {new OutOfMemoryError("fatal"), new ThreadDeath()}) {
            doThrow(failure).when(backend).moveNoReplace(source, root.resolve("target"));
            assertFatalRethrown(failure, () -> operations.moveNoReplace(source, root.resolve("target")));
        }
    }

    @Test
    void identityUsesNioFirstAndPreservesUnavailablePathFailures() throws Exception {
        NativeFileSystem backend = mock(NativeFileSystem.class);
        FileSystemOperations operations = new DefaultFileSystemOperations(backend, new NioFileSystem());
        Path source = Files.writeString(root.resolve("source"), "content");
        Object nioIdentity = Files.readAttributes(source, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS).fileKey();
        when(backend.readIdentity(source)).thenReturn("optional-native-identity");

        assertThat(operations.readIdentity(source)).isEqualTo(nioIdentity == null
                ? "optional-native-identity" : nioIdentity.toString());
        assertThatThrownBy(() -> operations.readIdentity(root.resolve("missing"))).isInstanceOf(NoSuchFileException.class);
    }

    @Test
    void nativeIdentityFailureDoesNotCrashNioOnlyOperation() throws Exception {
        NativeFileSystem backend = mock(NativeFileSystem.class);
        FileSystemOperations operations = new DefaultFileSystemOperations(backend, new NioFileSystem());
        Path source = Files.writeString(root.resolve("source"), "content");
        Object nioIdentity = Files.readAttributes(source, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS).fileKey();
        when(backend.readIdentity(source)).thenThrow(new Error("identity binding became unavailable"));

        assertThat(operations.readIdentity(source)).isEqualTo(nioIdentity == null ? null : nioIdentity.toString());
        operations.moveWithAtomicPreference(source, root.resolve("target"));
        assertThat(Files.readString(root.resolve("target"))).isEqualTo("content");
    }

    @Test
    void unavailableNativeFilesystemQueryPreservesTheNioPipeline() throws Exception {
        NativeFileSystem backend = mock(NativeFileSystem.class);
        FileSystemOperations operations = new DefaultFileSystemOperations(backend, new NioFileSystem());
        Path source = Files.writeString(root.resolve("source"), "content");
        for (Throwable failure : new Throwable[] {new IllegalStateException("query unavailable"),
                new UnsatisfiedLinkError("binding unavailable"), new Error("nonfatal query failure")}) {
            doThrow(failure).when(backend).sameFileSystem(source, root);
            assertThat(operations.sameFileSystem(source, root)).isTrue();
        }
        assertThat(new DefaultFileSystemOperations(null, new NioFileSystem()).sameFileSystem(source, root)).isTrue();
    }

    @Test
    void authoritativeDifferentNativeVolumesCannotFallBackToNioEquality() throws Exception {
        NativeFileSystem backend = mock(NativeFileSystem.class);
        FileSystemOperations operations = new DefaultFileSystemOperations(backend, new NioFileSystem());
        Path source = Files.writeString(root.resolve("source"), "content");
        when(backend.sameFileSystem(source, root)).thenReturn(false);

        assertThat(operations.sameFileSystem(source, root)).isFalse();
    }

    @Test
    void checkedFilesystemQueryPermissionFailureStillPropagates() throws Exception {
        NativeFileSystem backend = mock(NativeFileSystem.class);
        FileSystemOperations operations = new DefaultFileSystemOperations(backend, new NioFileSystem());
        Path source = Files.writeString(root.resolve("source"), "content");
        when(backend.sameFileSystem(source, root)).thenThrow(new AccessDeniedException(source.toString()));

        assertThatThrownBy(() -> operations.sameFileSystem(source, root)).isInstanceOf(AccessDeniedException.class);
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void preferredAtomicReplacementFallsBackOnlyForExplicitUnsupportedAtomicity(boolean replace) throws Exception {
        Path source = Files.writeString(root.resolve("source"), "new content");
        Path target = root.resolve("target");
        if (replace) {
            Files.writeString(target, "old content");
        }
        RecordingNio nio = new RecordingNio(new AtomicMoveNotSupportedException(source.toString(), target.toString(), "test"));
        FileSystemOperations operations = new DefaultFileSystemOperations(null, nio);

        if (replace) {
            operations.replace(source, target, FileSystemOperations.Atomicity.PREFERRED);
            assertThat(nio.options).containsExactly(
                    List.of(StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING),
                    List.of(StandardCopyOption.REPLACE_EXISTING));
        } else {
            operations.moveWithAtomicPreference(source, target);
            assertThat(nio.options).containsExactly(List.of(StandardCopyOption.ATOMIC_MOVE), List.of());
        }

        assertThat(source).doesNotExist();
        assertThat(Files.readString(target)).isEqualTo("new content");
    }

    @Test
    void requiredAtomicityCannotFallBack() throws Exception {
        Path source = Files.writeString(root.resolve("source"), "new content");
        Path target = Files.writeString(root.resolve("target"), "old content");
        RecordingNio nio = new RecordingNio(new AtomicMoveNotSupportedException(source.toString(), target.toString(), "test"));
        FileSystemOperations operations = new DefaultFileSystemOperations(null, nio);

        assertThatThrownBy(() -> operations.replace(source, target, FileSystemOperations.Atomicity.REQUIRED))
                .isInstanceOf(AtomicMoveNotSupportedException.class);

        assertThat(nio.options).hasSize(1);
        assertThat(Files.readString(source)).isEqualTo("new content");
        assertThat(Files.readString(target)).isEqualTo("old content");
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void genericIoFailuresNeverFallBackToAnotherMove(boolean replace) throws Exception {
        Path source = Files.writeString(root.resolve("source"), "new content");
        Path target = Files.writeString(root.resolve("target"), "old content");
        IOException failure = new IOException("uncertain operation result");
        RecordingNio nio = new RecordingNio(failure);
        FileSystemOperations operations = new DefaultFileSystemOperations(null, nio);

        assertThatThrownBy(() -> {
            if (replace) {
                operations.replace(source, target, FileSystemOperations.Atomicity.PREFERRED);
            } else {
                operations.moveWithAtomicPreference(source, target);
            }
        }).isSameAs(failure);

        assertThat(nio.options).hasSize(1);
        assertThat(Files.readString(source)).isEqualTo("new content");
        assertThat(Files.readString(target)).isEqualTo("old content");
    }

    @Test
    void atomicLegacyMoveAndSyncDelegateWithoutChangingTheirContracts() throws Exception {
        NioFileSystem nio = mock(NioFileSystem.class);
        FileSystemOperations operations = new DefaultFileSystemOperations(null, nio);
        Path source = root.resolve("source");
        Path target = root.resolve("target");
        when(nio.syncDirectory(root)).thenReturn(FileSystemOperations.SyncResult.UNSUPPORTED);

        operations.moveAtomically(source, target);
        operations.syncFile(source);
        assertThat(operations.syncDirectory(root)).isEqualTo(FileSystemOperations.SyncResult.UNSUPPORTED);

        verify(nio).move(source, target, StandardCopyOption.ATOMIC_MOVE);
        verify(nio).syncFile(source);
        verify(nio).syncDirectory(root);
    }

    private static void throwUnchecked(Throwable failure) {
        if (failure instanceof RuntimeException exception) {
            throw exception;
        }
        throw (Error) failure;
    }

    private static void assertFatalRethrown(Error expected, CheckedAction action) throws Exception {
        Error observed = null;
        try {
            action.run();
        } catch (Error failure) {
            observed = failure;
        }
        assertThat(observed).isSameAs(expected);
    }

    @FunctionalInterface
    private interface CheckedAction {
        void run() throws Exception;
    }

    private static final class RecordingNio extends NioFileSystem {
        private final IOException initialFailure;
        private final List<List<CopyOption>> options = new ArrayList<>();

        private RecordingNio(IOException initialFailure) {
            this.initialFailure = initialFailure;
        }

        @Override
        void move(Path source, Path target, CopyOption... currentOptions) throws IOException {
            options.add(Arrays.asList(currentOptions));
            if (options.size() == 1) {
                throw initialFailure;
            }
            super.move(source, target, currentOptions);
        }
    }
}
