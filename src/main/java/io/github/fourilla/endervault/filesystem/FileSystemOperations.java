package io.github.fourilla.endervault.filesystem;

import java.io.IOException;
import java.nio.file.Path;

/** Filesystem primitives; callers retain validation, journals, and recovery ownership. */
public interface FileSystemOperations {

    enum MoveResult { MOVED, UNSUPPORTED }

    enum Atomicity { REQUIRED, PREFERRED }

    enum SyncResult { SYNCED, UNSUPPORTED }

    /** The native backend is optional and can be disabled before loading any native classes. */
    static FileSystemOperations systemDefault() {
        return FileSystemOperationsProvider.systemDefault();
    }

    boolean supportsNoReplaceMove(Path source, Path targetParent) throws IOException;

    /**
     * Performs a same-filesystem rename without replacing an existing target.
     * UNSUPPORTED means no change was made. An IOException must never trigger blind fallback.
     */
    MoveResult moveNoReplace(Path source, Path target) throws IOException;

    /** Stable identity where available; null means the provider cannot supply one. */
    String readIdentity(Path path) throws IOException;

    boolean sameFileSystem(Path source, Path targetParent) throws IOException;

    /** Intentionally replaces the target; preferred atomicity falls back only when unsupported. */
    void replace(Path source, Path target, Atomicity atomicity) throws IOException;

    /**
     * Preserves Java NIO ATOMIC_MOVE semantics. This does not guarantee no replacement;
     * callers needing that guarantee must use moveNoReplace instead.
     */
    void moveAtomically(Path source, Path target) throws IOException;

    /** Preserves the legacy atomic move followed by a plain move when atomicity is unsupported. */
    void moveWithAtomicPreference(Path source, Path target) throws IOException;

    void syncFile(Path path) throws IOException;

    /** Returns unsupported only for a provider/platform that cannot sync directories. */
    SyncResult syncDirectory(Path path) throws IOException;
}
