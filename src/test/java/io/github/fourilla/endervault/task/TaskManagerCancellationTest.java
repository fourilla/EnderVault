package io.github.fourilla.endervault.task;

import static org.assertj.core.api.Assertions.*;

import io.github.fourilla.endervault.config.NasProperties;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;

class TaskManagerCancellationTest {
    @Test void runningTransfersStopCooperativelyForBothCancellationEntrypoints() throws Exception {
        for (var type : new TaskType[] { TaskType.FILE_COPY, TaskType.FILE_MOVE, TaskType.DIRECTORY_MERGE }) {
            for (boolean bulk : new boolean[] { false, true }) {
                var manager = new TaskManagerService(new NasProperties());
                var entered = new CountDownLatch(1);
                var release = new CountDownLatch(1);
                var interrupted = new AtomicBoolean();
                try {
                    var task = manager.submit(type, "test", "", "test", "", context -> {
                        entered.countDown();
                        try { if (!release.await(5, TimeUnit.SECONDS)) throw new IllegalStateException("timeout"); }
                        catch (InterruptedException ex) { interrupted.set(true); throw ex; }
                        context.checkCanceled();
                        return TaskOutcome.complete("unexpected");
                    });
                    assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue();
                    if (bulk) assertThat(manager.requestCancelActive(type)).isEqualTo(1);
                    else manager.cancel(task.id());
                    assertThat(task.active()).isTrue();
                    release.countDown();
                    awaitFinished(task);
                    assertThat(task.status()).isEqualTo(TaskStatus.CANCELED);
                    assertThat(interrupted).isFalse();
                } finally { release.countDown(); manager.shutdown(); }
            }
        }
    }

    @Test void queuedTransferNeverExecutesAndLateCancellationDoesNotUndoCompletion() throws Exception {
        var properties = new NasProperties();
        properties.getTasks().setWorkerThreads(1);
        var manager = new TaskManagerService(properties);
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var queuedRan = new AtomicBoolean();
        try {
            var running = manager.submit(TaskType.FILE_COPY, "test", "", "test", "", context -> {
                entered.countDown();
                if (!release.await(5, TimeUnit.SECONDS)) throw new IllegalStateException("timeout");
                // The last safe point has already passed; completed publication wins.
                return TaskOutcome.complete("finished");
            });
            assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue();
            var queued = manager.submit(TaskType.FILE_COPY, "queued", "", "test", "", context -> {
                queuedRan.set(true); return TaskOutcome.complete("unexpected");
            });
            manager.cancel(queued.id());
            assertThat(queued.status()).isEqualTo(TaskStatus.CANCELED);
            manager.cancel(running.id());
            release.countDown();
            awaitFinished(running);
            assertThat(running.status()).isEqualTo(TaskStatus.COMPLETE);
            assertThat(queuedRan).isFalse();
        } finally { release.countDown(); manager.shutdown(); }
    }

    private static void awaitFinished(AppTask task) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (task.active() && System.nanoTime() < deadline) Thread.sleep(5);
        assertThat(task.active()).isFalse();
    }
}
