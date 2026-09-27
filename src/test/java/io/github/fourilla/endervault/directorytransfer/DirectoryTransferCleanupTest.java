package io.github.fourilla.endervault.directorytransfer;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import io.github.fourilla.endervault.config.NasProperties;
import io.github.fourilla.endervault.filecommit.*;
import io.github.fourilla.endervault.pending.*;
import io.github.fourilla.endervault.storage.FileLifecycleService;
import io.github.fourilla.endervault.storage.StorageService;
import io.github.fourilla.endervault.task.*;
import io.github.fourilla.endervault.temporary.TemporaryArtifactRegistry;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import tools.jackson.databind.json.JsonMapper;

class DirectoryTransferCleanupTest {
    @TempDir Path root;
    final JsonMapper mapper = JsonMapper.builder().findAndAddModules().build();
    NasProperties properties;
    DirectoryTransferReviewStore reviews;
    DirectoryTransferRecordCleanup records;
    DirectoryTransferService transfers;
    DirectoryTransferPendingExecutionService pendingExecution;
    PendingFileDecisionRepository pending;
    PendingFileDecisionService pendingService;
    FileCommitJournalStore journals;
    TaskManagerService tasks;
    DirectoryTransferCleanupService cleanup;
    StorageService storage;

    @BeforeEach void setup() throws Exception {
        properties = new NasProperties();
        properties.getStorage().setRoot(root);
        storage = new StorageService(properties);
        storage.initialize();
        reviews = new DirectoryTransferReviewStore(mapper, properties);
        records = spy(new DirectoryTransferRecordCleanup(reviews, mapper));
        journals = spy(new FileCommitJournalStore(mapper, properties));
        journals.initialize();
        pending = spy(new PendingFileDecisionRepository(mapper, properties));
        pending.initialize();
        tasks = mock(TaskManagerService.class);
        when(tasks.listTasks()).thenReturn(List.of());
        var commits = new FileCommitCoordinator(journals, storage, properties);
        var registry = new TemporaryArtifactRegistry();
        var execution = new DirectoryTransferExecution(reviews,
                new DirectoryTransferFilePublisher(reviews, storage, commits, registry), commits, storage, registry);
        var finalizer = new DirectoryTransferFinalizer(reviews, storage, commits, mock(FileLifecycleService.class), mapper);
        transfers = new DirectoryTransferService(reviews, execution, finalizer);
        pendingService = new PendingFileDecisionService(pending, storage, commits, registry, List.of());
        pendingExecution = new DirectoryTransferPendingExecutionService(pendingService, reviews, execution, finalizer);
        cleanup = service();
        Files.createDirectories(root.resolve("from/photos"));
        Files.writeString(root.resolve("from/photos/a.txt"), "content");
        Files.createDirectories(root.resolve("to"));
    }

    DirectoryTransferCleanupService service() {
        return new DirectoryTransferCleanupService(reviews, records, transfers, pendingExecution, pending, journals, tasks, storage);
    }

    DirectoryTransferReview plan() throws IOException {
        return reviews.create(new DirectoryTransferPlanner(storage, properties)
                .planTransfer(DirectoryTransferPlan.Operation.COPY, "from/photos", "to", null));
    }

    DirectoryTransferReview complete() throws IOException {
        var review = plan();
        assertThat(transfers.execute(review.plan().id(), 0, null).phase()).isEqualTo(DirectoryTransferRun.Phase.COMPLETE);
        return review;
    }

    Path metadata(String path) { return reviews.inspectionRoot().resolve(path); }

    java.util.Map<String, String> snapshot() throws IOException {
        var result = new java.util.TreeMap<String, String>();
        try (var paths = Files.walk(root)) {
            for (Path path : (Iterable<Path>) paths::iterator) {
                result.put(root.relativize(path).toString(), Files.isDirectory(path) ? "directory"
                        : java.util.Base64.getEncoder().encodeToString(Files.readAllBytes(path)));
            }
        }
        return result;
    }

    @Test void inspectorScansWithoutWritesAndRepairsOnlyTheSelectedRecord() throws Exception {
        String id = complete().plan().id();
        var other = plan();
        reviews.abandonUnstarted(other.plan().id(), 0);
        var inspector = new CompletedTaskRecordInspector(cleanup);
        var before = snapshot();
        var issues = inspector.inspect();
        assertThat(issues).hasSize(2);
        assertThat(issues).anySatisfy(issue -> {
            assertThat(issue.subject()).isEqualTo(id);
            assertThat(issue.title()).isEqualTo("Copy - Completed");
            assertThat(issue.detail()).contains("/to/photos");
        });
        assertThat(snapshot()).isEqualTo(before);
        inspector.repair(io.github.fourilla.endervault.metadata.MetadataIssueAction.DELETE_COMPLETED_TASK_RECORD, id);
        assertThat(reviews.ids()).containsExactly(other.plan().id());
    }

    @Test void inspectorRepairRevalidatesNewReferencesAndRejectsUnsafeOrForgedActions() throws Exception {
        String id = complete().plan().id();
        var inspector = new CompletedTaskRecordInspector(cleanup);
        assertThat(inspector.inspect()).hasSize(1);
        var active = mock(AppTask.class);
        when(active.status()).thenReturn(TaskStatus.RUNNING);
        when(active.directoryTransferReviews()).thenReturn(java.util.Set.of(id));
        when(tasks.listTasks()).thenReturn(List.of(active));
        assertThatThrownBy(() -> inspector.repair(io.github.fourilla.endervault.metadata.MetadataIssueAction.DELETE_COMPLETED_TASK_RECORD, id))
                .hasMessageContaining("Skipped");
        assertThatThrownBy(() -> inspector.repair(io.github.fourilla.endervault.metadata.MetadataIssueAction.DELETE_METADATA, id))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> inspector.repair(io.github.fourilla.endervault.metadata.MetadataIssueAction.DELETE_COMPLETED_TASK_RECORD, "../anything"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(reviews.ids()).contains(id);
    }

    @Test void scanRetainsCorruptRecordsWithoutRegistryBackupSideEffects() throws Exception {
        String id = complete().plan().id();
        Files.writeString(metadata("runs/" + id + ".json"), "broken-json");
        var before = snapshot();
        assertThat(new CompletedTaskRecordInspector(cleanup).inspect()).isEmpty();
        assertThat(snapshot()).isEqualTo(before);
        Files.writeString(pending.inspectionPath(), "broken-pending");
        before = snapshot();
        assertThat(new CompletedTaskRecordInspector(cleanup).inspect()).singleElement()
                .satisfies(issue -> assertThat(issue.repairable()).isFalse());
        assertThat(snapshot()).isEqualTo(before);
    }

    @Test void interruptedCleanupIsReadOnlyDuringScanAndResumedByRepair() throws Exception {
        String id = complete().plan().id();
        records.approve(id);
        Files.delete(metadata(id + ".json"));
        var before = snapshot();
        var inspector = new CompletedTaskRecordInspector(cleanup);
        assertThat(inspector.inspect()).singleElement().satisfies(issue -> assertThat(issue.title()).contains("Cleanup interrupted"));
        assertThat(snapshot()).isEqualTo(before);
        inspector.repair(io.github.fourilla.endervault.metadata.MetadataIssueAction.DELETE_COMPLETED_TASK_RECORD, id);
        assertThat(reviews.cleanupPending(id)).isFalse();
    }

    @Test void emptyScanDoesNotCreateDirectoriesAndExistingRepairFlowReportsSkippedRecords() throws Exception {
        var inspector = new CompletedTaskRecordInspector(cleanup);
        var before = snapshot();
        assertThat(inspector.inspect()).isEmpty();
        assertThat(snapshot()).isEqualTo(before);
        String id = complete().plan().id();
        var maintenance = new io.github.fourilla.endervault.metadata.MetadataMaintenanceService(List.of(inspector), properties);
        var report = maintenance.scan(List.of("COMPLETED_TASK_RECORDS"));
        var token = report.areaReports().getFirst().issues().getFirst().token();
        cleanup.cleanupOne(id); // Another completion/repair has already removed the candidate.
        var repaired = maintenance.repair(List.of(token));
        assertThat(repaired.repaired()).isZero();
        assertThat(repaired.failed()).isZero();
        assertThat(repaired.skipped()).isEqualTo(1);
        assertThat(repaired.messages()).anyMatch(message -> message.contains("Skipped"));
        assertThat(repaired.repairedTokens()).isEmpty();
    }

    @Test void removesCompletedMetadataButNeverSourceOrDestinationFiles() throws Exception {
        String id = complete().plan().id();
        assertThat(cleanup.cleanup()).isEqualTo(1);
        assertThat(reviews.ids()).isEmpty();
        assertThat(reviews.runIds()).isEmpty();
        assertThat(metadata("results/" + id)).doesNotExist();
        assertThat(metadata("completion/" + id)).doesNotExist();
        assertThat(metadata("executions/" + id + ".json")).doesNotExist();
        assertThat(metadata("cleanup/" + id + ".json")).doesNotExist();
        assertThat(root.resolve("from/photos/a.txt")).hasContent("content");
        assertThat(root.resolve("to/photos/a.txt")).hasContent("content");
        assertThatThrownBy(() -> transfers.execute(id, 0, null)).isInstanceOf(IOException.class);
        assertThat(cleanup.cleanup()).isZero();
    }

    @Test void removesAbandonedUnstartedReview() throws Exception {
        var review = plan();
        reviews.abandonUnstarted(review.plan().id(), 0);
        assertThat(cleanup.cleanup()).isEqualTo(1);
        assertThat(root.resolve("from/photos/a.txt")).hasContent("content");
    }

    @ParameterizedTest
    @EnumSource(value = DirectoryTransferRun.Phase.class, names = {"PUBLISHING", "FINALIZING", "OWNER_COMPLETING", "NEEDS_REVIEW", "ABANDONING"})
    void unfinishedRunsSurviveNewStoreAndCleanup(DirectoryTransferRun.Phase phase) throws Exception {
        var review = plan();
        String id = review.plan().id();
        reviews.freeze(id, 0);
        new DurableJsonFileWriter(mapper).write(metadata("runs/" + id + ".json"), new DirectoryTransferRun(id, 0, phase, true));
        reviews = new DirectoryTransferReviewStore(mapper, properties);
        records = new DirectoryTransferRecordCleanup(reviews, mapper);
        assertThat(service().cleanup()).isZero();
        assertThat(reviews.require(id)).isEqualTo(review);
        assertThat(reviews.run(id).phase()).isEqualTo(phase);
        assertThat(reviews.run(id).paused()).isTrue();
        new DurableJsonFileWriter(mapper).write(metadata("runs/" + id + ".json"), new DirectoryTransferRun(id, 0, phase, false));
        assertThat(service().cleanup()).isZero();
        assertThat(reviews.run(id).phase()).isEqualTo(phase);
    }

    @Test void reviewWithoutExecutionAndMissingOutcomeAreRetained() throws Exception {
        var review = plan();
        assertThat(cleanup.cleanup()).isZero();
        transfers.execute(review.plan().id(), 0, null);
        Files.delete(metadata("completion/" + review.plan().id() + "/" + review.plan().items().getLast().id() + ".json"));
        assertThat(cleanup.cleanup()).isZero();
        assertThat(reviews.require(review.plan().id())).isEqualTo(review);
    }

    @Test void corruptOrUnexpectedRecordsAreNotDeleted() throws Exception {
        var review = complete();
        String id = review.plan().id();
        Path extra = metadata("results/" + id + "/unknown.json");
        Files.writeString(extra, "{}");
        assertThat(cleanup.cleanup()).isZero();
        Files.delete(extra);
        Files.writeString(metadata("completion/" + id + "/" + review.plan().items().getFirst().id() + ".json"), "broken");
        assertThat(cleanup.cleanup()).isZero();
        assertThat(metadata(id + ".json")).exists();
    }

    @Test void successorChainRetainsBothSidesEvenIfSuccessorCompleted() throws Exception {
        var old = plan();
        String oldId = old.plan().id();
        reviews.freeze(oldId, 0);
        var publishing = new DirectoryTransferRun(oldId, 0, DirectoryTransferRun.Phase.PUBLISHING, false);
        reviews.saveRun(null, publishing);
        var finalizing = new DirectoryTransferRun(oldId, 0, DirectoryTransferRun.Phase.FINALIZING, false);
        reviews.saveRun(publishing, finalizing);
        reviews.saveRun(finalizing, new DirectoryTransferRun(oldId, 0, DirectoryTransferRun.Phase.NEEDS_REVIEW, false));
        var next = plan();
        reviews.recordSuccessor(oldId, next.plan().id());
        transfers.execute(next.plan().id(), 0, null);
        assertThat(cleanup.cleanup()).isZero();
        assertThat(reviews.ids()).containsExactlyInAnyOrder(oldId, next.plan().id());
    }

    @Test void activeTaskAndUnsettledTaskDependencyDelayCleanup() throws Exception {
        String id = complete().plan().id();
        var task = mock(AppTask.class);
        when(tasks.listTasks()).thenReturn(List.of(task));
        when(task.status()).thenReturn(TaskStatus.RUNNING);
        when(task.directoryTransferReviews()).thenReturn(java.util.Set.of(id));
        assertThat(cleanup.cleanup()).isZero();
        when(task.status()).thenReturn(TaskStatus.PENDING);
        when(task.directoryTransferReviews()).thenReturn(java.util.Set.of(id));
        assertThat(cleanup.cleanup()).isZero();
        when(task.status()).thenReturn(TaskStatus.COMPLETE);
        assertThat(cleanup.cleanup()).isEqualTo(1);
    }

    @Test void orphanClaimAndUnreadableJournalPreventCleanup() throws Exception {
        String id = complete().plan().id();
        String pendingId = UUID.randomUUID().toString();
        var decision = new PendingFileDecision(pendingId, PendingFileDecisionSource.ADMIN_UPLOAD,
                "staging", "to", "photos", 0, Instant.now(), null, null, null, true);
        Path claim = root.resolve(properties.getStorage().getMetadataDirectory()).resolve("pending-directory-merges/" + pendingId + ".json");
        new DurableJsonFileWriter(mapper).write(claim, new PendingFileDecisionRepository.MergeClaim(id, decision));
        assertThat(pending.list()).isEmpty();
        assertThat(cleanup.cleanup()).isZero();
        Files.delete(claim);
        doReturn(List.of(FileCommitJournalInspection.unreadable("unknown", Instant.now(), new IOException())))
                .when(journals).inspectJournals();
        assertThat(cleanup.cleanup()).isZero();
        assertThat(metadata(id + ".json")).exists();
    }

    @Test void cleanupInterruptedMidDeletionResumesWithoutReexecutingTransfer() throws Exception {
        var review = complete();
        String id = review.plan().id();
        Path completion = metadata("completion/" + id + "/" + review.plan().items().getFirst().id() + ".json");
        doThrow(new IOException("injected shutdown")).when(records).delete(completion);
        assertThat(cleanup.cleanup()).isZero();
        assertThat(metadata("cleanup/" + id + ".json")).exists();
        assertThat(metadata("results/" + id)).doesNotExist();
        assertThat(reviews.runIds()).isEmpty();
        assertThatThrownBy(() -> reviews.require(id)).isInstanceOf(IOException.class);
        reviews = new DirectoryTransferReviewStore(mapper, properties);
        records = new DirectoryTransferRecordCleanup(reviews, mapper);
        assertThat(service().cleanup()).isEqualTo(1);
        assertThat(metadata("cleanup/" + id + ".json")).doesNotExist();
        assertThat(root.resolve("from/photos/a.txt")).hasContent("content");
        assertThat(root.resolve("to/photos/a.txt")).hasContent("content");
    }

    @Test void pausedOrRecoveryFlagOnCompletedRecordRetainsEvidence() throws Exception {
        String id = complete().plan().id();
        var writer = new DurableJsonFileWriter(mapper);
        writer.write(metadata("runs/" + id + ".json"), new DirectoryTransferRun(id, 0, DirectoryTransferRun.Phase.COMPLETE, true));
        assertThat(cleanup.cleanup()).isZero();
        writer.write(metadata("runs/" + id + ".json"), new DirectoryTransferRun(id, 0, DirectoryTransferRun.Phase.COMPLETE, false, true));
        assertThat(cleanup.cleanup()).isZero();
    }

    @Test void completedUploadRetiresOnlyAfterPendingOwnerCompletion() throws Exception {
        Path staged = Files.createDirectory(storage.resolveFileStagingFile("cleanup-upload"));
        Files.writeString(staged.resolve("uploaded.txt"), "uploaded");
        var decision = pendingService.create(staged, PendingFileDecisionSource.DIRECTORY_UPLOAD, "to", "photos", 8);
        var preparation = new DirectoryTransferPendingPreparationService(pendingService,
                new DirectoryTransferPlanner(storage, properties), reviews, pendingExecution);
        var review = preparation.prepare(decision.id(), null);
        assertThat(cleanup.cleanup()).isZero();
        pendingExecution.execute(review.plan().id(), 0, null);
        assertThat(pending.find(decision.id())).isEmpty();
        assertThat(records.claims(pending.inspectionPath()).keySet()).contains(review.plan().id());
        assertThat(cleanup.cleanup()).isEqualTo(1);
        assertThat(records.claims(pending.inspectionPath()).keySet()).isEmpty();
        assertThat(staged).doesNotExist();
        assertThat(root.resolve("to/photos/uploaded.txt")).hasContent("uploaded");
    }

    @Test void abandonedUploadReviewIsKeptUntilReturnedPendingIsResolved() throws Exception {
        Path staged = Files.createDirectory(storage.resolveFileStagingFile("cleanup-retained-upload"));
        Files.writeString(staged.resolve("uploaded.txt"), "uploaded");
        var decision = pendingService.create(staged, PendingFileDecisionSource.DIRECTORY_UPLOAD, "to", "photos", 8);
        var preparation = new DirectoryTransferPendingPreparationService(pendingService,
                new DirectoryTransferPlanner(storage, properties), reviews, pendingExecution);
        var review = preparation.prepare(decision.id(), null);
        pendingExecution.abandonUnstarted(review.plan().id(), 0);
        assertThat(records.claims(pending.inspectionPath()).keySet()).isEmpty();
        assertThat(cleanup.cleanup()).isZero();
        assertThat(staged.resolve("uploaded.txt")).hasContent("uploaded");
        assertThat(reviews.require(review.plan().id())).isEqualTo(review);
    }

    @Test void evenFinishedJournalKeepsItsOwnerReviewUntilJournalIsRemoved() throws Exception {
        var review = complete();
        String id = review.plan().id();
        var entry = mock(FileCommitJournalEntry.class, RETURNS_DEEP_STUBS);
        when(entry.manifest().owner()).thenReturn(new FileCommitOwner(FileCommitOwnerType.DIRECTORY_MERGE,
                id + ":" + review.plan().items().getFirst().id()));
        doReturn(List.of(new FileCommitJournalInspection("remaining-journal", entry, Instant.now(), null)))
                .when(journals).inspectJournals();
        assertThat(cleanup.cleanup()).isZero();
        assertThat(reviews.require(id)).isEqualTo(review);
    }

    @Test void completionEventOnlyCleansItsOwnRecordAndHasNoScheduledRetry() throws Exception {
        String id = complete().plan().id();
        var other = plan();
        reviews.abandonUnstarted(other.plan().id(), 0);
        clearInvocations(records);
        cleanup.onRecordsReleased(new DirectoryTransferRecordsReleased(java.util.Set.of(id)));
        assertThat(reviews.ids()).containsExactly(other.plan().id());
        verify(records, never()).pending();
        verify(records, never()).eligible(other.plan().id());
        assertThat(DirectoryTransferCleanupService.class.getDeclaredMethods()).noneMatch(method ->
                method.isAnnotationPresent(org.springframework.scheduling.annotation.Scheduled.class));
        assertThat(cleanup.cleanup()).isEqualTo(1);
    }

    @Test void completionFailureIsRetainedForExplicitManualRetry() throws Exception {
        String id = complete().plan().id();
        doThrow(new IOException("disk temporarily unavailable")).doCallRealMethod().when(records).finish(id);
        assertThatCode(() -> cleanup.onRecordsReleased(new DirectoryTransferRecordsReleased(java.util.Set.of(id))))
                .doesNotThrowAnyException();
        assertThat(reviews.cleanupPending(id)).isTrue();
        cleanup = service(); // Recreating the service does not resume cleanup.
        assertThat(reviews.cleanupPending(id)).isTrue();
        assertThat(cleanup.cleanup()).isEqualTo(1);
        assertThat(reviews.cleanupPending(id)).isFalse();
    }

    @Test void unrelatedActiveTaskDoesNotPreventSafeCleanup() throws Exception {
        String id = complete().plan().id();
        var active = mock(AppTask.class);
        when(active.status()).thenReturn(TaskStatus.RUNNING);
        when(active.directoryTransferReviews()).thenReturn(java.util.Set.of(UUID.randomUUID().toString()));
        when(tasks.listTasks()).thenReturn(List.of(active));
        assertThat(cleanup.cleanup()).isEqualTo(1);
        assertThat(reviews.ids()).doesNotContain(id);
    }

    @Test void unexpectedFileDuringInterruptedCleanupRetainsApprovalAndRetriesSafely() throws Exception {
        var review = complete();
        String id = review.plan().id();
        records.approve(id);
        Path extra = metadata("results/" + id + "/unexpected.json");
        Files.writeString(extra, "do not delete");
        assertThatThrownBy(() -> cleanup.cleanup()).isInstanceOf(IOException.class);
        assertThat(extra).hasContent("do not delete");
        assertThat(metadata("cleanup/" + id + ".json")).exists();
        Files.delete(extra);
        assertThat(cleanup.cleanup()).isEqualTo(1);
    }

    DirectoryTransferReview completedUpload() throws Exception {
        Path staged = Files.createDirectory(storage.resolveFileStagingFile("cleanup-upload-interruption"));
        Files.writeString(staged.resolve("uploaded.txt"), "uploaded");
        var decision = pendingService.create(staged, PendingFileDecisionSource.DIRECTORY_UPLOAD, "to", "photos", 8);
        var preparation = new DirectoryTransferPendingPreparationService(pendingService,
                new DirectoryTransferPlanner(storage, properties), reviews, pendingExecution);
        var review = preparation.prepare(decision.id(), null);
        pendingExecution.execute(review.plan().id(), 0, null);
        return review;
    }

    @Test void claimDeletionFailureKeepsApprovalAndCanRetryAfterRestart() throws Exception {
        String id = completedUpload().plan().id();
        doThrow(new IOException("claim cleanup unavailable")).when(pending).retireCompletedMergeClaim(any());
        assertThat(cleanup.cleanup()).isZero();
        assertThat(metadata("cleanup/" + id + ".json")).exists();
        assertThat(records.claims(pending.inspectionPath()).keySet()).contains(id);
        pending = new PendingFileDecisionRepository(mapper, properties);
        reviews = new DirectoryTransferReviewStore(mapper, properties);
        records = new DirectoryTransferRecordCleanup(reviews, mapper);
        assertThat(service().cleanup()).isEqualTo(1);
        assertThat(records.claims(pending.inspectionPath()).keySet()).isEmpty();
        assertThat(root.resolve("to/photos/uploaded.txt")).hasContent("uploaded");
    }

    @Test void removedClaimDoesNotPreventResumingMetadataCleanup() throws Exception {
        var review = completedUpload();
        String id = review.plan().id();
        Path firstResult = metadata("results/" + id + "/" + review.plan().items().getFirst().id() + ".json");
        doThrow(new IOException("interrupted after claim removal")).when(records).delete(firstResult);
        assertThat(cleanup.cleanup()).isZero();
        assertThat(records.claims(pending.inspectionPath()).keySet()).isEmpty();
        var inspector = new DirectoryTransferPendingInspector(reviews, pending, mapper,
                mock(DirectoryTransferPendingPreparationService.class), mock(DirectoryTransferPendingReplanningService.class), pendingExecution);
        assertThat(inspector.inspect(null)).isEmpty();
        reviews = new DirectoryTransferReviewStore(mapper, properties);
        records = new DirectoryTransferRecordCleanup(reviews, mapper);
        assertThat(service().cleanup()).isEqualTo(1);
        assertThat(root.resolve("to/photos/uploaded.txt")).hasContent("uploaded");
    }

    @Test void recreatedUploadStagingOrMissingClaimPreventsRetirement() throws Exception {
        var review = completedUpload();
        String id = review.plan().id();
        var claim = records.claims(pending.inspectionPath()).get(id);
        Path staged = Files.createDirectory(storage.resolveFileStagingFile(claim.decision().stagingFilename()));
        Files.writeString(staged.resolve("new.txt"), "new");
        assertThat(cleanup.cleanup()).isZero();
        assertThat(staged.resolve("new.txt")).hasContent("new");
        Files.delete(staged.resolve("new.txt"));
        Files.delete(staged);
        pending.retireCompletedMergeClaim(claim);
        assertThat(cleanup.cleanup()).isZero();
        assertThat(reviews.require(id)).isEqualTo(review);
    }

    @Test void approvalMustBeForcedBeforeRemovingCompletedClaimOnRetry() throws Exception {
        String id = completedUpload().plan().id();
        var claim = records.claims(pending.inspectionPath()).get(id);
        records.approve(id, claim);
        doThrow(new IOException("approval force failed")).when(records).prepareFinish(id);
        assertThatThrownBy(() -> cleanup.cleanup()).isInstanceOf(IOException.class);
        verify(pending, never()).retireCompletedMergeClaim(any());
        assertThat(records.claims(pending.inspectionPath()).keySet()).contains(id);
    }
}
