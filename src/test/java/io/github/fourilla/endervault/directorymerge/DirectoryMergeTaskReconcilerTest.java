package io.github.fourilla.endervault.directorymerge;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import io.github.fourilla.endervault.config.NasProperties;
import io.github.fourilla.endervault.task.*;
import java.io.IOException;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class DirectoryMergeTaskReconcilerTest {
    final TaskManagerService tasks = new TaskManagerService(new NasProperties());
    final DirectoryMergeReviewStore reviews = mock(DirectoryMergeReviewStore.class);
    final DirectoryMergeTaskReconciler reconciler = new DirectoryMergeTaskReconciler(tasks, reviews);

    @AfterEach void close() { tasks.shutdown(); }

    AppTask task(TaskStatus outcome, String... ids) throws Exception {
        var task = tasks.submit(TaskType.FILE_MOVE, "Move items", "target", "admin", "ip", context -> {
            for (String id : ids) context.directoryMergeReview(id);
            if (ids.length > 0) context.resultReference(ids[0]);
            if (outcome == TaskStatus.CANCELED) throw new TaskCanceledException();
            if (outcome == TaskStatus.FAILED) throw new IOException("failure");
            return new TaskOutcome(outcome, "Original outcome");
        });
        long deadline = System.nanoTime() + 5_000_000_000L;
        while (task.active() && System.nanoTime() < deadline) Thread.sleep(5L);
        assertFalse(task.active());
        return task;
    }

    void complete(String id) throws Exception {
        var run = mock(DirectoryMergeRun.class);
        when(run.phase()).thenReturn(DirectoryMergeRun.Phase.COMPLETE);
        when(reviews.run(id)).thenReturn(run);
    }

    @Test void everyDirectoryMustCompleteNotJustTheDisplayedReference() throws Exception {
        var task = task(TaskStatus.PENDING, "a", "b");
        complete("a");
        reconciler.reconcile();
        assertEquals(TaskStatus.PENDING, task.status());
        assertEquals("a", task.resultReference());
        complete("b");
        reconciler.reconcile();
        assertEquals(TaskStatus.COMPLETE, task.status());
        assertNull(task.resultReference());
        assertEquals(Set.of("a", "b"), task.directoryMergeReviews());
        reconciler.reconcile();
        assertEquals(TaskStatus.COMPLETE, task.status());
    }

    @Test void followsReplansUntilTheFinalGenerationCompletes() throws Exception {
        var task = task(TaskStatus.PENDING, "old");
        when(reviews.successor("old")).thenReturn("next");
        when(reviews.successor("next")).thenReturn("last");
        complete("old");
        reconciler.reconcile();
        assertEquals(TaskStatus.PENDING, task.status());
        complete("last");
        reconciler.reconcile();
        assertEquals(TaskStatus.COMPLETE, task.status());
    }

    @Test void incompletePausedAndNeedsReviewOutcomesAreNotSuccess() throws Exception {
        var task = task(TaskStatus.PENDING, "a");
        var run = mock(DirectoryMergeRun.class);
        when(reviews.run("a")).thenReturn(run);
        for (var phase : DirectoryMergeRun.Phase.values()) {
            if (phase == DirectoryMergeRun.Phase.COMPLETE) continue;
            when(run.phase()).thenReturn(phase);
            reconciler.reconcile();
            assertEquals(TaskStatus.PENDING, task.status());
        }
    }

    @Test void failuresAndCancellationRemainHistoricalOutcomes() throws Exception {
        complete("a");
        for (var status : new TaskStatus[]{TaskStatus.FAILED, TaskStatus.PARTIAL, TaskStatus.CANCELED}) {
            var task = task(status, "a");
            reconciler.reconcile();
            assertEquals(status, task.status());
            assertEquals(status == TaskStatus.CANCELED ? "Canceled." : status == TaskStatus.FAILED
                    ? "failure" : "Original outcome", task.message());
        }
        verify(reviews, never()).require(anyString());
    }

    @Test void missingOrCyclicRecordsFailClosedWithoutBlockingOtherTasks() throws Exception {
        var missing = task(TaskStatus.PENDING, "missing");
        var cycle = task(TaskStatus.PENDING, "cycle");
        var good = task(TaskStatus.PENDING, "good");
        when(reviews.require("missing")).thenThrow(new IOException("missing"));
        when(reviews.successor("cycle")).thenReturn("cycle");
        complete("good");
        reconciler.reconcile();
        assertEquals(TaskStatus.PENDING, missing.status());
        assertEquals(TaskStatus.PENDING, cycle.status());
        assertEquals(TaskStatus.COMPLETE, good.status());
    }

    @Test void deletedHistoryAndUnrelatedPendingTasksAreUntouched() throws Exception {
        var unrelated = task(TaskStatus.PENDING);
        var removed = task(TaskStatus.PENDING, "a");
        tasks.delete(removed.id());
        complete("a");
        reconciler.reconcile();
        assertEquals(TaskStatus.PENDING, unrelated.status());
        assertTrue(tasks.listTasks(java.util.List.of(removed.id())).isEmpty());
        verify(reviews, never()).require(anyString());
    }

    @Test void completionRejectsIncompleteDependencySnapshot() throws Exception {
        var task = task(TaskStatus.PENDING, "a", "b");
        tasks.completeDirectoryMergeReviews(task.id(), Set.of("a"));
        assertEquals(TaskStatus.PENDING, task.status());
        tasks.completeDirectoryMergeReviews(task.id(), Set.of());
        assertEquals(TaskStatus.PENDING, task.status());
    }
}
