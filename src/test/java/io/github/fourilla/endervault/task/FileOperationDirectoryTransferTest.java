package io.github.fourilla.endervault.task;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import io.github.fourilla.endervault.activity.ActivityLogService;
import io.github.fourilla.endervault.auth.ClientIpResolver;
import io.github.fourilla.endervault.config.NasProperties;
import io.github.fourilla.endervault.directorytransfer.*;
import io.github.fourilla.endervault.filesystem.FileSystemOperations;
import io.github.fourilla.endervault.storage.*;
import io.github.fourilla.endervault.transfer.*;
import io.github.fourilla.endervault.trash.TrashService;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.json.JsonMapper;

class FileOperationDirectoryTransferTest {
    @TempDir Path root;
    TaskManagerService tasks = mock(TaskManagerService.class);
    StorageService storage = mock(StorageService.class);
    FileLifecycleService lifecycle = mock(FileLifecycleService.class);
    DirectoryTransferPlanner planner = mock(DirectoryTransferPlanner.class);
    DirectoryTransferReviewStore reviews = mock(DirectoryTransferReviewStore.class);
    DirectoryTransferService transfers = mock(DirectoryTransferService.class);
    TaskContext context = mock(TaskContext.class);
    List<TaskWork> work = new ArrayList<>();
    FileOperationTaskService service;

    @BeforeEach void setup() throws Exception {
        service = new FileOperationTaskService(tasks, storage, lifecycle, mock(TrashService.class),
                mock(ActivityLogService.class), mock(ClientIpResolver.class), planner, reviews, transfers);
        when(tasks.submit(any(), anyString(), anyString(), anyString(), anyString(), any())).thenAnswer(call -> {
            work.add(call.getArgument(5));
            return mock(AppTask.class);
        });
        when(storage.normalizeVaultDirectory("target")).thenReturn("target");
        when(storage.summarizeVaultPaths(any())).thenReturn(new StorageOperationSummary(0, 0));
    }

    DirectoryTransferReview directory(String name, boolean approved) throws Exception {
        var source = mock(FileItem.class);
        when(source.directory()).thenReturn(true);
        when(source.path()).thenReturn("source/" + name);
        when(source.parentPath()).thenReturn("source");
        when(storage.describeVaultPath("source/" + name)).thenReturn(source);
        var plan = mock(DirectoryTransferPlan.class);
        when(plan.id()).thenReturn(name);
        when(plan.destinationPath()).thenReturn("target/" + name);
        when(planner.planTransfer(any(), eq("source/" + name), eq("target"), any())).thenReturn(plan);
        var review = mock(DirectoryTransferReview.class);
        when(review.fullyReviewed()).thenReturn(approved);
        when(reviews.create(plan)).thenReturn(review);
        return review;
    }

    TaskOutcome run(TransferOperation operation, TransferBufferItem... items) throws Exception {
        service.queueTransfer(operation, List.of(items), "target", null, ConflictPolicy.OVERWRITE);
        return work.getLast().run(context);
    }

    @Test void collisionsPersistBeforeExposureAndIgnoreLegacyOverwrite() throws Exception {
        directory("photos", false);
        assertEquals(TaskStatus.PENDING, run(TransferOperation.COPY,
                new TransferBufferItem("source/photos", "photos", true)).status());
        var order = inOrder(reviews, context);
        order.verify(reviews).create(any());
        order.verify(context, atLeastOnce()).resultReference("photos");
        verifyNoInteractions(transfers, lifecycle);
        verify(storage, never()).copyVaultPath(anyString(), anyString(), any(), any());
    }

    @Test void nonConflictingDirectoryUsesJournalEngineWithoutRootWideLifecycleReplay() throws Exception {
        directory("photos", true);
        var run = mock(DirectoryTransferRun.class);
        when(run.phase()).thenReturn(DirectoryTransferRun.Phase.COMPLETE);
        when(transfers.execute(eq("photos"), eq(0L), any())).thenReturn(run);
        assertEquals(TaskStatus.COMPLETE, run(TransferOperation.MOVE,
                new TransferBufferItem("source/photos", "photos", true)).status());
        verifyNoInteractions(lifecycle);
        verify(storage, never()).moveVaultPath(anyString(), anyString(), any());
        verify(context, atLeastOnce()).resultReference(null);
    }

    @Test void observedFailureLogsNeedsReviewWithoutPollutingOtherPendingItems() throws Exception {
        var properties = new NasProperties();
        properties.getStorage().setRoot(root);
        var activity = new ActivityLogService(JsonMapper.builder().findAndAddModules().build(), properties);
        activity.initialize();
        service = new FileOperationTaskService(tasks, storage, lifecycle, mock(TrashService.class),
                activity, mock(ClientIpResolver.class), planner, reviews, transfers);
        directory("a", true);
        directory("b", true);
        directory("c", false);
        when(context.taskId()).thenReturn("task-initial");
        var needsReview = mock(DirectoryTransferRun.class);
        when(needsReview.phase()).thenReturn(DirectoryTransferRun.Phase.NEEDS_REVIEW);
        when(transfers.execute(eq("a"), eq(0L), any())).thenAnswer(call -> {
            try (var publication = FileSystemOperations.publication()) {
                assertThrows(IOException.class, () -> FileSystemOperations.systemDefault().moveWithAtomicPreference(
                        root.resolve("private-missing-source.txt"), root.resolve("target.txt")));
            }
            return needsReview;
        });
        when(transfers.execute(eq("b"), eq(0L), any()))
                .thenReturn(needsReview);
        try (var operation = FileSystemOperations.operation(false).activate()) {
            var outcome = run(TransferOperation.MOVE, new TransferBufferItem("source/a", "a", true),
                    new TransferBufferItem("source/b", "b", true), new TransferBufferItem("source/c", "c", true));
            assertEquals(TaskStatus.PENDING, outcome.status());
            assertTrue(outcome.message().contains("3 directory merge(s)"));
            assertTrue(FileSystemOperations.currentDiagnostics().isEmpty());
        }
        var entries = activity.recentCurrentEntries(10);
        assertEquals(1, entries.size());
        var entry = entries.getFirst();
        assertEquals("MOVE", entry.type());
        assertFalse(entry.success());
        assertEquals("anonymous", entry.actor());
        assertEquals("source/a", entry.path());
        assertEquals("target/a", entry.targetPath());
        assertEquals("Directory transfer requires review", entry.message());
        assertEquals("needs-review", entry.metadataView().get("reason"));
        assertEquals("a", entry.metadataView().get("directoryTransferId"));
        assertEquals("task-initial", entry.metadataView().get("taskId"));
        assertEquals("UNKNOWN", entry.metadataView().get("fs.backend"));
        assertEquals("1", entry.metadataView().get("fs.failedOperations"));
        assertFalse(entry.detailLine().contains("private-missing-source"));
        verify(transfers, never()).execute(eq("c"), anyLong(), any());
        verifyNoInteractions(lifecycle);
    }

    @Test void currentSourceTypeOverridesStaleBufferType() throws Exception {
        directory("photos", false);
        assertEquals(TaskStatus.PENDING, run(TransferOperation.COPY,
                new TransferBufferItem("source/photos", "photos", false)).status());
        verifyNoInteractions(transfers);
    }

    @Test void multipleReviewsRemainIndependentAndNormalFilesContinue() throws Exception {
        directory("a", false);
        directory("b", false);
        var file = mock(FileItem.class);
        when(storage.describeVaultPath("source/file")).thenReturn(file);
        when(storage.moveVaultPath("source/file", "target", ConflictPolicy.OVERWRITE)).thenReturn("target/file");
        var outcome = run(TransferOperation.MOVE, new TransferBufferItem("source/a", "a", true),
                new TransferBufferItem("source/file", "file", false), new TransferBufferItem("source/b", "b", true));
        assertEquals(TaskStatus.PENDING, outcome.status());
        assertTrue(outcome.message().contains("2 directory merge(s)"));
        verify(context).directoryTransferReview("a");
        verify(context).directoryTransferReview("b");
        verify(lifecycle).recordMove(anyString(), anyString(), eq("source/file"), eq("target/file"), anyString());
        verify(context, atLeast(2)).resultReference("a");
    }

    @Test void failedReviewSaveDoesNotExposeReferenceOrTouchFiles() throws Exception {
        directory("photos", false);
        when(reviews.create(any())).thenThrow(new IOException("disk failure"));
        assertEquals(TaskStatus.PARTIAL, run(TransferOperation.MOVE,
                new TransferBufferItem("source/photos", "photos", true)).status());
        verify(context, never()).resultReference(anyString());
        verifyNoInteractions(transfers);
    }

    @Test void executionFailureKeepsReviewAndContinuesBatch() throws Exception {
        directory("a", true);
        directory("b", false);
        when(transfers.execute(eq("a"), eq(0L), any())).thenThrow(new IOException("interrupted publication"));
        assertEquals(TaskStatus.PARTIAL, run(TransferOperation.MOVE,
                new TransferBufferItem("source/a", "a", true), new TransferBufferItem("source/b", "b", true)).status());
        verify(context, atLeast(2)).resultReference("a");
    }

    @Test void cancellationKeepsDurableReviewReference() throws Exception {
        directory("a", true);
        when(transfers.execute(eq("a"), eq(0L), any())).thenThrow(new TaskCanceledException());
        assertThrows(TaskCanceledException.class, () -> run(TransferOperation.MOVE,
                new TransferBufferItem("source/a", "a", true)));
        verify(context, atLeast(2)).resultReference("a");
    }

    @Test void moveToSameParentIsNoOpAndCopyNeverOverwritesItself() throws Exception {
        directory("a", false);
        when(storage.describeVaultPath("source/a").parentPath()).thenReturn("target");
        assertEquals(TaskStatus.COMPLETE, run(TransferOperation.MOVE,
                new TransferBufferItem("source/a", "a", true)).status());
        assertEquals(TaskStatus.PARTIAL, run(TransferOperation.COPY,
                new TransferBufferItem("source/a", "a", true)).status());
        verifyNoInteractions(planner, reviews, transfers);
    }
}
