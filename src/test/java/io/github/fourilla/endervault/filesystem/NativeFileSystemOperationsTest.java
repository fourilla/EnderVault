package io.github.fourilla.endervault.filesystem;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.github.fourilla.endervault.filesystem.NativeFileSystemOperations.NativeResult;
import io.github.fourilla.endervault.filesystem.NativeFileSystemOperations.OperatingSystem;
import java.io.IOException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.FileSystem;
import java.nio.file.FileStore;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class NativeFileSystemOperationsTest {

    @TempDir
    Path root;

    @Test
    void nativeRenameMovesTheWholeDirectoryWithoutCopying() throws Exception {
        NativeFileSystemOperations support = new NativeFileSystemOperations();
        Path source = Files.createDirectory(root.resolve("source 한글 + & [1], %;"));
        Path child = Files.writeString(source.resolve("note.txt"), "original content");
        Path targetParent = Files.createDirectory(root.resolve("destination"));
        Path target = targetParent.resolve(source.getFileName());
        String directoryIdentity = fileKey(source);
        String fileIdentity = fileKey(child);
        requireSupported(support, source, targetParent);
        assertThat(directoryIdentity).isNotNull();
        assertThat(fileIdentity).isNotNull();

        FileSystemOperations.MoveResult moved = support.moveNoReplace(source, target);

        if (moved != FileSystemOperations.MoveResult.MOVED) {
            assertThat(source).isDirectory();
            assertThat(target).doesNotExist();
        }
        requireMoved(moved);
        assertThat(source).doesNotExist();
        assertThat(target).isDirectory();
        assertThat(fileKey(target)).isEqualTo(directoryIdentity);
        assertThat(fileKey(target.resolve("note.txt"))).isEqualTo(fileIdentity);
        assertThat(Files.readString(target.resolve("note.txt"))).isEqualTo("original content");
    }

    @ParameterizedTest
    @ValueSource(strings = {"empty-directory", "nonempty-directory", "file"})
    void nativeRenameDoesNotReplaceACompetingTarget(String kind) throws Exception {
        NativeFileSystemOperations support = new NativeFileSystemOperations();
        Path source = Files.createDirectory(root.resolve("source"));
        Files.writeString(source.resolve("note.txt"), "source content");
        Path targetParent = Files.createDirectory(root.resolve("destination"));
        Path target = targetParent.resolve("source");
        if ("file".equals(kind)) {
            Files.writeString(target, "competing file");
        } else {
            Files.createDirectory(target);
            if ("nonempty-directory".equals(kind)) {
                Files.writeString(target.resolve("competitor.txt"), "competing content");
            }
        }
        String sourceIdentity = fileKey(source);
        String targetIdentity = fileKey(target);
        requireSupported(support, source, targetParent);
        assertThat(sourceIdentity).isNotNull();
        assertThat(targetIdentity).isNotNull();

        assertThatThrownBy(() -> support.moveNoReplace(source, target))
                .isInstanceOf(FileAlreadyExistsException.class);

        assertThat(fileKey(source)).isEqualTo(sourceIdentity);
        assertThat(fileKey(target)).isEqualTo(targetIdentity);
        assertThat(Files.readString(source.resolve("note.txt"))).isEqualTo("source content");
        if ("file".equals(kind)) {
            assertThat(Files.readString(target)).isEqualTo("competing file");
        } else if ("nonempty-directory".equals(kind)) {
            assertThat(Files.readString(target.resolve("competitor.txt"))).isEqualTo("competing content");
            assertThat(target.resolve("note.txt")).doesNotExist();
        } else {
            try (var entries = Files.list(target)) {
                assertThat(entries).isEmpty();
            }
        }
    }

    @Test
    void nativeRenameSupportsLongUnicodePaths() throws Exception {
        NativeFileSystemOperations support = new NativeFileSystemOperations();
        Path source = Files.createDirectory(root.resolve("원본 폴더"));
        Files.writeString(source.resolve("한글 + & [1], %;.txt"), "content");
        Path targetParent = root;
        for (int index = 0; index < 8; index++) {
            targetParent = Files.createDirectory(targetParent.resolve("long-directory-name-한글-0123456789-" + index));
        }
        Path target = targetParent.resolve("대상 폴더");
        assertThat(target.toAbsolutePath().toString().length()).isGreaterThan(260);
        requireSupported(support, source, targetParent);

        FileSystemOperations.MoveResult moved = support.moveNoReplace(source, target);

        if (moved != FileSystemOperations.MoveResult.MOVED) {
            assertThat(source).isDirectory();
            assertThat(target).doesNotExist();
        }
        requireMoved(moved);
        assertThat(source).doesNotExist();
        assertThat(Files.readString(target.resolve("한글 + & [1], %;.txt"))).isEqualTo("content");
    }

    @Test
    void unavailableNativeBindingDoesNotAttemptOrCopyAnything() throws Exception {
        NativeFileSystemOperations support = new NativeFileSystemOperations(OperatingSystem.current(), null);
        Path source = Files.createDirectory(root.resolve("source"));
        Files.writeString(source.resolve("note.txt"), "content");
        Path targetParent = Files.createDirectory(root.resolve("destination"));
        Path target = targetParent.resolve("source");

        assertThat(support.supportsNoReplaceMove(source, targetParent)).isFalse();
        assertThat(support.moveNoReplace(source, target)).isEqualTo(FileSystemOperations.MoveResult.UNSUPPORTED);

        assertThat(Files.readString(source.resolve("note.txt"))).isEqualTo("content");
        assertThat(target).doesNotExist();
    }

    @Test
    void customPathProvidersAreExcludedBeforeNativeInvocation() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        NativeFileSystemOperations support = new NativeFileSystemOperations(OperatingSystem.current(), (source, target) -> {
            calls.incrementAndGet();
            return new NativeResult(true, 0);
        });
        Path custom = mock(Path.class);
        when(custom.getFileSystem()).thenReturn(mock(FileSystem.class));

        assertThat(support.supportsNoReplaceMove(custom, root)).isFalse();
        assertThat(support.moveNoReplace(custom, root.resolve("target"))).isEqualTo(FileSystemOperations.MoveResult.UNSUPPORTED);
        assertThat(calls.get()).isZero();
    }

    @Test
    void aRegularFileUsesTheSameNoReplacePrimitiveWithoutCopying() throws Exception {
        NativeFileSystemOperations support = new NativeFileSystemOperations();
        Path source = Files.writeString(root.resolve("source.txt"), "content");
        Path target = root.resolve("target.txt");
        String originalIdentity = fileKey(source);
        requireSupported(support, source, root);

        FileSystemOperations.MoveResult result = support.moveNoReplace(source, target);

        requireMoved(result);
        assertThat(source).doesNotExist();
        assertThat(Files.readString(target)).isEqualTo("content");
        assertThat(fileKey(target)).isEqualTo(originalIdentity);
    }

    @ParameterizedTest
    @ValueSource(strings = {"file", "directory"})
    void aRegularFileCannotReplaceACompetingTarget(String kind) throws Exception {
        NativeFileSystemOperations support = new NativeFileSystemOperations();
        Path source = Files.writeString(root.resolve("source.txt"), "original");
        Path target = root.resolve("target");
        if ("file".equals(kind)) {
            Files.writeString(target, "competitor");
        } else {
            Files.createDirectory(target);
        }
        requireSupported(support, source, root);
        String originalIdentity = fileKey(source);
        String competingIdentity = fileKey(target);

        assertThatThrownBy(() -> support.moveNoReplace(source, target)).isInstanceOf(FileAlreadyExistsException.class);

        assertThat(fileKey(source)).isEqualTo(originalIdentity);
        assertThat(fileKey(target)).isEqualTo(competingIdentity);
        assertThat(Files.readString(source)).isEqualTo("original");
    }

    @Test
    void linuxMountRootsAreExcludedWhenTheSourceParentBelongsToAnotherStore() {
        FileStore mounted = mock(FileStore.class);
        FileStore parent = mock(FileStore.class);

        assertThat(NativeFileSystemOperations.sourceAndParentStoresMatch(OperatingSystem.LINUX, mounted, parent)).isFalse();
        assertThat(NativeFileSystemOperations.sourceAndParentStoresMatch(OperatingSystem.LINUX, mounted, mounted)).isTrue();
        assertThat(NativeFileSystemOperations.sourceAndParentStoresMatch(OperatingSystem.WINDOWS, mounted, parent)).isTrue();
    }

    @Test
    void unavailableVolumeNamesPermitTheLegacyStoreComparisonButExcludeNativeMutation() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        NativeFileSystemOperations support = new NativeFileSystemOperations(OperatingSystem.current(),
                new NativeFileSystemOperations.NativeAccess() {
                    @Override
                    public boolean sameFileSystem(Path source, Path parent, FileStore sourceStore, FileStore targetStore) {
                        return Boolean.TRUE.equals(querySameFileSystem(source, parent, sourceStore, targetStore));
                    }

                    @Override
                    public Boolean querySameFileSystem(Path source, Path parent, FileStore sourceStore, FileStore targetStore) {
                        return null;
                    }

                    @Override
                    public NativeResult move(Path source, Path target) {
                        calls.incrementAndGet();
                        return new NativeResult(true, 0);
                    }
                });
        Path source = Files.createDirectory(root.resolve("source"));

        assertThat(support.sameFileSystem(source, root)).isTrue();
        assertThat(support.supportsNoReplaceMove(source, root)).isFalse();
        assertThat(support.moveNoReplace(source, root.resolve("target"))).isEqualTo(FileSystemOperations.MoveResult.UNSUPPORTED);
        assertThat(calls.get()).isZero();
        assertThat(source).isDirectory();
        assertThat(root.resolve("target")).doesNotExist();
    }

    @Test
    void twoKnownDifferentNativeVolumesRemainAuthoritativeEvenWhenNioStoresCompareEqual() throws Exception {
        NativeFileSystemOperations support = new NativeFileSystemOperations(OperatingSystem.current(),
                new NativeFileSystemOperations.NativeAccess() {
                    @Override
                    public boolean sameFileSystem(Path source, Path parent, FileStore sourceStore, FileStore targetStore) {
                        return false;
                    }

                    @Override
                    public NativeResult move(Path source, Path target) {
                        throw new AssertionError("Different native volumes cannot be moved.");
                    }
                });
        Path source = Files.createDirectory(root.resolve("source"));

        assertThat(Files.getFileStore(source)).isEqualTo(Files.getFileStore(root));
        assertThat(support.sameFileSystem(source, root)).isFalse();
        assertThat(support.supportsNoReplaceMove(source, root)).isFalse();
    }

    @Test
    void unavailableSourceIsAValidationFailure() {
        NativeFileSystemOperations support = new NativeFileSystemOperations(OperatingSystem.current(),
                (source, target) -> new NativeResult(true, 0));

        assertThatThrownBy(() -> support.supportsNoReplaceMove(root.resolve("missing"), root))
                .isInstanceOf(NoSuchFileException.class);
    }

    @Test
    void remoteLocationsAreExcludedBeforeNativeInvocation() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        NativeFileSystemOperations support = new NativeFileSystemOperations(OperatingSystem.current(),
                new NativeFileSystemOperations.NativeAccess() {
                    @Override
                    public boolean local(Path path) {
                        return false;
                    }

                    @Override
                    public NativeResult move(Path source, Path target) {
                        calls.incrementAndGet();
                        return new NativeResult(true, 0);
                    }
                });
        Path source = Files.createDirectory(root.resolve("source"));

        assertThat(support.supportsNoReplaceMove(source, root)).isFalse();
        assertThat(support.moveNoReplace(source, root.resolve("target"))).isEqualTo(FileSystemOperations.MoveResult.UNSUPPORTED);
        assertThat(calls.get()).isZero();
        assertThat(source).isDirectory();
    }

    @Test
    void aDifferentNativeVolumeCannotUseTheDirectoryStrategy() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        NativeFileSystemOperations support = new NativeFileSystemOperations(OperatingSystem.current(),
                new NativeFileSystemOperations.NativeAccess() {
                    @Override
                    public boolean sameFileSystem(Path source, Path parent, FileStore sourceStore, FileStore targetStore) {
                        return false;
                    }

                    @Override
                    public NativeResult move(Path source, Path target) {
                        calls.incrementAndGet();
                        return new NativeResult(true, 0);
                    }
                });
        Path source = Files.createDirectory(root.resolve("source"));

        assertThat(support.supportsNoReplaceMove(source, root)).isFalse();
        assertThat(support.moveNoReplace(source, root.resolve("target"))).isEqualTo(FileSystemOperations.MoveResult.UNSUPPORTED);
        assertThat(calls.get()).isZero();
        assertThat(source).isDirectory();
    }

    @Test
    void nativeInvocationExceptionsAreUncertainFailuresInsteadOfFallback() throws Exception {
        NativeFileSystemOperations support = new NativeFileSystemOperations(OperatingSystem.current(), (source, target) -> {
            throw new IllegalStateException("simulated native invocation failure");
        });
        Path source = Files.createDirectory(root.resolve("source"));
        requireSupported(support, source, root);

        assertThatThrownBy(() -> support.moveNoReplace(source, root.resolve("target")))
                .isInstanceOf(IOException.class).hasCauseInstanceOf(IllegalStateException.class)
                .hasMessageContaining("outcome could not be determined");
        assertThat(source).isDirectory();
        assertThat(root.resolve("target")).doesNotExist();
    }

    @Test
    void movingIntoTheSourceIsRejectedWithoutNativeInvocation() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        NativeFileSystemOperations support = new NativeFileSystemOperations(OperatingSystem.current(), (source, target) -> {
            calls.incrementAndGet();
            return new NativeResult(true, 0);
        });
        Path source = Files.createDirectory(root.resolve("source"));

        assertThatThrownBy(() -> support.moveNoReplace(source, source))
                .isInstanceOf(IOException.class).hasMessageContaining("itself");
        assertThatThrownBy(() -> support.moveNoReplace(source, source.resolve("nested")))
                .isInstanceOf(IOException.class).hasMessageContaining("itself");
        assertThat(calls.get()).isZero();
    }

    @Test
    void onlyTheInitialLocalFilesystemAllowlistIsEnabled() {
        assertThat(NativeFileSystemOperations.allowedFileSystem(OperatingSystem.WINDOWS, "NTFS")).isTrue();
        for (String type : new String[] {"ReFS", "FAT32", "CIFS", "unknown"}) {
            assertThat(NativeFileSystemOperations.allowedFileSystem(OperatingSystem.WINDOWS, type)).isFalse();
        }
        for (String type : new String[] {"ext2", "ext3", "ext4", "xfs", "btrfs", "tmpfs"}) {
            assertThat(NativeFileSystemOperations.allowedFileSystem(OperatingSystem.LINUX, type)).isTrue();
        }
        for (String type : new String[] {"overlay", "nfs", "nfs4", "cifs", "fuse", "fuse.sshfs", "unknown"}) {
            assertThat(NativeFileSystemOperations.allowedFileSystem(OperatingSystem.LINUX, type)).isFalse();
        }
        assertThat(NativeFileSystemOperations.allowedFileSystem(OperatingSystem.OTHER, "ext4")).isFalse();
    }

    @ParameterizedTest
    @ValueSource(ints = {22, 38, 95, 18})
    void linuxUnsupportedErrorsExplicitlyAllowRevalidatedFallback(int error) throws Exception {
        assertThat(NativeFileSystemOperations.interpretResult(OperatingSystem.LINUX, new NativeResult(false, error),
                root.resolve("source"), root.resolve("target"))).isEqualTo(FileSystemOperations.MoveResult.UNSUPPORTED);
    }

    @ParameterizedTest
    @ValueSource(ints = {1, 17, 50, 120})
    void windowsUnsupportedErrorsExplicitlyAllowRevalidatedFallback(int error) throws Exception {
        assertThat(NativeFileSystemOperations.interpretResult(OperatingSystem.WINDOWS, new NativeResult(false, error),
                root.resolve("source"), root.resolve("target"))).isEqualTo(FileSystemOperations.MoveResult.UNSUPPORTED);
    }

    @Test
    void targetCollisionsAreNeverConvertedToFallback() {
        for (int error : new int[] {80, 183}) {
            assertThatThrownBy(() -> NativeFileSystemOperations.interpretResult(OperatingSystem.WINDOWS,
                    new NativeResult(false, error), root.resolve("source"), root.resolve("target")))
                    .isInstanceOf(FileAlreadyExistsException.class);
        }
        assertThatThrownBy(() -> NativeFileSystemOperations.interpretResult(OperatingSystem.LINUX,
                new NativeResult(false, 17), root.resolve("source"), root.resolve("target")))
                .isInstanceOf(FileAlreadyExistsException.class);
    }

    @Test
    void windowsDirectoryToFileCollisionIsStillReportedWithoutFallback() throws Exception {
        Path target = Files.writeString(root.resolve("target.txt"), "competing content");

        assertThatThrownBy(() -> NativeFileSystemOperations.interpretResult(OperatingSystem.WINDOWS,
                new NativeResult(false, 5), root.resolve("source"), target))
                .isInstanceOf(FileAlreadyExistsException.class);
        assertThat(Files.readString(target)).isEqualTo("competing content");
    }

    @Test
    void permissionBusyAndUncertainIoErrorsAreNeverConvertedToFallback() {
        for (int error : new int[] {0, 2, 5, 13, 16, 28}) {
            assertThatThrownBy(() -> NativeFileSystemOperations.interpretResult(OperatingSystem.LINUX,
                    new NativeResult(false, error), root.resolve("source"), root.resolve("target")))
                    .isInstanceOf(IOException.class).isNotInstanceOf(FileAlreadyExistsException.class);
        }
        for (int error : new int[] {0, 2, 5, 32, 112}) {
            assertThatThrownBy(() -> NativeFileSystemOperations.interpretResult(OperatingSystem.WINDOWS,
                    new NativeResult(false, error), root.resolve("source"), root.resolve("target")))
                    .isInstanceOf(IOException.class).isNotInstanceOf(FileAlreadyExistsException.class);
        }
    }

    @Test
    void nativeSuccessUsesTheReturnValueInsteadOfAStaleLastError() throws Exception {
        for (OperatingSystem platform : new OperatingSystem[] {OperatingSystem.LINUX, OperatingSystem.WINDOWS}) {
            assertThat(NativeFileSystemOperations.interpretResult(platform, new NativeResult(true, 5),
                    root.resolve("source"), root.resolve("target"))).isEqualTo(FileSystemOperations.MoveResult.MOVED);
        }
    }

    @Test
    void windowsExtendedPathsPreserveUnicodeAndUncRoots() {
        assertThat(NativeFileSystemOperations.windowsPath("C:\\한글 폴더\\name + %.txt"))
                .isEqualTo("\\\\?\\C:\\한글 폴더\\name + %.txt");
        assertThat(NativeFileSystemOperations.windowsPath("\\\\server\\share\\한글 폴더"))
                .isEqualTo("\\\\?\\UNC\\server\\share\\한글 폴더");
        assertThat(NativeFileSystemOperations.windowsPath("\\\\?\\C:\\already-prefixed"))
                .isEqualTo("\\\\?\\C:\\already-prefixed");
    }

    @Test
    void stableEvidenceUsesTheNioKeyOrWindowsHandleIdentity() throws Exception {
        Path path = Files.writeString(root.resolve("file.txt"), "content");
        Object nioKey = Files.readAttributes(path, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS).fileKey();
        String stableKey = FileSystemOperations.systemDefault().readIdentity(path);
        if (nioKey != null) {
            assertThat(stableKey).isEqualTo(nioKey.toString());
        } else if (windowsNtfs()) {
            assertThat(stableKey).matches("windows:[0-9a-f]{8}:[0-9a-f]{16}");
        }
        assertThat(FileSystemOperations.systemDefault().readIdentity(path)).isEqualTo(stableKey);
        if (nioKey != null || windowsNtfs()) {
            Path different = Files.writeString(root.resolve("different.txt"), "content");
            assertThat(FileSystemOperations.systemDefault().readIdentity(different)).isNotEqualTo(stableKey);
        }
    }

    @Test
    void stableEvidenceDoesNotFollowAWindowsReparsePoint() {
        assertThatThrownBy(() -> NativeFileSystemOperations.windowsFileKey(0x400, 1, 0, 2, root.resolve("junction")))
                .isInstanceOf(IOException.class).hasMessageContaining("reparse point");
    }

    @Test
    void stableEvidenceRejectsAnUnavailablePath() {
        assertThatThrownBy(() -> FileSystemOperations.systemDefault().readIdentity(root.resolve("missing")))
                .isInstanceOf(NoSuchFileException.class);
    }

    private String fileKey(Path path) throws IOException {
        return FileSystemOperations.systemDefault().readIdentity(path);
    }

    private void requireSupported(NativeFileSystemOperations support, Path source, Path parent) throws IOException {
        boolean supported = support.supportsNoReplaceMove(source, parent);
        if (windowsNtfs()) {
            // A local NTFS test must expose a broken Windows binding instead of silently skipping it.
            assertThat(supported).as("Windows NTFS native rename support (source store %s; parent store %s;"
                            + " source identity %s; parent identity %s)", Files.getFileStore(source), Files.getFileStore(parent),
                    FileSystemOperations.systemDefault().readIdentity(source), FileSystemOperations.systemDefault().readIdentity(parent)).isTrue();
        } else {
            assumeTrue(supported, "Local filesystem has no supported native rename");
        }
    }

    private void requireMoved(FileSystemOperations.MoveResult moved) throws IOException {
        if (windowsNtfs()) {
            assertThat(moved).as("Windows NTFS native rename result").isEqualTo(FileSystemOperations.MoveResult.MOVED);
        } else {
            assumeTrue(moved == FileSystemOperations.MoveResult.MOVED, "Filesystem rejected the native rename flag without changing the tree");
        }
    }

    private boolean windowsNtfs() throws IOException {
        return OperatingSystem.current() == OperatingSystem.WINDOWS
                && "NTFS".equalsIgnoreCase(Files.getFileStore(root).type());
    }
}
