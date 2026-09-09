package io.github.fourilla.endervault.directorymerge;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import io.github.fourilla.endervault.common.StorageAccessException;
import io.github.fourilla.endervault.storage.StorageProgressListener;
import io.github.fourilla.endervault.task.*;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CancellationException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class DirectoryMergeTaskServiceTest {
    private final TaskManagerService tasks = mock(TaskManagerService.class);
    private final DirectoryMergeReviewStore reviews = mock(DirectoryMergeReviewStore.class);
    private final DirectoryMergeTransferService transfers = mock(DirectoryMergeTransferService.class);
    private final DirectoryMergePendingExecutionService pending = mock(DirectoryMergePendingExecutionService.class);
    private final DirectoryMergeTransferReplanningService transferReplanning = mock(DirectoryMergeTransferReplanningService.class);
    private final DirectoryMergePendingReplanningService pendingReplanning = mock(DirectoryMergePendingReplanningService.class);
    private final DirectoryMergeReview review = mock(DirectoryMergeReview.class);
    private final DirectoryMergePlan plan = mock(DirectoryMergePlan.class);
    private final List<TaskWork> work = new ArrayList<>();
    private final String id = UUID.randomUUID().toString();
    private DirectoryMergeTaskService service;

    @BeforeEach void setUp() throws Exception {
        service = new DirectoryMergeTaskService(tasks, reviews, transfers, pending, transferReplanning, pendingReplanning);
        when(reviews.require(id)).thenReturn(review);
        when(review.plan()).thenReturn(plan);
        when(review.revision()).thenReturn(2L);
        when(review.fullyReviewed()).thenReturn(true);
        when(plan.operation()).thenReturn(DirectoryMergePlan.Operation.COPY);
        when(plan.destinationPath()).thenReturn("target/photos");
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

    @Test void completedTransferUsesCommonTaskOutcomeWithoutDoubleCountingProgress() throws Exception {
        when(transfers.execute(eq(id), eq(2L), any())).thenReturn(run(DirectoryMergeRun.Phase.COMPLETE));
        service.execute(id, 2, "admin", "ip");
        TaskContext context = mock(TaskContext.class);
        assertEquals(TaskStatus.COMPLETE, work.getFirst().run(context).status());
        verify(context, never()).setTotalItems(anyLong());
        verify(context, never()).incrementProcessedItems();
        verifyNoInteractions(pending);
    }

    @Test void pendingExecutionKeepsChangedItemsInPendingState() throws Exception {
        when(plan.operation()).thenReturn(DirectoryMergePlan.Operation.PENDING);
        when(reviews.run(id)).thenReturn(run(DirectoryMergeRun.Phase.NEEDS_REVIEW));
        service.execute(id, 2, "admin", "ip");
        assertEquals(TaskStatus.PENDING, work.getFirst().run(mock(TaskContext.class)).status());
        verifyNoInteractions(transfers);
    }

    @Test void replanningDispatchesByOperationWithoutExecutingNewReview() throws Exception {
        when(reviews.run(id)).thenReturn(run(DirectoryMergeRun.Phase.NEEDS_REVIEW));
        when(transferReplanning.replan(eq(id), eq(2L), any())).thenReturn(review);
        service.replan(id, 2, "admin", "ip");
        assertEquals(TaskStatus.PENDING, work.getFirst().run(mock(TaskContext.class)).status());
        when(plan.operation()).thenReturn(DirectoryMergePlan.Operation.PENDING);
        when(pendingReplanning.replan(eq(id), eq(2L), any())).thenReturn(review);
        service.replan(id, 2, "admin", "ip");
        assertEquals(TaskStatus.PENDING, work.get(1).run(mock(TaskContext.class)).status());
        verifyNoInteractions(transfers, pending);
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
            return run(DirectoryMergeRun.Phase.COMPLETE);
        });
        AppTask first = service.execute(id, 2, "admin", "ip");
        assertThrows(TaskCanceledException.class, () -> work.getFirst().run(context));
        assertNotSame(first, service.execute(id, 2, "admin", "ip"));
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
            return run(DirectoryMergeRun.Phase.COMPLETE);
        });
        work.getFirst().run(mock(TaskContext.class));
        assertEquals(1, work.size());
    }

    @Test void pendingCompletionRequiresDurableFinalRunNotJustReturnedItems() throws Exception {
        when(plan.operation()).thenReturn(DirectoryMergePlan.Operation.PENDING);
        service.execute(id, 2, "admin", "ip");
        assertThrows(IOException.class, () -> work.getFirst().run(mock(TaskContext.class)));
        when(reviews.run(id)).thenReturn(run(DirectoryMergeRun.Phase.COMPLETE));
        service.execute(id, 2, "admin", "ip");
        assertEquals(TaskStatus.COMPLETE, work.get(1).run(mock(TaskContext.class)).status());
    }

    private DirectoryMergeRun run(DirectoryMergeRun.Phase phase) {
        return new DirectoryMergeRun(id, 2, phase, false);
    }
}
