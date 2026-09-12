package io.github.fourilla.endervault.directorymerge;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import io.github.fourilla.endervault.config.NasProperties;
import io.github.fourilla.endervault.filetool.FileActionRegistry;
import io.github.fourilla.endervault.metadata.MetadataIssueAction;
import io.github.fourilla.endervault.pending.*;
import io.github.fourilla.endervault.storage.StorageService;
import io.github.fourilla.endervault.task.TaskContext;
import io.github.fourilla.endervault.task.TaskCanceledException;
import io.github.fourilla.endervault.temporary.TemporaryArtifactRegistry;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.json.JsonMapper;

class DirectoryMergePendingInspectorTest {
    @TempDir Path vault;
    final JsonMapper mapper = JsonMapper.builder().findAndAddModules().build();
    final DirectoryMergePendingPreparationService preparation = mock(DirectoryMergePendingPreparationService.class);
    DirectoryMergeReviewStore store;
    PendingFileDecisionRepository repository;
    DirectoryMergePendingInspector inspector;
    PendingFileDecision decision;
    DirectoryMergeReview review;
    Path claimPath;

    @BeforeEach void setup() throws Exception {
        var properties = new NasProperties();
        properties.getStorage().setRoot(vault);
        var storage = new StorageService(properties, new FileActionRegistry(), new TemporaryArtifactRegistry());
        storage.initialize();
        Files.createDirectories(vault.resolve("source/photos"));
        Files.createDirectories(vault.resolve("target"));
        repository = new PendingFileDecisionRepository(mapper, properties);
        store = new DirectoryMergeReviewStore(mapper, properties);
        inspector = new DirectoryMergePendingInspector(store, repository, mapper, preparation,
                mock(DirectoryMergePendingReplanningService.class), mock(DirectoryMergePendingExecutionService.class));
        assertThat(inspector.inspect(null)).isEmpty();
        assertThat(store.inspectionRoot()).doesNotExist();
        assertThat(repository.inspectionPath()).doesNotExist();
        decision = new PendingFileDecision(UUID.randomUUID().toString(), PendingFileDecisionSource.DIRECTORY_UPLOAD,
                UUID.randomUUID().toString(), "target", "photos", 0, Instant.now(), null, "upload", "admin", true);
        repository.add(decision);
        var scanned = new DirectoryMergePlanner(storage, properties)
                .planTransfer(DirectoryMergePlan.Operation.COPY, "source/photos", "target", null);
        review = store.create(new DirectoryMergePlan(scanned.id(), DirectoryMergePlan.Operation.PENDING,
                decision.id(), scanned.destinationPath(), scanned.createdAt(), scanned.items()));
        claimPath = repository.inspectionPath().getParent().resolve("pending-directory-merges").resolve(decision.id() + ".json");
        claim(review.plan().id());
    }

    void write(Path path, Object value) throws Exception {
        Files.createDirectories(path.getParent());
        Files.write(path, mapper.writeValueAsBytes(value));
    }
    void claim(String id) throws Exception {
        write(claimPath, new PendingFileDecisionRepository.MergeClaim(id, decision));
    }
    void run(DirectoryMergeRun.Phase phase) throws Exception {
        write(store.inspectionRoot().resolve("runs").resolve(review.plan().id() + ".json"),
                new DirectoryMergeRun(review.plan().id(), review.revision(), phase, false));
    }

    @Test void liveClaimAndRetainedCompletionClaimsAreHealthy() throws Exception {
        assertThat(inspector.inspect(null)).isEmpty();
        repository.remove(decision.id());
        run(DirectoryMergeRun.Phase.OWNER_COMPLETING);
        assertThat(inspector.inspect(null)).isEmpty();
        run(DirectoryMergeRun.Phase.COMPLETE);
        assertThat(inspector.inspect(null)).isEmpty();
        assertThat(claimPath).exists();
    }

    @Test void pendingMustNotDisappearBeforeCompletionOrRemainAfterComplete() throws Exception {
        run(DirectoryMergeRun.Phase.PUBLISHING);
        repository.remove(decision.id());
        assertThat(inspector.inspect(null)).anyMatch(i -> i.title().contains("missing before"));
        repository.add(decision);
        run(DirectoryMergeRun.Phase.COMPLETE);
        assertThat(inspector.inspect(null)).anyMatch(i -> i.title().contains("still has a live"));
    }

    @Test void missingReviewAndMissingClaimAreBothDiagnosed() throws Exception {
        Path path = store.inspectionRoot().resolve(review.plan().id() + ".json");
        byte[] original = Files.readAllBytes(path);
        Files.delete(path);
        assertThat(inspector.inspect(null)).anyMatch(i -> i.title().contains("claim is unreadable"));
        Files.write(path, original);
        Files.delete(claimPath);
        assertThat(inspector.inspect(null)).anyMatch(i -> i.title().contains("no matching owner"));
    }

    @Test void successorBeforeAndAfterOwnerHandoffAreHealthy() throws Exception {
        var plan = review.plan();
        var next = store.create(new DirectoryMergePlan(UUID.randomUUID().toString(), plan.operation(),
                plan.sourceReference(), plan.destinationPath(), Instant.now(), plan.items()));
        run(DirectoryMergeRun.Phase.NEEDS_REVIEW);
        store.recordSuccessor(plan.id(), next.plan().id());
        assertThat(inspector.inspect(null)).isEmpty();
        claim(next.plan().id());
        assertThat(inspector.inspect(null)).isEmpty();
    }

    @Test void unrelatedOwnerAndChangedPendingSnapshotAreRejected() throws Exception {
        var original = review.plan();
        var other = store.create(new DirectoryMergePlan(UUID.randomUUID().toString(), original.operation(),
                UUID.randomUUID().toString(), original.destinationPath(), Instant.now(), original.items()));
        claim(other.plan().id());
        assertThat(inspector.inspect(null)).anyMatch(i -> i.title().contains("claim is unreadable"));
        claim(original.id());
        repository.remove(decision.id());
        repository.add(new PendingFileDecision(decision.id(), decision.source(), decision.stagingFilename(),
                "elsewhere", decision.originalFilename(), decision.size(), decision.createdAt(), null, "upload", "admin", true));
        assertThat(inspector.inspect(null)).anyMatch(i -> i.title().contains("claim is unreadable"));
    }

    @Test void corruptRecordsArePreservedWithoutBackupOrRepair() throws Exception {
        Files.writeString(claimPath, "{broken");
        var issues = inspector.inspect(null);
        assertThat(issues).isNotEmpty().allMatch(i -> i.action() == MetadataIssueAction.NONE);
        assertThat(Files.readString(claimPath)).isEqualTo("{broken");
        try (var files = Files.list(claimPath.getParent())) { assertThat(files.toList()).containsExactly(claimPath); }
        Files.writeString(repository.inspectionPath(), "{broken");
        assertThat(inspector.inspect(null)).anyMatch(i -> i.title().contains("registry is unreadable"));
        assertThat(Files.readString(repository.inspectionPath())).isEqualTo("{broken");
    }

    @Test void preparationWindowIsNotObservedHalfWritten() throws Exception {
        Path path = store.inspectionRoot().resolve(review.plan().id() + ".json");
        byte[] content = Files.readAllBytes(path);
        try (var executor = Executors.newSingleThreadExecutor()) {
            Future<List<io.github.fourilla.endervault.metadata.MetadataIssue>> result;
            var started = new CountDownLatch(1);
            synchronized (preparation) {
                Files.delete(path);
                result = executor.submit(() -> { started.countDown(); return inspector.inspect(null); });
                assertThat(started.await(5, TimeUnit.SECONDS)).isTrue();
                assertThat(result.isDone()).isFalse();
                Files.write(path, content);
            }
            assertThat(result.get(5, TimeUnit.SECONDS)).isEmpty();
        }
    }

    @Test void canceledInspectionPropagatesCancellation() {
        var context = mock(TaskContext.class);
        doThrow(new TaskCanceledException()).when(context).checkCanceled();
        assertThatThrownBy(() -> inspector.inspect(context)).isInstanceOf(TaskCanceledException.class);
    }

    @Test void cyclicSuccessorAndWrongRunIdentityAreDiagnosed() throws Exception {
        Path successor = store.inspectionRoot().resolve("successors").resolve(review.plan().id() + ".json");
        write(successor, Map.of("previous", review.plan().id(), "next", review.plan().id()));
        assertThat(inspector.inspect(null)).anyMatch(i -> i.title().contains("claim is unreadable"));
        Files.delete(successor);
        write(store.inspectionRoot().resolve("runs").resolve(review.plan().id() + ".json"),
                new DirectoryMergeRun(UUID.randomUUID().toString(), 0, DirectoryMergeRun.Phase.COMPLETE, false));
        repository.remove(decision.id());
        assertThat(inspector.inspect(null)).anyMatch(i -> i.title().contains("claim is unreadable"));
    }

    @Test void duplicateRegistryEntriesDoNotSilentlyOverrideEachOther() throws Exception {
        write(repository.inspectionPath(), List.of(decision, decision));
        assertThat(inspector.inspect(null)).anyMatch(i -> i.title().contains("registry is unreadable"));
    }
}
