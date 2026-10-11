package io.github.fourilla.endervault.task;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.fourilla.endervault.config.NasProperties;
import io.github.fourilla.endervault.filesystem.FileSystemOperations;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class TaskManagerFileSystemPolicyTest {
    @AfterEach
    void restorePolicy() {
        FileSystemOperations.configureNativePolicy(() -> true);
    }

    @Test
    void queuedAndRunningTasksKeepTheirSubmissionPolicyWhileLaterTasksUseTheNewValue() throws Exception {
        var properties = new NasProperties();
        properties.getTasks().setWorkerThreads(1);
        var enabled = new AtomicBoolean(true);
        FileSystemOperations.configureNativePolicy(enabled::get);
        var started = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var finished = new CountDownLatch(3);
        var observed = new ConcurrentHashMap<String, Boolean>();
        var tasks = new TaskManagerService(properties);
        try {
            tasks.submit(TaskType.FILE_MOVE, "Running", "", "admin", "local", context -> {
                started.countDown();
                if (!release.await(10, TimeUnit.SECONDS)) throw new IllegalStateException("Test worker was not released.");
                observed.put("running", FileSystemOperations.operationOrCapture().nativeEnabled());
                finished.countDown();
                return TaskOutcome.complete("Done.");
            });
            assertThat(started.await(10, TimeUnit.SECONDS)).isTrue();
            tasks.submit(TaskType.FILE_MOVE, "Queued", "", "admin", "local", context -> {
                observed.put("queued", FileSystemOperations.operationOrCapture().nativeEnabled());
                finished.countDown();
                return TaskOutcome.complete("Done.");
            });
            enabled.set(false);
            tasks.submit(TaskType.FILE_MOVE, "New", "", "admin", "local", context -> {
                observed.put("new", FileSystemOperations.operationOrCapture().nativeEnabled());
                finished.countDown();
                return TaskOutcome.complete("Done.");
            });
            release.countDown();
            assertThat(finished.await(10, TimeUnit.SECONDS)).isTrue();
            assertThat(observed).containsEntry("running", true).containsEntry("queued", true).containsEntry("new", false);
            assertThat(FileSystemOperations.currentDiagnostics()).isEmpty();
        } finally {
            release.countDown();
            tasks.shutdown();
        }
    }
}
