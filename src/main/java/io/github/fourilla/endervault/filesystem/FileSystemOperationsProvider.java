package io.github.fourilla.endervault.filesystem;

import java.nio.file.Path;
import java.util.Objects;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;

final class FileSystemOperationsProvider {

    private static final ThreadLocal<FileSystemOperation.Scope> ACTIVE = new ThreadLocal<>();
    private static volatile BooleanSupplier configuredPolicy = () -> true;

    private FileSystemOperationsProvider() {
    }

    static FileSystemOperations systemDefault() {
        return Holder.INSTANCE;
    }

    static void configureNativePolicy(BooleanSupplier nativeEnabled) {
        configuredPolicy = Objects.requireNonNull(nativeEnabled, "nativeEnabled");
    }

    static FileSystemOperation captureOperation() {
        FileSystemOperation current = currentOperation();
        return new FileSystemOperation(current == null ? nativeEnabled() : current.nativeEnabled());
    }

    static FileSystemOperation operation(boolean enabled) {
        return new FileSystemOperation(enabled);
    }

    static FileSystemOperation operationOrCapture() {
        FileSystemOperation current = currentOperation();
        return current == null ? captureOperation() : current;
    }

    static FileSystemOperation currentOperation() {
        FileSystemOperation.Scope current = ACTIVE.get();
        return current == null ? null : current.operation;
    }

    static FileSystemOperation.Scope activate(FileSystemOperation operation, boolean publication) {
        FileSystemOperation.Scope previous = ACTIVE.get();
        FileSystemOperation.Scope scope = new FileSystemOperation.Scope(operation, previous, publication);
        ACTIVE.set(scope);
        return scope;
    }

    static FileSystemOperation.Scope publication() {
        return activate(operationOrCapture(), true);
    }

    static FileSystemOperation publicationOperation() {
        FileSystemOperation.Scope current = ACTIVE.get();
        return current != null && current.publication ? current.operation : null;
    }

    static void close(FileSystemOperation.Scope scope) {
        if (ACTIVE.get() != scope) {
            throw new IllegalStateException("Filesystem operation scopes must close in reverse activation order.");
        }
        if (scope.previous == null) {
            ACTIVE.remove();
        } else {
            ACTIVE.set(scope.previous);
        }
    }

    static void recordJavaPublication(Path source, Path target, String method) {
        FileSystemOperation operation = publicationOperation();
        if (operation != null) {
            operation.attempted(false, source, target, method);
            operation.succeeded(false, target);
        }
    }

    static void recordRecoveredPublication(Path target) {
        FileSystemOperation operation = publicationOperation();
        if (operation != null) {
            operation.recovered(target);
        }
    }

    static NativeFileSystem nativeBackend(boolean enabled, Supplier<NativeFileSystem> loader) {
        if (!enabled) {
            return null;
        }
        try {
            return loader.get();
        } catch (RuntimeException | Error ex) {
            rethrowFatal(ex);
            // No operation has been attempted: missing/blocked/incompatible bindings are optional.
            return null;
        }
    }

    static boolean nativeEnabled() {
        FileSystemOperation operation = currentOperation();
        return operation == null ? configuredPolicy.getAsBoolean()
                : operation.nativeEnabled();
    }

    static void rethrowFatal(Throwable failure) {
        if (failure instanceof VirtualMachineError error) {
            throw error;
        }
        if (failure instanceof ThreadDeath death) {
            throw death;
        }
    }

    private static final class Holder {
        private static final FileSystemOperations INSTANCE = new DefaultFileSystemOperations();
    }
}
