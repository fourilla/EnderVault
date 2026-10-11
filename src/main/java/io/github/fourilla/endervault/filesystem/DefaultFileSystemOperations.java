package io.github.fourilla.endervault.filesystem;

import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.function.Supplier;

/** Keeps optional native loading and platform-specific failures away from domain services. */
public final class DefaultFileSystemOperations implements FileSystemOperations {

    private final Supplier<NativeFileSystem> nativeLoader;
    private volatile NativeFileSystem nativeFileSystem;
    private volatile boolean nativeLoaded;
    private final NioFileSystem nio;

    public DefaultFileSystemOperations() {
        this(new NioFileSystem(), () -> NativeFileSystemOperations.create());
    }

    DefaultFileSystemOperations(NativeFileSystem nativeFileSystem, NioFileSystem nio) {
        this.nativeFileSystem = nativeFileSystem;
        this.nio = nio;
        this.nativeLoader = () -> nativeFileSystem;
        this.nativeLoaded = true;
    }

    DefaultFileSystemOperations(NioFileSystem nio, Supplier<NativeFileSystem> nativeLoader) {
        this.nio = nio;
        this.nativeLoader = nativeLoader;
    }

    private NativeFileSystem nativeBackend() {
        if (!FileSystemOperationsProvider.nativeEnabled()) {
            return null;
        }
        if (!nativeLoaded) {
            synchronized (this) {
                if (!nativeLoaded) {
                    nativeFileSystem = FileSystemOperationsProvider.nativeBackend(true, nativeLoader);
                    nativeLoaded = true;
                }
            }
        }
        return nativeFileSystem;
    }

    @Override
    public boolean supportsNoReplaceMove(Path source, Path targetParent) throws IOException {
        return supportsNoReplaceMove(nativeBackend(), source, targetParent);
    }

    private boolean supportsNoReplaceMove(NativeFileSystem backend, Path source, Path targetParent) throws IOException {
        if (backend == null) {
            return false;
        }
        try {
            return backend.supportsNoReplaceMove(source, targetParent);
        } catch (RuntimeException | Error ex) {
            FileSystemOperationsProvider.rethrowFatal(ex);
            return false;
        }
    }

    @Override
    public MoveResult moveNoReplace(Path source, Path target) throws IOException {
        FileSystemOperation operation = FileSystemOperationsProvider.publicationOperation();
        NativeFileSystem backend = nativeBackend();
        if (backend == null) {
            if (operation != null) {
                operation.fallback(FileSystemOperationsProvider.nativeEnabled() ? "native-unavailable" : "native-disabled");
            }
            return MoveResult.UNSUPPORTED;
        }
        // Preflight failures cannot have changed the filesystem and may select the old pipeline.
        Path parent = target.toAbsolutePath().normalize().getParent();
        if (parent == null || !supportsNoReplaceMove(backend, source, parent)) {
            if (operation != null) {
                operation.fallback("native-capability-unavailable");
            }
            return MoveResult.UNSUPPORTED;
        }
        if (operation != null) {
            operation.attempted(true, source, target, "no-replace-rename");
        }
        try {
            MoveResult result = backend.moveNoReplace(source, target);
            if (operation != null) {
                if (result == MoveResult.MOVED) {
                    operation.succeeded(true, target);
                } else {
                    operation.fallback("native-operation-unsupported");
                }
            }
            return result;
        } catch (IOException ex) {
            if (operation != null) {
                operation.failed(true, !(ex instanceof java.nio.file.FileAlreadyExistsException));
            }
            throw ex;
        } catch (RuntimeException | Error ex) {
            FileSystemOperationsProvider.rethrowFatal(ex);
            if (operation != null) {
                operation.failed(true, true);
            }
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
        NativeFileSystem backend = nativeBackend();
        if (backend == null) {
            return null;
        }
        try {
            return backend.readIdentity(path);
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
        NativeFileSystem backend = nativeBackend();
        if (backend != null) {
            try {
                return backend.sameFileSystem(source, targetParent);
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
        FileSystemOperation operation = FileSystemOperationsProvider.publicationOperation();
        if (operation != null) {
            operation.attempted(false, source, target, "replace");
        }
        try {
            try {
                nio.move(source, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException ex) {
                if (atomicity == Atomicity.REQUIRED) {
                    throw ex;
                }
                nio.move(source, target, StandardCopyOption.REPLACE_EXISTING);
            }
            if (operation != null) {
                operation.succeeded(false, target);
            }
        } catch (IOException ex) {
            recordFailure(operation, ex);
            throw ex;
        }
    }

    @Override
    public void moveAtomically(Path source, Path target) throws IOException {
        javaMove(source, target, false);
    }

    @Override
    public void moveWithAtomicPreference(Path source, Path target) throws IOException {
        javaMove(source, target, true);
    }

    private void javaMove(Path source, Path target, boolean preferredAtomicity) throws IOException {
        FileSystemOperation operation = FileSystemOperationsProvider.publicationOperation();
        if (operation != null) {
            operation.attempted(false, source, target, "move");
        }
        try {
            try {
                nio.move(source, target, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException ex) {
                if (!preferredAtomicity) {
                    throw ex;
                }
                nio.move(source, target);
            }
            if (operation != null) {
                operation.succeeded(false, target);
            }
        } catch (IOException ex) {
            recordFailure(operation, ex);
            throw ex;
        }
    }

    private static void recordFailure(FileSystemOperation operation, IOException failure) {
        if (operation != null) {
            operation.failed(false, !(failure instanceof AtomicMoveNotSupportedException)
                    && !(failure instanceof java.nio.file.FileAlreadyExistsException));
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
