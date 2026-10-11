package io.github.fourilla.endervault.directorytransfer;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import io.github.fourilla.endervault.activity.ActivityLogService;
import io.github.fourilla.endervault.common.StorageAccessException;
import io.github.fourilla.endervault.config.NasProperties;
import io.github.fourilla.endervault.filesystem.FileSystemOperations;
import io.github.fourilla.endervault.storage.StorageProgressListener;
import io.github.fourilla.endervault.task.*;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CancellationException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.json.JsonMapper;

class DirectoryTransferTaskServiceTest {
    @TempDir Path root;
    private final TaskManagerService tasks = mock(TaskManagerService.class);
    private final DirectoryTransferReviewStore reviews = mock(DirectoryTransferReviewStore.class);
    private final DirectoryTransferService transfers = mock(DirectoryTransferService.class);
    private final DirectoryTransferPendingExecutionService pending = mock(DirectoryTransferPendingExecutionService.class);
    private final DirectoryTransferReplanningService transferReplanning = mock(DirectoryTransferReplanningService.class);
    private final DirectoryTransferPendingReplanningService pendingReplanning = mock(DirectoryTransferPendingReplanningService.class);
    private final ActivityLogService activity = mock(ActivityLogService.class);
    private final DirectoryTransferReview review = mock(DirectoryTransferReview.class);
    private final DirectoryTransferPlan plan = mock(DirectoryTransferPlan.class);
    private final List<TaskWork> work = new ArrayList<>();
    private final String id = UUID.randomUUID().toString();
    private DirectoryTransferTaskService service;

    @BeforeEach void setUp() throws Exception {
        service = new DirectoryTransferTaskService(tasks, reviews, transfers, pending, transferReplanning, pendingReplanning, activity);
        when(reviews.require(id)).thenReturn(review);
        when(review.plan()).thenReturn(plan);
        when(review.revision()).thenReturn(2L);
        when(review.fullyReviewed()).thenReturn(true);
        when(plan.operation()).thenReturn(DirectoryTransferPlan.Operation.COPY);
        when(plan.destinationPath()).thenReturn("target/photos");
        when(plan.sourceReference()).thenReturn("source/photos");
        when(tasks.submit(any(), anyString(), anyString(), anyString(), anyString(), any())).thenAnswer(invocation -> {
            work.add(invocation.getArgument(5));
            return spy(new AppTask(UUID.randomUUID().toString(), invocation.getArgument(0), invocation.getArgument(1),
                    invocation.getArgument(2), invocation.getArgument(3), invocation.getArgument(4)));
        });
    }

    @Test void duplicateSubmissionReturnsSameTaskWithoutExecutingOnRequestThread() throws Exception {
        AppTask first = service.execute(id, 2, "admin", "127.0.0.1");
        assertSame(first, service.execute(id, 2, "admin", "127.0.0.1"));
        assertEquals(1, work.size());
        assertThrows(StorageAccessException.class, () -> service.execute(id, 3, "admin", "ip"));
        assertThrows(StorageAccessException.class, () -> service.replan(id, 2, "admin", "ip"));
        verifyNoInteractions(transfers, pending);
    }

    @Test void abandonmentRejectsQueuedTasksAndAbandonedReviewCannotQueueAgain() throws Exception {
        service.execute(id, 2, "admin", "ip");
        assertThrows(StorageAccessException.class, () -> service.abandonUnstarted(id, 2));
        verify(reviews, never()).abandonUnstarted(anyString(), anyLong());
        var fresh = new DirectoryTransferTaskService(tasks, reviews, transfers, pending, transferReplanning, pendingReplanning, activity);
        when(reviews.run(id)).thenReturn(run(DirectoryTransferRun.Phase.ABANDONED));
        assertThrows(StorageAccessException.class, () -> fresh.execute(id, 2, "admin", "ip"));
    }

    @Test void completedTransferUsesCommonTaskOutcomeWithoutDoubleCountingProgress() throws Exception {
        when(transfers.execute(eq(id), eq(2L), any())).thenReturn(run(DirectoryTransferRun.Phase.COMPLETE));
        service.execute(id, 2, "admin", "ip");
        TaskContext context = mock(TaskContext.class);
        assertEquals(TaskStatus.COMPLETE, work.getFirst().run(context).status());
        verify(context, never()).setTotalItems(anyLong());
        verify(context, never()).incrementProcessedItems();
        verifyNoInteractions(pending);
    }

    @Test void reviewedCompletionLogsObservedBackendOnceAndPreservesActorAndPaths() throws Exception {
        var actualActivity = activityService();
        service = new DirectoryTransferTaskService(tasks, reviews, transfers, pending, transferReplanning,
                pendingReplanning, actualActivity);
        Path source = Files.writeString(root.resolve("staged-file.txt"), "content");
        Path target = root.resolve("published-file.txt");
        when(reviews.run(id)).thenReturn(run(DirectoryTransferRun.Phase.PUBLISHING));
        when(transfers.execute(eq(id), eq(2L), any())).thenAnswer(call -> {
            if (Files.exists(source)) {
                try (var publication = FileSystemOperations.publication()) {
                    FileSystemOperations.systemDefault().moveWithAtomicPreference(source, target);
                }
            }
            when(reviews.run(id)).thenReturn(run(DirectoryTransferRun.Phase.COMPLETE));
            return run(DirectoryTransferRun.Phase.COMPLETE);
        });
        TaskContext context = mock(TaskContext.class);
        when(context.taskId()).thenReturn("task-copy");
        try (var operation = FileSystemOperations.operation(false).activate()) {
            service.execute(id, 2, "operator", "192.0.2.1");
            assertEquals(TaskStatus.COMPLETE, work.getFirst().run(context).status());
            service.execute(id, 2, "another-operator", "192.0.2.2");
            assertEquals(TaskStatus.COMPLETE, work.get(1).run(context).status());
        }
        assertEquals("content", Files.readString(target));
        var entries = actualActivity.recentCurrentEntries(10);
        assertEquals(1, entries.size());
        var entry = entries.getFirst();
        assertEquals("COPY", entry.type());
        assertTrue(entry.success());
        assertEquals("operator", entry.actor());
        assertEquals("192.0.2.1", entry.ip());
        assertEquals("source/photos", entry.path());
        assertEquals("target/photos", entry.targetPath());
        assertEquals(id, entry.metadataView().get("directoryTransferId"));
        assertEquals("task-copy", entry.metadataView().get("taskId"));
        assertEquals("false", entry.metadataView().get("fs.nativeEnabled"));
        assertEquals("JAVA", entry.metadataView().get("fs.backend"));
        assertEquals("1", entry.metadataView().get("fs.javaOperations"));
        verify(transfers, times(2)).execute(eq(id), eq(2L), any());
    }

    @Test void failedMoveLogsActualUnknownOutcomeWithoutExposingExceptionDetails() throws Exception {
        var actualActivity = activityService();
        service = new DirectoryTransferTaskService(tasks, reviews, transfers, pending, transferReplanning,
                pendingReplanning, actualActivity);
        when(plan.operation()).thenReturn(DirectoryTransferPlan.Operation.MOVE);
        when(transfers.execute(eq(id), eq(2L), any())).thenAnswer(call -> {
            try (var publication = FileSystemOperations.publication()) {
                FileSystemOperations.systemDefault().moveWithAtomicPreference(
                        root.resolve("private-internal-file.txt"), root.resolve("target.txt"));
            }
            return run(DirectoryTransferRun.Phase.COMPLETE);
        });
        try (var operation = FileSystemOperations.operation(false).activate()) {
            service.execute(id, 2, "operator", "192.0.2.1");
            assertThrows(IOException.class, () -> work.getFirst().run(mock(TaskContext.class)));
        }
        var entries = actualActivity.recentCurrentEntries(10);
        assertEquals(1, entries.size());
        var entry = entries.getFirst();
        assertEquals("MOVE", entry.type());
        assertFalse(entry.success());
        assertEquals("NoSuchFileException", entry.metadataView().get("reason"));
        assertEquals("UNKNOWN", entry.metadataView().get("fs.backend"));
        assertEquals("0", entry.metadataView().get("fs.javaOperations"));
        assertEquals("1", entry.metadataView().get("fs.failedOperations"));
        assertFalse(entry.detailLine().contains("private-internal-file"));
    }

    @Test void changedTransferRequiringReviewDoesNotClaimCompletedActivity() throws Exception {
        when(transfers.execute(eq(id), eq(2L), any())).thenReturn(run(DirectoryTransferRun.Phase.NEEDS_REVIEW));
        service.execute(id, 2, "admin", "ip");
        assertEquals(TaskStatus.PENDING, work.getFirst().run(mock(TaskContext.class)).status());
        verifyNoInteractions(activity);
    }

    @Test void observedFailureRequiringReviewKeepsUnknownDiagnosticsInActivity() throws Exception {
        var actualActivity = activityService();
        service = new DirectoryTransferTaskService(tasks, reviews, transfers, pending, transferReplanning,
                pendingReplanning, actualActivity);
        when(plan.operation()).thenReturn(DirectoryTransferPlan.Operation.MOVE);
        when(transfers.execute(eq(id), eq(2L), any())).thenAnswer(call -> {
            try (var publication = FileSystemOperations.publication()) {
                assertThrows(IOException.class, () -> FileSystemOperations.systemDefault().moveWithAtomicPreference(
                        root.resolve("private-missing-source.txt"), root.resolve("target.txt")));
            }
            return run(DirectoryTransferRun.Phase.NEEDS_REVIEW);
        });
        TaskContext context = mock(TaskContext.class);
        when(context.taskId()).thenReturn("task-review");
        try (var operation = FileSystemOperations.operation(false).activate()) {
            service.execute(id, 2, "operator", "192.0.2.1");
            assertEquals(TaskStatus.PENDING, work.getFirst().run(context).status());
        }
        var entries = actualActivity.recentCurrentEntries(10);
        assertEquals(1, entries.size());
        var entry = entries.getFirst();
        assertEquals("MOVE", entry.type());
        assertFalse(entry.success());
        assertEquals("Directory transfer requires review", entry.message());
        assertEquals("needs-review", entry.metadataView().get("reason"));
        assertEquals("task-review", entry.metadataView().get("taskId"));
        assertEquals(id, entry.metadataView().get("directoryTransferId"));
        assertEquals("UNKNOWN", entry.metadataView().get("fs.backend"));
        assertEquals("1", entry.metadataView().get("fs.failedOperations"));
        assertFalse(entry.detailLine().contains("private-missing-source"));
    }

    @Test void remainingCopyAbandonmentIsQueuedDeduplicatedAndBlocksResume() throws Exception {
        when(reviews.run(id)).thenReturn(new DirectoryTransferRun(id, 2, DirectoryTransferRun.Phase.PUBLISHING, true));
        var first = service.abandonRemainingTransfer(id, 2, "admin", "ip");
        assertSame(first, service.abandonRemainingTransfer(id, 2, "admin", "ip"));
        assertThrows(StorageAccessException.class, () -> service.execute(id, 2, "admin", "ip"));
        verifyNoInteractions(transfers);
        assertEquals(TaskStatus.COMPLETE, work.getFirst().run(mock(TaskContext.class)).status());
        verify(transfers).abandonRemainingTransfer(id, 2);
        verifyNoInteractions(activity);
    }

    @Test void remainingAbandonmentRejectsActiveWorkAndUpload() throws Exception {
        when(reviews.run(id)).thenReturn(new DirectoryTransferRun(id, 2, DirectoryTransferRun.Phase.PUBLISHING, false));
        assertThrows(StorageAccessException.class, () -> service.abandonRemainingTransfer(id, 2, "admin", "ip"));
        when(reviews.run(id)).thenReturn(new DirectoryTransferRun(id, 2, DirectoryTransferRun.Phase.FINALIZING, true));
        when(plan.operation()).thenReturn(DirectoryTransferPlan.Operation.PENDING);
        assertThrows(StorageAccessException.class, () -> service.abandonRemainingTransfer(id, 2, "admin", "ip"));
        verifyNoInteractions(tasks, transfers);
    }

    @Test void uploadAbandonmentUsesOwnerAwareServiceAndRejectsQueuedWork() throws Exception {
        when(plan.operation()).thenReturn(DirectoryTransferPlan.Operation.PENDING);
        service.abandonUnstarted(id, 2);
        verify(pending).abandonUnstarted(id, 2);
        verify(reviews, never()).abandonUnstarted(anyString(), anyLong());
        service.execute(id, 2, "admin", "ip");
        assertThrows(StorageAccessException.class, () -> service.abandonUnstarted(id, 2));
        verify(pending, times(1)).abandonUnstarted(id, 2);
    }

    @Test void pendingExecutionKeepsChangedItemsInPendingState() throws Exception {
        when(plan.operation()).thenReturn(DirectoryTransferPlan.Operation.PENDING);
        when(reviews.run(id)).thenReturn(run(DirectoryTransferRun.Phase.NEEDS_REVIEW));
        service.execute(id, 2, "admin", "ip");
        assertEquals(TaskStatus.PENDING, work.getFirst().run(mock(TaskContext.class)).status());
        verifyNoInteractions(transfers);
        verifyNoInteractions(activity);
    }

    @Test void replanningDispatchesByOperationWithoutExecutingNewReview() throws Exception {
        when(reviews.run(id)).thenReturn(run(DirectoryTransferRun.Phase.NEEDS_REVIEW));
        when(transferReplanning.replan(eq(id), eq(2L), any())).thenReturn(review);
        service.replan(id, 2, "admin", "ip");
        assertEquals(TaskStatus.PENDING, work.getFirst().run(mock(TaskContext.class)).status());
        when(plan.operation()).thenReturn(DirectoryTransferPlan.Operation.PENDING);
        when(pendingReplanning.replan(eq(id), eq(2L), any())).thenReturn(review);
        service.replan(id, 2, "admin", "ip");
        assertEquals(TaskStatus.PENDING, work.get(1).run(mock(TaskContext.class)).status());
        verifyNoInteractions(transfers, pending);
        verifyNoInteractions(activity);
    }

    @Test void staleOrIncompleteApprovalAndInvalidReplanNeverQueueWork() throws Exception {
        assertThrows(StorageAccessException.class, () -> service.execute(id, 1, "admin", "ip"));
        when(review.fullyReviewed()).thenReturn(false);
        assertThrows(StorageAccessException.class, () -> service.execute(id, 2, "admin", "ip"));
        assertThrows(StorageAccessException.class, () -> service.replan(id, 2, "admin", "ip"));
        assertTrue(work.isEmpty());
    }

    @Test void cancellationReachesEngineAndReleasesSubmissionAfterWorkExits() throws Exception {
        TaskContext context = mock(TaskContext.class);
        doThrow(new TaskCanceledException()).when(context).checkCanceled();
        when(transfers.execute(eq(id), eq(2L), any())).thenAnswer(invocation -> {
            invocation.<StorageProgressListener>getArgument(2).checkCanceled();
            return run(DirectoryTransferRun.Phase.COMPLETE);
        });
        AppTask first = service.execute(id, 2, "admin", "ip");
        assertThrows(TaskCanceledException.class, () -> work.getFirst().run(context));
        assertNotSame(first, service.execute(id, 2, "admin", "ip"));
        verifyNoInteractions(activity);
    }

    @Test void ioFailureIsNotConvertedToSuccessAndCanBeRetried() throws Exception {
        when(transfers.execute(eq(id), eq(2L), any())).thenThrow(new IOException("disk failure"));
        service.execute(id, 2, "admin", "ip");
        assertThrows(IOException.class, () -> work.getFirst().run(mock(TaskContext.class)));
        service.execute(id, 2, "admin", "ip");
        assertEquals(2, work.size());
    }

    @Test void javaCancellationIsReportedAsCanceledNotFailed() throws Exception {
        when(transfers.execute(eq(id), eq(2L), any())).thenThrow(new CancellationException());
        service.execute(id, 2, "admin", "ip");
        assertThrows(TaskCanceledException.class, () -> work.getFirst().run(mock(TaskContext.class)));
        verifyNoInteractions(activity);
    }

    @Test void queuedCancellationDoesNotLeaveSubmissionPermanentlyLocked() throws Exception {
        AppTask first = service.execute(id, 2, "admin", "ip");
        doReturn(false).when(first).active();
        assertNotSame(first, service.execute(id, 2, "admin", "ip"));
    }

    @Test void runningWorkRetainsExclusionEvenIfTaskHasBeenMarkedCanceled() throws Exception {
        AppTask first = service.execute(id, 2, "admin", "ip");
        when(transfers.execute(eq(id), eq(2L), any())).thenAnswer(invocation -> {
            doReturn(false).when(first).active();
            assertSame(first, service.execute(id, 2, "admin", "ip"));
            return run(DirectoryTransferRun.Phase.COMPLETE);
        });
        work.getFirst().run(mock(TaskContext.class));
        assertEquals(1, work.size());
    }

    @Test void pendingCompletionRequiresDurableFinalRunNotJustReturnedItems() throws Exception {
        when(plan.operation()).thenReturn(DirectoryTransferPlan.Operation.PENDING);
        service.execute(id, 2, "admin", "ip");
        assertThrows(IOException.class, () -> work.getFirst().run(mock(TaskContext.class)));
        when(reviews.run(id)).thenReturn(run(DirectoryTransferRun.Phase.COMPLETE));
        service.execute(id, 2, "admin", "ip");
        assertEquals(TaskStatus.COMPLETE, work.get(1).run(mock(TaskContext.class)).status());
        verifyNoInteractions(activity);
    }

    private ActivityLogService activityService() throws IOException {
        var properties = new NasProperties();
        properties.getStorage().setRoot(root);
        var service = new ActivityLogService(JsonMapper.builder().findAndAddModules().build(), properties);
        service.initialize();
        return service;
    }

    private DirectoryTransferRun run(DirectoryTransferRun.Phase phase) {
        return new DirectoryTransferRun(id, 2, phase, false);
    }
}
