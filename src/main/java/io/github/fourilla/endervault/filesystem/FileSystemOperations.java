package io.github.fourilla.endervault.filesystem;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Map;
import java.util.function.BooleanSupplier;

/** Filesystem primitives; callers retain validation, journals, and recovery ownership. */
public interface FileSystemOperations {

    enum MoveResult { MOVED, UNSUPPORTED }

    enum Atomicity { REQUIRED, PREFERRED }

    enum SyncResult { SYNCED, UNSUPPORTED }

    /** The native backend is optional and can be disabled before loading any native classes. */
    static FileSystemOperations systemDefault() {
        return FileSystemOperationsProvider.systemDefault();
    }

    /** Connects the application's bound, live configuration without loading a native backend. */
    static void configureNativePolicy(BooleanSupplier nativeEnabled) {
        FileSystemOperationsProvider.configureNativePolicy(nativeEnabled);
    }

    /** Captures a new operation; nested work inherits the caller's policy with separate diagnostics. */
    static FileSystemOperation captureOperation() {
        return FileSystemOperationsProvider.captureOperation();
    }

    /** Restores a recorded policy independently of the setting for newly admitted work. */
    static FileSystemOperation operation(boolean nativeEnabled) {
        return FileSystemOperationsProvider.operation(nativeEnabled);
    }

    /** Reuses a bound operation or captures the policy for a synchronous entry point. */
    static FileSystemOperation operationOrCapture() {
        return FileSystemOperationsProvider.operationOrCapture();
    }

    /** Returns only observed primary publication diagnostics for the current operation. */
    static Map<String, String> currentDiagnostics() {
        FileSystemOperation operation = FileSystemOperationsProvider.currentOperation();
        return operation == null || !operation.hasDiagnostics() ? Map.of() : operation.diagnostics();
    }

    /** Restricts diagnostics to a primary file publication, excluding journals, caches and logs. */
    static FileSystemOperation.Scope publication() {
        return FileSystemOperationsProvider.publication();
    }

    /** Records a successful Java publication performed directly by a caller, such as a hardlink. */
    static void recordJavaPublication(Path source, Path target, String method) {
        FileSystemOperationsProvider.recordJavaPublication(source, target, method);
    }

    /**
     * Records a verified prior publication whose backend cannot be observed during recovery.
     * Repeated recovery and targets published successfully in the same handle are excluded.
     */
    static void recordRecoveredPublication(Path target) {
        FileSystemOperationsProvider.recordRecoveredPublication(target);
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
