package io.github.fourilla.endervault.filesystem;

import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;
import org.springframework.stereotype.Service;

/** Keeps optional native loading and platform-specific failures away from domain services. */
@Service
public final class DefaultFileSystemOperations implements FileSystemOperations {

    private final NativeFileSystem nativeFileSystem;
    private final NioFileSystem nio;

    public DefaultFileSystemOperations() {
        this(FileSystemOperationsProvider.nativeBackend(FileSystemOperationsProvider.nativeEnabled(),
                () -> NativeFileSystemOperations.create()), new NioFileSystem());
    }

    DefaultFileSystemOperations(NativeFileSystem nativeFileSystem, NioFileSystem nio) {
        this.nativeFileSystem = nativeFileSystem;
        this.nio = nio;
    }

    @Override
    public boolean supportsNoReplaceMove(Path source, Path targetParent) throws IOException {
        if (nativeFileSystem == null) {
            return false;
        }
        try {
            return nativeFileSystem.supportsNoReplaceMove(source, targetParent);
        } catch (RuntimeException | Error ex) {
            FileSystemOperationsProvider.rethrowFatal(ex);
            return false;
        }
    }

    @Override
    public MoveResult moveNoReplace(Path source, Path target) throws IOException {
        if (nativeFileSystem == null) {
            return MoveResult.UNSUPPORTED;
        }
        // Preflight failures cannot have changed the filesystem and may select the old pipeline.
        Path parent = target.toAbsolutePath().normalize().getParent();
        if (parent == null || !supportsNoReplaceMove(source, parent)) {
            return MoveResult.UNSUPPORTED;
        }
        try {
            return nativeFileSystem.moveNoReplace(source, target);
        } catch (RuntimeException | Error ex) {
            FileSystemOperationsProvider.rethrowFatal(ex);
            // Invocation may already have changed the filesystem. Never advertise fallback here.
            throw new IOException("Native filesystem move outcome could not be determined.", ex);
        }
    }

    @Override
    public String readIdentity(Path path) throws IOException {
        BasicFileAttributes attributes = Files.readAttributes(path, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
        if (attributes.fileKey() != null) {
            return attributes.fileKey().toString();
        }
        if (nativeFileSystem == null) {
            return null;
        }
        try {
            return nativeFileSystem.readIdentity(path);
        } catch (RuntimeException | Error ex) {
            FileSystemOperationsProvider.rethrowFatal(ex);
            // Optional identity is unavailable; NIO attribute/permission IO failures still propagate.
            return null;
        }
    }

    @Override
    public boolean sameFileSystem(Path source, Path targetParent) throws IOException {
        if (source.getFileSystem() != targetParent.getFileSystem()) {
            return false;
        }
        if (nativeFileSystem != null) {
            try {
                return nativeFileSystem.sameFileSystem(source, targetParent);
            } catch (RuntimeException | Error ex) {
                FileSystemOperationsProvider.rethrowFatal(ex);
                // This compatibility query must remain usable by the legacy NIO pipeline.
                // The separate no-replace capability gate still requires native certainty.
            }
        }
        return Files.getFileStore(source).equals(Files.getFileStore(targetParent));
    }

    @Override
    public void replace(Path source, Path target, Atomicity atomicity) throws IOException {
        try {
            nio.move(source, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException ex) {
            if (atomicity == Atomicity.REQUIRED) {
                throw ex;
            }
            nio.move(source, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    @Override
    public void moveAtomically(Path source, Path target) throws IOException {
        nio.move(source, target, StandardCopyOption.ATOMIC_MOVE);
    }

    @Override
    public void moveWithAtomicPreference(Path source, Path target) throws IOException {
        try {
            moveAtomically(source, target);
        } catch (AtomicMoveNotSupportedException ex) {
            nio.move(source, target);
        }
    }

    @Override
    public void syncFile(Path path) throws IOException {
        nio.syncFile(path);
    }

    @Override
    public SyncResult syncDirectory(Path path) throws IOException {
        return nio.syncDirectory(path);
    }
}
