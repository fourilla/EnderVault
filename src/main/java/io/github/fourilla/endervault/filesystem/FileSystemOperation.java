package io.github.fourilla.endervault.filesystem;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/** Immutable execution policy with diagnostics belonging exclusively to one logical operation. */
public final class FileSystemOperation {

    private final boolean nativeEnabled;
    private final Diagnostics diagnostics = new Diagnostics();

    FileSystemOperation(boolean nativeEnabled) {
        this.nativeEnabled = nativeEnabled;
    }

    public boolean nativeEnabled() {
        return nativeEnabled;
    }

    /**
     * Binds this handle to the current thread until close. Executor boundaries must activate it
     * explicitly; the context is deliberately not inherited by newly created threads.
     */
    public Scope activate() {
        return FileSystemOperationsProvider.activate(this, false);
    }

    /** A neutral immutable snapshot; the business owner decides whether and where to log it. */
    public Map<String, String> diagnostics() {
        return diagnostics.snapshot(nativeEnabled);
    }

    void fallback(String reason) {
        diagnostics.fallback(reason);
    }

    void attempted(boolean nativeExecution, Path source, Path target, String method) {
        diagnostics.attempted(nativeExecution, source, target, method);
    }

    void succeeded(boolean nativeExecution, Path target) {
        diagnostics.succeeded(nativeExecution, target);
    }

    void failed(boolean nativeExecution, boolean uncertain) {
        diagnostics.failed(nativeExecution, uncertain);
    }

    void recovered(Path target) {
        diagnostics.recovered(target);
    }

    boolean hasDiagnostics() {
        return diagnostics.hasObservations();
    }

    public static final class Scope implements AutoCloseable {
        final FileSystemOperation operation;
        final Scope previous;
        final boolean publication;
        private final Thread owner = Thread.currentThread();
        private boolean closed;

        Scope(FileSystemOperation operation, Scope previous, boolean publication) {
            this.operation = operation;
            this.previous = previous;
            this.publication = publication;
        }

        @Override
        public void close() {
            if (Thread.currentThread() != owner) {
                throw new IllegalStateException("Filesystem operation scope must close on its owning thread.");
            }
            if (!closed) {
                FileSystemOperationsProvider.close(this);
                closed = true;
            }
        }
    }

    private static final class Diagnostics {
        private long nativeAttempts;
        private long javaAttempts;
        private long nativeOperations;
        private long javaOperations;
        private long failedOperations;
        private long uncertainOperations;
        private long uncertainNativeOperations;
        private long recoveredOperations;
        private final Set<String> fallbacks = new TreeSet<>();
        private final Set<String> sourceTypes = new TreeSet<>();
        private final Set<String> targetTypes = new TreeSet<>();
        private final Set<String> javaMethods = new TreeSet<>();
        // Retained only for this handle; paths never enter a diagnostics snapshot or activity log.
        private final Set<Path> successfulTargets = new HashSet<>();
        private final Set<Path> recoveredTargets = new HashSet<>();
        private String operatingSystem;

        synchronized void fallback(String reason) {
            fallbacks.add(reason);
        }

        synchronized void attempted(boolean nativeExecution, Path source, Path target, String method) {
            if (nativeExecution) {
                nativeAttempts++;
                operatingSystem = systemName();
            } else {
                javaAttempts++;
                javaMethods.add(method == null ? "unknown" : method);
            }
            addFileSystemType(sourceTypes, source);
            try {
                Path targetParent = target == null ? null : target.toAbsolutePath().normalize().getParent();
                addFileSystemType(targetTypes, targetParent);
            } catch (RuntimeException ex) {
                // Provider path conversion is diagnostic-only as well.
            } catch (Error failure) {
                FileSystemOperationsProvider.rethrowFatal(failure);
            }
        }

        synchronized void succeeded(boolean nativeExecution, Path target) {
            if (nativeExecution) {
                nativeOperations++;
            } else {
                javaOperations++;
            }
            Path key = targetKey(target);
            if (key != null) {
                try {
                    successfulTargets.add(key);
                } catch (RuntimeException ex) {
                    // An optional provider's path equality/hash operation is diagnostic-only.
                } catch (Error failure) {
                    FileSystemOperationsProvider.rethrowFatal(failure);
                }
            }
        }

        synchronized void failed(boolean nativeExecution, boolean uncertain) {
            failedOperations++;
            if (uncertain) {
                uncertainOperations++;
                if (nativeExecution) {
                    uncertainNativeOperations++;
                }
            }
        }

        synchronized void recovered(Path target) {
            Path key = targetKey(target);
            if (key != null) {
                try {
                    if (successfulTargets.contains(key) || !recoveredTargets.add(key)) {
                        return;
                    }
                } catch (RuntimeException ex) {
                    // If path comparison is unavailable, retain an unknown observation.
                } catch (Error failure) {
                    FileSystemOperationsProvider.rethrowFatal(failure);
                }
            }
            recoveredOperations++;
            addFileSystemType(targetTypes, target);
        }

        synchronized Map<String, String> snapshot(boolean nativeEnabled) {
            Map<String, String> result = new LinkedHashMap<>();
            result.put("fs.nativeEnabled", Boolean.toString(nativeEnabled));
            result.put("fs.backend", nativeOperations > 0 ? "JNA"
                    : javaOperations > 0 && uncertainNativeOperations == 0 && recoveredOperations == 0 ? "JAVA" : "UNKNOWN");
            result.put("fs.jnaOperations", Long.toString(nativeOperations));
            result.put("fs.javaOperations", Long.toString(javaOperations));
            if (nativeAttempts > 0) {
                result.put("fs.jnaAttempts", Long.toString(nativeAttempts));
            }
            if (javaAttempts > 0) {
                result.put("fs.javaAttempts", Long.toString(javaAttempts));
            }
            if (failedOperations > 0) {
                result.put("fs.failedOperations", Long.toString(failedOperations));
            }
            if (uncertainOperations > 0) {
                result.put("fs.uncertainOperations", Long.toString(uncertainOperations));
            }
            if (recoveredOperations > 0) {
                result.put("fs.recoveredOperations", Long.toString(recoveredOperations));
            }
            if (!fallbacks.isEmpty()) {
                result.put("fs.fallbackReasons", String.join(", ", fallbacks));
            }
            if (operatingSystem != null) {
                result.put("fs.os", operatingSystem);
            }
            if (!sourceTypes.isEmpty()) {
                result.put("fs.sourceType", String.join(", ", sourceTypes));
            }
            if (!targetTypes.isEmpty()) {
                result.put("fs.targetType", String.join(", ", targetTypes));
            }
            if (!javaMethods.isEmpty()) {
                result.put("fs.javaMethods", String.join(", ", javaMethods));
            }
            return Map.copyOf(result);
        }

        synchronized boolean hasObservations() {
            return nativeAttempts > 0 || javaAttempts > 0 || recoveredOperations > 0 || !fallbacks.isEmpty();
        }

        private static String systemName() {
            try {
                return System.getProperty("os.name", "unknown");
            } catch (SecurityException ex) {
                return "unknown";
            }
        }

        private static Path targetKey(Path target) {
            if (target == null) return null;
            try {
                return target.toAbsolutePath().normalize();
            } catch (RuntimeException ex) {
                return null;
            } catch (Error failure) {
                FileSystemOperationsProvider.rethrowFatal(failure);
                return null;
            }
        }

        private static void addFileSystemType(Set<String> types, Path path) {
            if (path == null) {
                return;
            }
            try {
                types.add(Files.getFileStore(path).type());
            } catch (IOException | RuntimeException ex) {
                // Diagnostic collection must not change the result of a file operation.
            } catch (Error failure) {
                FileSystemOperationsProvider.rethrowFatal(failure);
            }
        }
    }
}
