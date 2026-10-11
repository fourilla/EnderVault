package io.github.fourilla.endervault.filesystem;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class FileSystemOperationPolicyTest {

    @TempDir Path root;

    @BeforeEach
    void initializePolicy() {
        FileSystemOperations.configureNativePolicy(() -> true);
    }

    @AfterEach
    void restorePolicy() {
        assertThat(FileSystemOperationsProvider.currentOperation()).isNull();
        FileSystemOperations.configureNativePolicy(() -> true);
    }

    @Test
    void aSavedPolicyAppliesOnlyToNewOperationsAndTheSameFacade() throws Exception {
        AtomicBoolean configured = new AtomicBoolean(true);
        FileSystemOperations.configureNativePolicy(configured::get);
        NativeFileSystem nativeBackend = mock(NativeFileSystem.class);
        Path source = Files.writeString(root.resolve("source"), "content");
        when(nativeBackend.supportsNoReplaceMove(source, root)).thenReturn(true);
        FileSystemOperations operations = new DefaultFileSystemOperations(nativeBackend, new NioFileSystem());
        FileSystemOperation admitted = FileSystemOperations.captureOperation();
        configured.set(false);
        FileSystemOperation next = FileSystemOperations.captureOperation();

        try (var scope = admitted.activate()) {
            assertThat(operations.supportsNoReplaceMove(source, root)).isTrue();
            assertThat(FileSystemOperations.captureOperation().nativeEnabled()).isTrue();
        }
        try (var scope = next.activate()) {
            assertThat(operations.supportsNoReplaceMove(source, root)).isFalse();
        }
        assertThat(operations.supportsNoReplaceMove(source, root)).isFalse();
        configured.set(true);
        assertThat(operations.supportsNoReplaceMove(source, root)).isTrue();
    }

    @Test
    void disabledPolicyDoesNotLoadNativeCodeEvenForIdentityAndFilesystemQueries() throws Exception {
        FileSystemOperations.configureNativePolicy(() -> false);
        AtomicInteger loads = new AtomicInteger();
        FileSystemOperations operations = new DefaultFileSystemOperations(new NioFileSystem(), () -> {
            loads.incrementAndGet();
            throw new AssertionError("Disabled native access must not initialize JNA.");
        });
        Path source = Files.writeString(root.resolve("source"), "content");

        assertThat(operations.supportsNoReplaceMove(source, root)).isFalse();
        assertThat(operations.moveNoReplace(source, root.resolve("target"))).isEqualTo(FileSystemOperations.MoveResult.UNSUPPORTED);
        operations.readIdentity(source);
        assertThat(operations.sameFileSystem(source, root)).isTrue();
        assertThat(loads.get()).isZero();
    }

    @Test
    void togglingPolicyLoadsTheBackendOnceAndDoesNotDiscardInFlightAccess() throws Exception {
        AtomicBoolean configured = new AtomicBoolean(false);
        FileSystemOperations.configureNativePolicy(configured::get);
        AtomicInteger loads = new AtomicInteger();
        NativeFileSystem backend = mock(NativeFileSystem.class);
        Path source = Files.writeString(root.resolve("source"), "content");
        when(backend.supportsNoReplaceMove(source, root)).thenReturn(true);
        FileSystemOperations operations = new DefaultFileSystemOperations(new NioFileSystem(), () -> {
            loads.incrementAndGet();
            return backend;
        });

        assertThat(operations.supportsNoReplaceMove(source, root)).isFalse();
        configured.set(true);
        FileSystemOperation admitted = FileSystemOperations.captureOperation();
        assertThat(operations.supportsNoReplaceMove(source, root)).isTrue();
        configured.set(false);
        assertThat(operations.supportsNoReplaceMove(source, root)).isFalse();
        try (var scope = admitted.activate()) {
            assertThat(operations.supportsNoReplaceMove(source, root)).isTrue();
        }
        configured.set(true);
        assertThat(operations.supportsNoReplaceMove(source, root)).isTrue();
        assertThat(loads.get()).isOne();
    }

    @Test
    void unavailableNativeInitializationIsNotRetriedByEveryNewTask() throws Exception {
        AtomicInteger loads = new AtomicInteger();
        FileSystemOperations operations = new DefaultFileSystemOperations(new NioFileSystem(), () -> {
            loads.incrementAndGet();
            throw new NoClassDefFoundError("JNA absent");
        });
        Path source = Files.writeString(root.resolve("source"), "content");
        for (int attempt = 0; attempt < 3; attempt++) {
            try (var scope = FileSystemOperations.captureOperation().activate()) {
                assertThat(operations.supportsNoReplaceMove(source, root)).isFalse();
                assertThat(operations.moveNoReplace(source, root.resolve("target")))
                        .isEqualTo(FileSystemOperations.MoveResult.UNSUPPORTED);
            }
        }
        assertThat(loads.get()).isOne();
        assertThat(Files.readString(source)).isEqualTo("content");
    }

    @Test
    void aRecordedPolicyCanBeRestoredWithoutAdoptingTheCurrentSetting() {
        FileSystemOperations.configureNativePolicy(() -> false);
        FileSystemOperation resumed = FileSystemOperations.operation(true);
        try (var scope = resumed.activate()) {
            assertThat(FileSystemOperations.operationOrCapture()).isSameAs(resumed);
            FileSystemOperation child = FileSystemOperations.captureOperation();
            assertThat(child).isNotSameAs(resumed);
            assertThat(child.nativeEnabled()).isTrue();
        }
        assertThat(FileSystemOperations.captureOperation().nativeEnabled()).isFalse();
    }

    @Test
    void nestedScopesRestoreTheirParentAndRejectOutOfOrderClosing() {
        FileSystemOperation outer = FileSystemOperations.operation(true);
        FileSystemOperation inner = FileSystemOperations.operation(false);
        try (var outerScope = outer.activate()) {
            try (var innerScope = inner.activate()) {
                assertThat(FileSystemOperations.operationOrCapture()).isSameAs(inner);
                assertThatThrownBy(outerScope::close).isInstanceOf(IllegalStateException.class);
            }
            assertThat(FileSystemOperations.operationOrCapture()).isSameAs(outer);
        }
        assertThat(FileSystemOperationsProvider.currentOperation()).isNull();
    }

    @Test
    void concurrentExecutorWorkersKeepTheirOwnPolicyAndCloseAfterFailure() throws Exception {
        FileSystemOperations.configureNativePolicy(() -> false);
        FileSystemOperation enabled = FileSystemOperations.operation(true);
        FileSystemOperation disabled = FileSystemOperations.operation(false);
        CountDownLatch bothActive = new CountDownLatch(2);
        try (var executor = Executors.newFixedThreadPool(2)) {
            var enabledResult = executor.submit(() -> {
                try (var scope = enabled.activate()) {
                    bothActive.countDown();
                    assertThat(bothActive.await(5, TimeUnit.SECONDS)).isTrue();
                    return FileSystemOperations.captureOperation().nativeEnabled();
                }
            });
            var disabledResult = executor.submit(() -> {
                try {
                    try (var scope = disabled.activate()) {
                        bothActive.countDown();
                        assertThat(bothActive.await(5, TimeUnit.SECONDS)).isTrue();
                        assertThat(FileSystemOperations.captureOperation().nativeEnabled()).isFalse();
                        throw new IOException("simulated task failure");
                    }
                } catch (IOException expected) {
                    return FileSystemOperationsProvider.currentOperation() == null;
                }
            });
            assertThat(enabledResult.get(5, TimeUnit.SECONDS)).isTrue();
            assertThat(disabledResult.get(5, TimeUnit.SECONDS)).isTrue();
            assertThat(executor.submit(() -> FileSystemOperationsProvider.currentOperation() == null)
                    .get(5, TimeUnit.SECONDS)).isTrue();
        }
    }

    @Test
    void newlyCreatedThreadsDoNotInheritAnotherThreadsOperation() throws Exception {
        FileSystemOperations.configureNativePolicy(() -> false);
        try (var executor = Executors.newSingleThreadExecutor();
             var scope = FileSystemOperations.operation(true).activate()) {
            assertThat(executor.submit(() -> FileSystemOperations.captureOperation().nativeEnabled())
                    .get(5, TimeUnit.SECONDS)).isFalse();
        }
    }

    @Test
    void onlyPrimaryPublicationScopesCollectDiagnostics() throws Exception {
        FileSystemOperations operations = new DefaultFileSystemOperations(null, new NioFileSystem());
        FileSystemOperation operation = FileSystemOperations.operation(false);
        Path journal = Files.writeString(root.resolve("journal-temp"), "journal");
        Path source = Files.writeString(root.resolve("source"), "content");
        try (var scope = operation.activate()) {
            operations.replace(journal, root.resolve("journal"), FileSystemOperations.Atomicity.PREFERRED);
            operations.readIdentity(source);
            operations.sameFileSystem(source, root);
            operations.syncFile(source);
            assertThat(FileSystemOperations.currentDiagnostics()).isEmpty();
            try (var publication = FileSystemOperations.publication()) {
                operations.moveWithAtomicPreference(source, root.resolve("target"));
            }
            assertThat(FileSystemOperations.currentDiagnostics()).containsEntry("fs.javaOperations", "1")
                    .containsEntry("fs.backend", "JAVA");
        }
        assertThat(FileSystemOperations.currentDiagnostics()).isEmpty();
        assertThat(operation.diagnostics()).containsEntry("fs.javaOperations", "1");
    }

    @Test
    void mixedPublicationsUseTheJnaLabelAndKeepBothSuccessCountsAndFilesystemTypes() throws Exception {
        NativeFileSystem backend = mock(NativeFileSystem.class);
        Path source = Files.writeString(root.resolve("source"), "content");
        Path target = root.resolve("target");
        when(backend.supportsNoReplaceMove(source, root)).thenReturn(true);
        when(backend.moveNoReplace(source, target)).thenAnswer(invocation -> {
            Files.move(source, target);
            return FileSystemOperations.MoveResult.MOVED;
        });
        FileSystemOperations operations = new DefaultFileSystemOperations(backend, new NioFileSystem());
        FileSystemOperation operation = FileSystemOperations.operation(true);
        Path javaSource = Files.writeString(root.resolve("java-source"), "java content");
        try (var scope = operation.activate(); var publication = FileSystemOperations.publication()) {
            assertThat(operations.moveNoReplace(source, target)).isEqualTo(FileSystemOperations.MoveResult.MOVED);
            operations.moveWithAtomicPreference(javaSource, root.resolve("java-target"));
        }
        Map<String, String> diagnostics = operation.diagnostics();
        assertThat(diagnostics).containsEntry("fs.backend", "JNA")
                .containsEntry("fs.jnaOperations", "1").containsEntry("fs.javaOperations", "1")
                .containsEntry("fs.os", System.getProperty("os.name"))
                .containsEntry("fs.sourceType", Files.getFileStore(target).type())
                .containsEntry("fs.targetType", Files.getFileStore(root).type());
        assertThat(diagnostics.toString()).doesNotContain(root.toString());
        assertThatThrownBy(() -> diagnostics.put("fs.backend", "changed")).isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void disabledNativeFallbackAndDirectHardlinkPublicationAreRecordedAsJava() throws Exception {
        NativeFileSystem backend = mock(NativeFileSystem.class);
        FileSystemOperations operations = new DefaultFileSystemOperations(backend, new NioFileSystem());
        FileSystemOperation operation = FileSystemOperations.operation(false);
        Path source = Files.writeString(root.resolve("source"), "content");
        Path target = root.resolve("target");
        try (var scope = operation.activate(); var publication = FileSystemOperations.publication()) {
            assertThat(operations.moveNoReplace(source, target)).isEqualTo(FileSystemOperations.MoveResult.UNSUPPORTED);
            Files.createLink(target, source);
            FileSystemOperations.recordJavaPublication(source, target, "hardlink");
            Files.delete(source);
        }
        verifyNoInteractions(backend);
        assertThat(operation.diagnostics()).containsEntry("fs.backend", "JAVA")
                .containsEntry("fs.javaOperations", "1").containsEntry("fs.javaMethods", "hardlink")
                .containsEntry("fs.fallbackReasons", "native-disabled").doesNotContainKey("fs.os");
    }

    @Test
    void uncertainNativeFailureIsNeverClaimedAsACompletedPublicationOrRetried() throws Exception {
        NativeFileSystem backend = mock(NativeFileSystem.class);
        NioFileSystem nio = mock(NioFileSystem.class);
        Path source = Files.writeString(root.resolve("source"), "content");
        Path target = root.resolve("target");
        when(backend.supportsNoReplaceMove(source, root)).thenReturn(true);
        when(backend.moveNoReplace(source, target)).thenAnswer(invocation -> {
            Files.move(source, target);
            throw new IllegalStateException("result lost");
        });
        FileSystemOperations operations = new DefaultFileSystemOperations(backend, nio);
        FileSystemOperation operation = FileSystemOperations.operation(true);
        try (var scope = operation.activate(); var publication = FileSystemOperations.publication()) {
            assertThatThrownBy(() -> operations.moveNoReplace(source, target)).isInstanceOf(IOException.class);
        }
        assertThat(operation.diagnostics()).containsEntry("fs.backend", "UNKNOWN")
                .containsEntry("fs.jnaOperations", "0").containsEntry("fs.jnaAttempts", "1")
                .containsEntry("fs.uncertainOperations", "1");
        verifyNoInteractions(nio);
        assertThat(Files.readString(target)).isEqualTo("content");
    }

    @Test
    void uncertainNativeOutcomeDoesNotClaimEarlierJavaSuccessMakesTheWholeOperationJava() throws Exception {
        NativeFileSystem backend = mock(NativeFileSystem.class);
        Path source = Files.writeString(root.resolve("source"), "content");
        Path target = root.resolve("target");
        when(backend.supportsNoReplaceMove(source, root)).thenReturn(true);
        when(backend.moveNoReplace(source, target)).thenThrow(new IOException("Outcome unavailable"));
        FileSystemOperations operations = new DefaultFileSystemOperations(backend, new NioFileSystem());
        FileSystemOperation operation = FileSystemOperations.operation(true);
        Path javaSource = Files.writeString(root.resolve("java-source"), "java content");
        try (var scope = operation.activate(); var publication = FileSystemOperations.publication()) {
            operations.moveWithAtomicPreference(javaSource, root.resolve("java-target"));
            assertThatThrownBy(() -> operations.moveNoReplace(source, target)).isInstanceOf(IOException.class);
        }
        assertThat(operation.diagnostics()).containsEntry("fs.backend", "UNKNOWN")
                .containsEntry("fs.javaOperations", "1").containsEntry("fs.jnaOperations", "0")
                .containsEntry("fs.uncertainOperations", "1");
    }

    @Test
    void recoveryRecordsAnUnknownPriorBackendWithoutInventingAnExecution() throws Exception {
        Path recovered = Files.writeString(root.resolve("recovered"), "previous publication");
        FileSystemOperation operation = FileSystemOperations.operation(true);
        try (var scope = operation.activate()) {
            FileSystemOperations.recordRecoveredPublication(recovered);
            assertThat(FileSystemOperations.currentDiagnostics()).isEmpty();
            try (var publication = FileSystemOperations.publication()) {
                FileSystemOperations.recordRecoveredPublication(recovered);
                FileSystemOperations.recordRecoveredPublication(root.resolve("unused/../recovered"));
            }
            assertThat(FileSystemOperations.currentDiagnostics()).containsEntry("fs.backend", "UNKNOWN")
                    .containsEntry("fs.recoveredOperations", "1").containsEntry("fs.jnaOperations", "0")
                    .containsEntry("fs.javaOperations", "0").doesNotContainKey("fs.jnaAttempts")
                    .doesNotContainKey("fs.javaAttempts").doesNotContainKey("fs.os");
        }
        assertThat(operation.diagnostics()).containsEntry("fs.targetType", Files.getFileStore(recovered).type());
    }

    @Test
    void verifyingAFreshNioPublicationDoesNotTurnItsBackendIntoUnknown() throws Exception {
        Path source = Files.writeString(root.resolve("source"), "content");
        Path target = root.resolve("target");
        FileSystemOperation operation = FileSystemOperations.operation(false);
        try (var scope = operation.activate(); var publication = FileSystemOperations.publication()) {
            new DefaultFileSystemOperations(null, new NioFileSystem()).moveWithAtomicPreference(source, target);
            FileSystemOperations.recordRecoveredPublication(root.resolve("unused/../target"));
            FileSystemOperations.recordRecoveredPublication(target);
        }
        assertThat(operation.diagnostics()).containsEntry("fs.backend", "JAVA")
                .containsEntry("fs.javaOperations", "1").doesNotContainKey("fs.recoveredOperations");
        assertThat(Files.readString(target)).isEqualTo("content");
    }

    @Test
    void verifyingAFreshDirectJavaPublicationDoesNotCountAsRecovery() throws Exception {
        Path source = Files.writeString(root.resolve("source"), "content");
        Path target = root.resolve("target");
        FileSystemOperation operation = FileSystemOperations.operation(false);
        try (var scope = operation.activate(); var publication = FileSystemOperations.publication()) {
            Files.createLink(target, source);
            FileSystemOperations.recordJavaPublication(source, target, "hardlink");
            FileSystemOperations.recordRecoveredPublication(target);
        }
        assertThat(operation.diagnostics()).containsEntry("fs.backend", "JAVA")
                .containsEntry("fs.javaOperations", "1").doesNotContainKey("fs.recoveredOperations");
    }

    @Test
    void failedPublicationDoesNotSuppressAVerifiedPriorTarget() throws Exception {
        Path source = Files.writeString(root.resolve("source"), "content");
        Path target = Files.writeString(root.resolve("target"), "prior content");
        NioFileSystem nio = new NioFileSystem() {
            @Override
            void move(Path from, Path to, java.nio.file.CopyOption... options) throws IOException {
                throw new java.nio.file.FileAlreadyExistsException(to.toString());
            }
        };
        FileSystemOperation operation = FileSystemOperations.operation(false);
        try (var scope = operation.activate(); var publication = FileSystemOperations.publication()) {
            assertThatThrownBy(() -> new DefaultFileSystemOperations(null, nio).moveAtomically(source, target))
                    .isInstanceOf(java.nio.file.FileAlreadyExistsException.class);
            FileSystemOperations.recordRecoveredPublication(target);
            FileSystemOperations.recordRecoveredPublication(target);
        }
        assertThat(operation.diagnostics()).containsEntry("fs.backend", "UNKNOWN")
                .containsEntry("fs.javaOperations", "0").containsEntry("fs.failedOperations", "1")
                .containsEntry("fs.recoveredOperations", "1");
        assertThat(Files.readString(source)).isEqualTo("content");
        assertThat(Files.readString(target)).isEqualTo("prior content");
    }

    @Test
    void diagnosticTargetNormalizationFailureDoesNotFailPublicationRecording() {
        Path diagnosticTarget = mock(Path.class);
        when(diagnosticTarget.toAbsolutePath()).thenThrow(new SecurityException("Path unavailable"));
        FileSystemOperation operation = FileSystemOperations.operation(false);
        try (var scope = operation.activate(); var publication = FileSystemOperations.publication()) {
            FileSystemOperations.recordJavaPublication(root, diagnosticTarget, "copy");
            FileSystemOperations.recordRecoveredPublication(diagnosticTarget);
        }
        assertThat(operation.diagnostics()).containsEntry("fs.javaOperations", "1")
                .containsEntry("fs.recoveredOperations", "1").containsEntry("fs.backend", "UNKNOWN");
    }

    @Test
    void recoveredBackendRemainsUnknownAfterJavaSuccessButUsesJnaWhenNativeSuccessIsObserved() throws Exception {
        Path recovered = Files.writeString(root.resolve("recovered"), "previous publication");
        Path javaSource = Files.writeString(root.resolve("java-source"), "java content");
        Path source = Files.writeString(root.resolve("source"), "content");
        Path target = root.resolve("target");
        NativeFileSystem backend = mock(NativeFileSystem.class);
        when(backend.supportsNoReplaceMove(source, root)).thenReturn(true);
        when(backend.moveNoReplace(source, target)).thenAnswer(invocation -> {
            Files.move(source, target);
            return FileSystemOperations.MoveResult.MOVED;
        });
        FileSystemOperations operations = new DefaultFileSystemOperations(backend, new NioFileSystem());
        FileSystemOperation operation = FileSystemOperations.operation(true);
        try (var scope = operation.activate(); var publication = FileSystemOperations.publication()) {
            FileSystemOperations.recordRecoveredPublication(recovered);
            operations.moveWithAtomicPreference(javaSource, root.resolve("java-target"));
            assertThat(FileSystemOperations.currentDiagnostics()).containsEntry("fs.backend", "UNKNOWN");
            operations.moveNoReplace(source, target);
            FileSystemOperations.recordRecoveredPublication(target);
            assertThat(FileSystemOperations.currentDiagnostics()).containsEntry("fs.backend", "JNA")
                    .containsEntry("fs.recoveredOperations", "1").containsEntry("fs.javaOperations", "1")
                    .containsEntry("fs.jnaOperations", "1");
        }
    }

    @Test
    void preferredAtomicFallbackCountsOneLogicalPublication() throws Exception {
        Path source = Files.writeString(root.resolve("source"), "content");
        Path target = root.resolve("target");
        AtomicInteger calls = new AtomicInteger();
        NioFileSystem nio = new NioFileSystem() {
            @Override
            void move(Path from, Path to, java.nio.file.CopyOption... options) throws IOException {
                if (calls.incrementAndGet() == 1) {
                    throw new AtomicMoveNotSupportedException(from.toString(), to.toString(), "unsupported");
                }
                super.move(from, to, options);
            }
        };
        FileSystemOperation operation = FileSystemOperations.operation(false);
        try (var scope = operation.activate(); var publication = FileSystemOperations.publication()) {
            new DefaultFileSystemOperations(null, nio).moveWithAtomicPreference(source, target);
        }
        assertThat(calls.get()).isEqualTo(2);
        assertThat(operation.diagnostics()).containsEntry("fs.javaOperations", "1")
                .containsEntry("fs.javaAttempts", "1");
    }

    @Test
    void childOperationsInheritPolicyWithIndependentDiagnosticsAndSuppressTheirParentsPrimaryMarker() throws Exception {
        FileSystemOperation parent = FileSystemOperations.operation(true);
        FileSystemOperation child;
        try (var scope = parent.activate(); var publication = FileSystemOperations.publication()) {
            child = FileSystemOperations.captureOperation();
            try (var childScope = child.activate()) {
                FileSystemOperations.recordJavaPublication(root, root.resolve("child-untracked"), "hardlink");
                assertThat(FileSystemOperations.currentDiagnostics()).isEmpty();
                try (var childPublication = FileSystemOperations.publication()) {
                    FileSystemOperations.recordJavaPublication(root, root.resolve("child"), "hardlink");
                }
            }
            FileSystemOperations.recordJavaPublication(root, root.resolve("parent"), "hardlink");
        }
        assertThat(child.nativeEnabled()).isTrue();
        assertThat(child.diagnostics()).containsEntry("fs.javaOperations", "1");
        assertThat(parent.diagnostics()).containsEntry("fs.javaOperations", "1");
    }

    @Test
    void unavailableDiagnosticFilesystemMetadataDoesNotFailTheRecordedPublication() {
        FileSystemOperation operation = FileSystemOperations.operation(false);
        try (var scope = operation.activate(); var publication = FileSystemOperations.publication()) {
            FileSystemOperations.recordJavaPublication(root.resolve("already-moved"), root.resolve("missing/target"), "hardlink");
        }
        assertThat(operation.diagnostics()).containsEntry("fs.javaOperations", "1")
                .doesNotContainKey("fs.sourceType").doesNotContainKey("fs.targetType");
    }
}
