package io.github.fourilla.endervault.task;

import static org.assertj.core.api.Assertions.*;

import io.github.fourilla.endervault.config.NasProperties;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

class TaskRecordReleaseTest {
    @Test void releaseRunsAfterWorkAndOutcomeAndCannotTurnSuccessIntoFailure() throws Exception {
        var manager = new TaskManagerService(new NasProperties());
        var released = new CountDownLatch(1);
        var events = new CopyOnWriteArrayList<DirectoryTransferRecordsReleased>();
        manager.setApplicationEventPublisher(event -> {
            events.add((DirectoryTransferRecordsReleased) event);
            assertThat(manager.listTasks().getFirst().status()).isEqualTo(TaskStatus.COMPLETE);
            released.countDown();
            throw new IllegalStateException("Cleanup unavailable");
        });
        try {
            var task = manager.submit(TaskType.FILE_COPY, "copy", "", "test", "", context -> {
                context.directoryTransferReview("review");
                assertThat(events).isEmpty();
                return TaskOutcome.complete("Files copied");
            });
            assertThat(released.await(5, TimeUnit.SECONDS)).isTrue();
            assertThat(events).containsExactly(new DirectoryTransferRecordsReleased(Set.of("review")));
            assertThat(task.status()).isEqualTo(TaskStatus.COMPLETE);
            assertThat(task.message()).isEqualTo("Files copied");
        } finally { manager.shutdown(); }
    }

    @Test void pendingTaskReleasesOnlyAfterItsDependenciesSettle() throws Exception {
        var manager = new TaskManagerService(new NasProperties());
        var events = new CopyOnWriteArrayList<Object>();
        manager.setApplicationEventPublisher(events::add);
        try {
            var task = manager.submit(TaskType.FILE_MOVE, "move", "", "test", "", context -> {
                context.directoryTransferReview("review");
                return TaskOutcome.pending("Review required");
            });
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            while (task.active() && System.nanoTime() < deadline) Thread.sleep(5);
            assertThat(task.status()).isEqualTo(TaskStatus.PENDING);
            assertThat(events).isEmpty();
            manager.settleDirectoryTransferReviews(task.id(), Set.of("wrong"), false);
            assertThat(events).isEmpty();
            manager.settleDirectoryTransferReviews(task.id(), Set.of("review"), false);
            assertThat(events).containsExactly(new DirectoryTransferRecordsReleased(Set.of("review")));
            manager.settleDirectoryTransferReviews(task.id(), Set.of("review"), false);
            assertThat(events).hasSize(1);
        } finally { manager.shutdown(); }
    }
}
