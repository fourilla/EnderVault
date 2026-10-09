package io.github.fourilla.endervault.filesystem;

import java.util.function.Supplier;

final class FileSystemOperationsProvider {

    static final String NATIVE_ENABLED_PROPERTY = "endervault.filesystem.native-enabled";

    private FileSystemOperationsProvider() {
    }

    static FileSystemOperations systemDefault() {
        return Holder.INSTANCE;
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
        return Boolean.parseBoolean(System.getProperty(NATIVE_ENABLED_PROPERTY, "true"));
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
