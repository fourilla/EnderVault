package io.github.fourilla.endervault.filesystem;

import com.sun.jna.Library;
import com.sun.jna.Memory;
import com.sun.jna.Native;
import com.sun.jna.NativeLibrary;
import com.sun.jna.Platform;
import com.sun.jna.Pointer;
import com.sun.jna.WString;
import com.sun.jna.win32.StdCallLibrary;
import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.FileStore;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
/** A no-replace rename primitive; its caller owns validation, durable intent, and recovery. */
final class NativeFileSystemOperations implements NativeFileSystem {

    private static final Set<String> LINUX_FILE_SYSTEMS = Set.of("ext2", "ext3", "ext4", "xfs", "btrfs", "tmpfs");
    private static final int MOVEFILE_WRITE_THROUGH = 8;
    private static final int AT_FDCWD = -100;
    private static final int RENAME_NOREPLACE = 1;
    private static final int ERROR_INVALID_FUNCTION = 1;
    private static final int ERROR_ACCESS_DENIED = 5;
    private static final int ERROR_NOT_SAME_DEVICE = 17;
    private static final int ERROR_NOT_SUPPORTED = 50;
    private static final int ERROR_FILE_EXISTS = 80;
    private static final int ERROR_CALL_NOT_IMPLEMENTED = 120;
    private static final int ERROR_ALREADY_EXISTS = 183;
    private static final int FILE_READ_ATTRIBUTES = 0x80;
    private static final int FILE_SHARE_READ_WRITE_DELETE = 7;
    private static final int OPEN_EXISTING = 3;
    private static final int FILE_FLAG_BACKUP_SEMANTICS = 0x02000000;
    private static final int FILE_FLAG_OPEN_REPARSE_POINT = 0x00200000;
    private static final int FILE_ATTRIBUTE_REPARSE_POINT = 0x400;
    private static final int FILE_INFORMATION_SIZE = 52;
    private static final int FILE_INFORMATION_ATTRIBUTES = 0;
    private static final int FILE_INFORMATION_VOLUME_SERIAL = 28;
    private static final int FILE_INFORMATION_INDEX_HIGH = 44;
    private static final int FILE_INFORMATION_INDEX_LOW = 48;
    private static final int EEXIST = 17;
    private static final int EXDEV = 18;
    private static final int EINVAL = 22;
    private static final int ENOSYS = 38;
    private static final int EOPNOTSUPP = 95;

    private final OperatingSystem operatingSystem;
    private final NativeAccess nativeAccess;

    NativeFileSystemOperations() {
        this(OperatingSystem.current(), loadNative(OperatingSystem.current()));
    }

    static NativeFileSystem create() {
        OperatingSystem operatingSystem = OperatingSystem.current();
        if (operatingSystem == OperatingSystem.OTHER) {
            return null;
        }
        NativeAccess access = loadNative(operatingSystem);
        return access == null ? null : new NativeFileSystemOperations(operatingSystem, access);
    }

    NativeFileSystemOperations(OperatingSystem operatingSystem, NativeAccess nativeAccess) {
        this.operatingSystem = operatingSystem;
        this.nativeAccess = nativeAccess;
    }

    public boolean supportsNoReplaceMove(Path source, Path targetParent) throws IOException {
        if (nativeAccess == null || !defaultPath(source) || !defaultPath(targetParent)) {
            return false;
        }
        Path absoluteSource = source.toAbsolutePath().normalize();
        Path absoluteParent = targetParent.toAbsolutePath().normalize();
        Path sourceParent = absoluteSource.getParent();
        if (sourceParent == null || !identifiedEntry(absoluteSource)
                || !identifiedDirectory(sourceParent) || !identifiedDirectory(absoluteParent)) {
            return false;
        }
        FileStore sourceStore = Files.getFileStore(absoluteSource);
        FileStore targetStore = Files.getFileStore(absoluteParent);
        if (!allowedFileSystem(operatingSystem, sourceStore.type())
                || !allowedFileSystem(operatingSystem, targetStore.type())
                || !nativeAccess.sameFileSystem(absoluteSource, absoluteParent, sourceStore, targetStore)) {
            return false;
        }
        // A mounted root and its parent may resolve to different stores even when the target
        // belongs to the mounted store. Such a rename is rejected before invoking renameat2.
        if (!sourceAndParentStoresMatch(operatingSystem, sourceStore, Files.getFileStore(sourceParent))) {
            return false;
        }
        if (operatingSystem == OperatingSystem.LINUX
                && (!utf8FileNames() || !roundTrips(absoluteSource) || !roundTrips(absoluteParent))) {
            return false;
        }
        return nativeAccess.local(absoluteSource) && nativeAccess.local(absoluteParent);
    }

    /**
     * UNSUPPORTED means no rename was attempted or the native API explicitly rejected unsupported use.
     * The caller must revalidate the original tree and absent target before selecting copying.
     * An IOException after invocation is never a request to retry using another strategy.
     */
    public FileSystemOperations.MoveResult moveNoReplace(Path source, Path target) throws IOException {
        if (!defaultPath(source) || !defaultPath(target)) {
            return FileSystemOperations.MoveResult.UNSUPPORTED;
        }
        Path absoluteSource = source.toAbsolutePath().normalize();
        Path absoluteTarget = target.toAbsolutePath().normalize();
        if (absoluteTarget.equals(absoluteSource) || absoluteTarget.startsWith(absoluteSource)) {
            throw new IOException("A filesystem entry cannot be moved into itself.");
        }
        Path targetParent = absoluteTarget.getParent();
        if (targetParent == null || !supportsNoReplaceMove(absoluteSource, targetParent)
                || (operatingSystem == OperatingSystem.LINUX && !roundTrips(absoluteTarget))) {
            return FileSystemOperations.MoveResult.UNSUPPORTED;
        }
        NativeResult result;
        try {
            result = nativeAccess.move(absoluteSource, absoluteTarget);
        } catch (RuntimeException | Error ex) {
            FileSystemOperationsProvider.rethrowFatal(ex);
            throw new IOException("Native filesystem move outcome could not be determined.", ex);
        }
        return interpretResult(operatingSystem, result, absoluteSource, absoluteTarget);
    }

    static boolean allowedFileSystem(OperatingSystem operatingSystem, String type) {
        String normalized = type.toLowerCase(Locale.ROOT);
        return switch (operatingSystem) {
            case WINDOWS -> "ntfs".equals(normalized);
            case LINUX -> LINUX_FILE_SYSTEMS.contains(normalized);
            case OTHER -> false;
        };
    }

    static boolean sourceAndParentStoresMatch(OperatingSystem system, FileStore source, FileStore parent) {
        return system != OperatingSystem.LINUX || source.equals(parent);
    }

    static FileSystemOperations.MoveResult interpretResult(OperatingSystem operatingSystem, NativeResult result, Path source, Path target)
            throws IOException {
        if (result.moved()) {
            return FileSystemOperations.MoveResult.MOVED;
        }
        int error = result.error();
        if ((operatingSystem == OperatingSystem.WINDOWS
                && (error == ERROR_FILE_EXISTS || error == ERROR_ALREADY_EXISTS
                // Windows may report access denied when a directory would replace a regular file.
                || (error == ERROR_ACCESS_DENIED && Files.exists(target, LinkOption.NOFOLLOW_LINKS))))
                || (operatingSystem == OperatingSystem.LINUX && error == EEXIST)) {
            throw new FileAlreadyExistsException(target.toString());
        }
        // These API failures reject the operation; they do not authorize fallback after other errors.
        if ((operatingSystem == OperatingSystem.WINDOWS && (error == ERROR_INVALID_FUNCTION
                || error == ERROR_NOT_SAME_DEVICE || error == ERROR_NOT_SUPPORTED || error == ERROR_CALL_NOT_IMPLEMENTED))
                || (operatingSystem == OperatingSystem.LINUX
                && (error == EINVAL || error == ENOSYS || error == EOPNOTSUPP || error == EXDEV))) {
            return FileSystemOperations.MoveResult.UNSUPPORTED;
        }
        throw new IOException("Native filesystem move failed (" + operatingSystem + ", error " + error
                + "): " + source + " -> " + target);
    }

    static String windowsPath(String absolutePath) {
        if (absolutePath.startsWith("\\\\?\\")) {
            return absolutePath;
        }
        return absolutePath.startsWith("\\\\")
                ? "\\\\?\\UNC\\" + absolutePath.substring(2)
                : "\\\\?\\" + absolutePath;
    }

    private static boolean defaultPath(Path path) {
        return path != null && path.getFileSystem() == FileSystems.getDefault();
    }

    private boolean identifiedDirectory(Path path) throws IOException {
        BasicFileAttributes attributes = Files.readAttributes(path, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
        return attributes.isDirectory() && readIdentity(path) != null;
    }

    private boolean identifiedEntry(Path path) throws IOException {
        BasicFileAttributes attributes = Files.readAttributes(path, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
        return (attributes.isDirectory() || attributes.isRegularFile()) && readIdentity(path) != null;
    }

    @Override
    public boolean sameFileSystem(Path source, Path targetParent) throws IOException {
        if (source.getFileSystem() != targetParent.getFileSystem()) {
            return false;
        }
        FileStore sourceStore = Files.getFileStore(source);
        FileStore targetStore = Files.getFileStore(targetParent);
        if (!defaultPath(source) || !defaultPath(targetParent) || nativeAccess == null) {
            return sourceStore.equals(targetStore);
        }
        Boolean nativeResult = nativeAccess.querySameFileSystem(source.toAbsolutePath().normalize(),
                targetParent.toAbsolutePath().normalize(), sourceStore, targetStore);
        return nativeResult != null ? nativeResult : sourceStore.equals(targetStore);
    }

    /** Stable rename evidence, including NTFS providers whose basic attributes omit fileKey. */
    public String readIdentity(Path path) throws IOException {
        BasicFileAttributes attributes = Files.readAttributes(path, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
        if (attributes.fileKey() != null) {
            return attributes.fileKey().toString();
        }
        if (OperatingSystem.current() != OperatingSystem.WINDOWS || !defaultPath(path)
                || !"NTFS".equalsIgnoreCase(Files.getFileStore(path).type())) {
            return null;
        }
        WindowsIdentityCalls calls = WindowsIdentityHolder.CALLS;
        if (calls == null) {
            return null;
        }
        Path absolute = path.toAbsolutePath().normalize();
        Pointer handle = calls.CreateFileW(new WString(windowsPath(absolute.toString())), FILE_READ_ATTRIBUTES,
                FILE_SHARE_READ_WRITE_DELETE, null, OPEN_EXISTING,
                FILE_FLAG_BACKUP_SEMANTICS | FILE_FLAG_OPEN_REPARSE_POINT, null);
        int openError = Native.getLastError();
        long address = Pointer.nativeValue(handle);
        if (handle == null || address == -1L || (Native.POINTER_SIZE == 4 && address == 0xffffffffL)) {
            return identityFailure(openError, absolute);
        }
        try (Memory information = new Memory(FILE_INFORMATION_SIZE)) {
            // BY_HANDLE_FILE_INFORMATION consists of 13 DWORDs on both Windows architectures.
            int result = calls.GetFileInformationByHandle(handle, information);
            int error = Native.getLastError();
            if (result == 0) {
                return identityFailure(error, absolute);
            }
            // Volume serial plus the high/low file index uniquely identifies a live NTFS entry.
            return windowsFileKey(information.getInt(FILE_INFORMATION_ATTRIBUTES),
                    information.getInt(FILE_INFORMATION_VOLUME_SERIAL), information.getInt(FILE_INFORMATION_INDEX_HIGH),
                    information.getInt(FILE_INFORMATION_INDEX_LOW), absolute);
        } finally {
            calls.CloseHandle(handle);
        }
    }

    static String windowsFileKey(int attributes, int volumeSerial, int indexHigh, int indexLow, Path path)
            throws IOException {
        if ((attributes & FILE_ATTRIBUTE_REPARSE_POINT) != 0) {
            throw new IOException("Filesystem identity cannot use a reparse point: " + path);
        }
        return String.format(Locale.ROOT, "windows:%08x:%08x%08x", volumeSerial, indexHigh, indexLow);
    }

    private static String identityFailure(int error, Path path) throws IOException {
        if (error == ERROR_INVALID_FUNCTION || error == ERROR_NOT_SUPPORTED || error == ERROR_CALL_NOT_IMPLEMENTED) {
            return null;
        }
        if (error == 2 || error == 3) {
            throw new NoSuchFileException(path.toString());
        }
        throw new IOException("Could not read Windows filesystem identity (error " + error + "): " + path);
    }

    private static WindowsIdentityCalls loadWindowsIdentity() {
        if (OperatingSystem.current() != OperatingSystem.WINDOWS) {
            return null;
        }
        try {
            NativeLibrary library = NativeLibrary.getInstance("kernel32");
            library.getFunction("CreateFileW");
            library.getFunction("GetFileInformationByHandle");
            library.getFunction("CloseHandle");
            return Native.load("kernel32", WindowsIdentityCalls.class);
        } catch (RuntimeException | Error ex) {
            FileSystemOperationsProvider.rethrowFatal(ex);
            return null;
        }
    }

    private static class WindowsIdentityHolder {
        private static final WindowsIdentityCalls CALLS = loadWindowsIdentity();
    }

    private static boolean roundTrips(Path path) {
        return path.getFileSystem().getPath(path.toString()).equals(path);
    }

    private static boolean utf8FileNames() {
        String encoding = System.getProperty("sun.jnu.encoding");
        return encoding != null && Charset.isSupported(encoding)
                && StandardCharsets.UTF_8.equals(Charset.forName(encoding));
    }

    private static NativeAccess loadNative(OperatingSystem operatingSystem) {
        try {
            return switch (operatingSystem) {
                case WINDOWS -> {
                    NativeLibrary library = NativeLibrary.getInstance("kernel32");
                    library.getFunction("MoveFileExW");
                    library.getFunction("GetVolumePathNameW");
                    library.getFunction("GetVolumeNameForVolumeMountPointW");
                    library.getFunction("GetDriveTypeW");
                    WindowsCalls calls = Native.load("kernel32", WindowsCalls.class);
                    yield new NativeAccess() {
                        @Override
                        public boolean local(Path path) {
                            String volume = volumePath(path);
                            if (volume == null) {
                                return false;
                            }
                            int driveType = calls.GetDriveTypeW(new WString(volume));
                            return driveType == 2 || driveType == 3 || driveType == 6;
                        }

                        @Override
                        public boolean sameFileSystem(Path source, Path parent, FileStore sourceStore, FileStore targetStore) {
                            return Boolean.TRUE.equals(querySameFileSystem(source, parent, sourceStore, targetStore));
                        }

                        @Override
                        public Boolean querySameFileSystem(Path source, Path parent, FileStore sourceStore, FileStore targetStore) {
                            // WindowsFileStore compares mount-point spelling, including the long-path prefix.
                            // Bind to the actual volume GUID instead of weakening the same-filesystem gate.
                            String sourceVolume = volumeName(source);
                            String targetVolume = volumeName(parent);
                            return sourceVolume != null && targetVolume != null
                                    ? sourceVolume.equalsIgnoreCase(targetVolume) : null;
                        }

                        private String volumePath(Path path) {
                            char[] volume = new char[32768];
                            if (calls.GetVolumePathNameW(new WString(windowsPath(path.toString())), volume, volume.length) == 0) {
                                return null;
                            }
                            return Native.toString(volume);
                        }

                        private String volumeName(Path path) {
                            String volumePath = volumePath(path);
                            if (volumePath == null) {
                                return null;
                            }
                            char[] name = new char[50];
                            if (calls.GetVolumeNameForVolumeMountPointW(new WString(volumePath), name, name.length) == 0) {
                                return null;
                            }
                            return Native.toString(name);
                        }

                        @Override
                        public NativeResult move(Path source, Path target) {
                            int result = calls.MoveFileExW(new WString(windowsPath(source.toString())),
                                    new WString(windowsPath(target.toString())), MOVEFILE_WRITE_THROUGH);
                            int error = Native.getLastError();
                            return new NativeResult(result != 0, error);
                        }
                    };
                }
                case LINUX -> {
                    NativeLibrary.getInstance(Platform.C_LIBRARY_NAME).getFunction("renameat2");
                    LinuxCalls calls = Native.load(Platform.C_LIBRARY_NAME, LinuxCalls.class,
                            Map.of(Library.OPTION_STRING_ENCODING, StandardCharsets.UTF_8.name()));
                    yield (source, target) -> {
                        int result = calls.renameat2(AT_FDCWD, source.toString(), AT_FDCWD, target.toString(), RENAME_NOREPLACE);
                        int error = Native.getLastError();
                        return new NativeResult(result == 0, error);
                    };
                }
                case OTHER -> null;
            };
        } catch (RuntimeException | Error ex) {
            FileSystemOperationsProvider.rethrowFatal(ex);
            // Native loading or symbol discovery failed before any filesystem mutation.
            return null;
        }
    }

    enum OperatingSystem {
        WINDOWS, LINUX, OTHER;

        static OperatingSystem current() {
            String name = System.getProperty("os.name", "");
            return name.startsWith("Windows") ? WINDOWS : "Linux".equals(name) ? LINUX : OTHER;
        }
    }

    @FunctionalInterface
    interface NativeAccess {
        NativeResult move(Path source, Path target);

        default boolean local(Path path) {
            return true;
        }

        default boolean sameFileSystem(Path source, Path parent, FileStore sourceStore, FileStore targetStore) {
            return sourceStore.equals(targetStore);
        }

        /** Null is an unavailable native query, distinct from two known different volumes. */
        default Boolean querySameFileSystem(Path source, Path parent, FileStore sourceStore, FileStore targetStore) {
            return sameFileSystem(source, parent, sourceStore, targetStore);
        }
    }

    record NativeResult(boolean moved, int error) {
    }

    interface WindowsCalls extends StdCallLibrary {
        int MoveFileExW(WString source, WString target, int flags);
        int GetVolumePathNameW(WString path, char[] volumePath, int length);
        int GetVolumeNameForVolumeMountPointW(WString mountPoint, char[] volumeName, int length);
        int GetDriveTypeW(WString rootPath);
    }

    interface LinuxCalls extends Library {
        int renameat2(int sourceDirectory, String source, int targetDirectory, String target, int flags);
    }

    interface WindowsIdentityCalls extends StdCallLibrary {
        Pointer CreateFileW(WString path, int access, int sharing, Pointer security, int disposition,
                int flags, Pointer template);
        int GetFileInformationByHandle(Pointer handle, Pointer information);
        int CloseHandle(Pointer handle);
    }
}
