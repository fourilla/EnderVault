package io.github.fourilla.endervault.directorymerge;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import io.github.fourilla.endervault.task.*;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class DirectoryMergePreparationTaskServiceTest {
    TaskManagerService tasks = mock(TaskManagerService.class);
    DirectoryMergePlanner planner = mock(DirectoryMergePlanner.class);
    DirectoryMergeReviewStore reviews = mock(DirectoryMergeReviewStore.class);
    DirectoryMergePendingPreparationService pending = mock(DirectoryMergePendingPreparationService.class);
    List<TaskWork> work = new ArrayList<>();
    DirectoryMergePreparationTaskService service;
    DirectoryMergePlan plan = mock(DirectoryMergePlan.class);
    DirectoryMergeReview review = mock(DirectoryMergeReview.class);

    @BeforeEach void setup() {
        service = new DirectoryMergePreparationTaskService(tasks, planner, reviews, pending);
        when(tasks.submit(any(), anyString(), nullable(String.class), anyString(), anyString(), any())).thenAnswer(call -> {
            work.add(call.getArgument(5));
            return mock(AppTask.class);
        });
        when(review.plan()).thenReturn(plan);
        when(plan.id()).thenReturn("review-id");
        when(plan.destinationPath()).thenReturn("target/photos");
    }

    @Test void scanRunsInWorkerAndPublishesReferenceOnlyAfterDurableCreate() throws Exception {
        when(planner.planTransfer(any(), anyString(), anyString(), any())).thenReturn(plan);
        when(reviews.create(plan)).thenReturn(review);
        service.transfer(DirectoryMergePlan.Operation.COPY, "photos", "target", "admin", "ip");
        verifyNoInteractions(planner, reviews);
        var context = mock(TaskContext.class);
        assertEquals(TaskStatus.PENDING, work.getFirst().run(context).status());
        var order = inOrder(reviews, context);
        order.verify(reviews).create(plan);
        order.verify(context).resultReference("review-id");
        verify(context).directoryMergeReview("review-id");
    }

    @Test void failedPersistenceDoesNotAnnounceReview() throws Exception {
        when(planner.planTransfer(any(), anyString(), anyString(), any())).thenReturn(plan);
        when(reviews.create(plan)).thenThrow(new IOException("write failed"));
        service.transfer(DirectoryMergePlan.Operation.MOVE, "photos", "target", "admin", "ip");
        var context = mock(TaskContext.class);
        assertThrows(IOException.class, () -> work.getFirst().run(context));
        verify(context, never()).resultReference(anyString());
    }

    @Test void pendingUsesExistingOwnerAwarePreparation() throws Exception {
        when(pending.prepare(eq("pending-id"), any())).thenReturn(review);
        service.pending("pending-id", "admin", "ip");
        work.getFirst().run(mock(TaskContext.class));
        verify(pending).prepare(eq("pending-id"), any());
        verifyNoInteractions(planner, reviews);
    }

    @Test void cancellationBeforeScanningDoesNotClaimPending() {
        service.pending("pending-id", "admin", "ip");
        var context = mock(TaskContext.class);
        doThrow(new TaskCanceledException()).when(context).checkCanceled();
        assertThrows(TaskCanceledException.class, () -> work.getFirst().run(context));
        verifyNoInteractions(pending);
    }

    @Test void transferEndpointCannotSelectPendingOperation() {
        assertThrows(IllegalArgumentException.class,
                () -> service.transfer(DirectoryMergePlan.Operation.PENDING, "photos", "target", "admin", "ip"));
        verifyNoInteractions(tasks);
    }
}
