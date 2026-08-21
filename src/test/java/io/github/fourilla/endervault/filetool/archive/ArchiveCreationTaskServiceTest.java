package io.github.fourilla.endervault.filetool.archive;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.fourilla.endervault.activity.ActivityLogService;
import io.github.fourilla.endervault.auth.ClientIpResolver;
import io.github.fourilla.endervault.common.StorageAccessException;
import io.github.fourilla.endervault.config.NasProperties;
import io.github.fourilla.endervault.filetool.FileActionRegistry;
import io.github.fourilla.endervault.filecommit.FileCommitCoordinator;
import io.github.fourilla.endervault.filecommit.FileCommitJournalStore;
import io.github.fourilla.endervault.pending.PendingFileDecisionAction;
import io.github.fourilla.endervault.pending.PendingFileDecisionRepository;
import io.github.fourilla.endervault.pending.PendingFileDecisionService;
import io.github.fourilla.endervault.storage.StorageService;
import io.github.fourilla.endervault.task.AppTask;
import io.github.fourilla.endervault.task.TaskManagerService;
import io.github.fourilla.endervault.task.TaskStatus;
import io.github.fourilla.endervault.temporary.TemporaryArtifactRegistry;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mock.web.MockHttpServletRequest;

class ArchiveCreationTaskServiceTest {

    @TempDir
    Path root;

    private StorageService storageService;
    private TaskManagerService taskManagerService;
    private ArchiveCreationTaskService service;
    private TemporaryArtifactRegistry temporaryArtifactRegistry;
    private PendingFileDecisionService pendingFileDecisionService;
    private FileCommitJournalStore fileCommitJournalStore;

    @BeforeEach
    void setUp() throws Exception {
        NasProperties properties = new NasProperties();
        properties.getStorage().setRoot(root);
        properties.getTasks().setWorkerThreads(1);
        temporaryArtifactRegistry = new TemporaryArtifactRegistry();
        storageService = new StorageService(properties, new FileActionRegistry(), temporaryArtifactRegistry);
        storageService.initialize();
        taskManagerService = new TaskManagerService(properties);
        ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();
        fileCommitJournalStore = new FileCommitJournalStore(objectMapper, properties);
        fileCommitJournalStore.initialize();
        FileCommitCoordinator fileCommitCoordinator = new FileCommitCoordinator(
                fileCommitJournalStore,
                storageService,
                properties
        );
        ActivityLogService activityLogService = new ActivityLogService(objectMapper, properties);
        activityLogService.initialize();
        PendingFileDecisionRepository pendingRepository = new PendingFileDecisionRepository(objectMapper, properties);
        pendingRepository.initialize();
        pendingFileDecisionService = new PendingFileDecisionService(
                pendingRepository,
                storageService,
                temporaryArtifactRegistry,
                List.of(new ArchiveOutputPendingDecisionObserver(taskManagerService))
        );
        pendingFileDecisionService.restoreRegistrations();
        service = new ArchiveCreationTaskService(
                storageService,
                taskManagerService,
                activityLogService,
                new ClientIpResolver(properties),
                temporaryArtifactRegistry,
                pendingFileDecisionService,
                fileCommitCoordinator
        );
    }

    @AfterEach
    void tearDown() {
        taskManagerService.shutdown();
    }

    @Test
    void createsSelectedFilesAndDirectoriesAsBackgroundZipTask() throws Exception {
        Files.createDirectories(root.resolve("docs").resolve("empty"));
        Files.writeString(root.resolve("docs").resolve("guide.txt"), "guide");
        Files.writeString(root.resolve("note.txt"), "note");

        AppTask task = service.queue(
                "",
                List.of("docs", "note.txt"),
                "bundle",
                request()
        );
        waitUntilFinished(task);

        assertThat(task.status()).isEqualTo(TaskStatus.COMPLETE);
        assertThat(task.targetPath()).isEqualTo("bundle.zip");
        assertThat(task.processedBytes()).isEqualTo(9L);
        assertThat(task.processedItems()).isEqualTo(4L);
        assertThat(zipEntries(root.resolve("bundle.zip"))).containsEntry("docs/guide.txt", "guide")
                .containsEntry("note.txt", "note")
                .containsKeys("docs/", "docs/empty/");
        assertThat(root.resolve(".endervault/archive-staging")).isEmptyDirectory();
        assertThat(temporaryArtifactRegistry.activeArtifacts()).isEmpty();
        assertThat(fileCommitJournalStore.list()).isEmpty();
    }

    @Test
    void existingArchiveQueuesCompletedZipForReview() throws Exception {
        Files.writeString(root.resolve("report.txt"), "new report");
        Files.writeString(root.resolve("report.zip"), "existing archive");

        AppTask task = service.queue(
                "",
                List.of("report.txt"),
                "",
                request()
        );
        waitUntilFinished(task);

        assertThat(task.status()).isEqualTo(TaskStatus.PENDING);
        assertThat(root.resolve("report.zip")).hasContent("existing archive");
        assertThat(pendingFileDecisionService.list()).hasSize(1);
        pendingFileDecisionService.resolve(
                pendingFileDecisionService.list().get(0).id(),
                PendingFileDecisionAction.KEEP_BOTH,
                null,
                false
        );
        assertThat(task.status()).isEqualTo(TaskStatus.COMPLETE);
        assertThat(zipEntries(root.resolve("report - 1.zip"))).containsEntry("report.txt", "new report");
        assertThat(pendingFileDecisionService.list()).isEmpty();
        assertThat(temporaryArtifactRegistry.activeArtifacts()).isEmpty();
        assertThat(fileCommitJournalStore.list()).isEmpty();
    }

    @Test
    void rejectsEmptySelectionsBeforeQueuing() throws Exception {
        Files.writeString(root.resolve("note.txt"), "note");

        assertThatThrownBy(() -> service.queue(
                "",
                List.of(),
                "empty.zip",
                request()
        )).isInstanceOf(StorageAccessException.class)
                .hasMessageContaining("Select at least one item");
    }

    private MockHttpServletRequest request() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRemoteAddr("127.0.0.1");
        request.setUserPrincipal(() -> "admin");
        return request;
    }

    private void waitUntilFinished(AppTask task) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 5_000L;
        while (task.active() && System.currentTimeMillis() < deadline) {
            Thread.sleep(20L);
        }
        assertThat(task.active()).isFalse();
    }

    private Map<String, String> zipEntries(Path archive) throws Exception {
        Map<String, String> entries = new LinkedHashMap<>();
        try (InputStream input = Files.newInputStream(archive);
                ZipInputStream zip = new ZipInputStream(input)) {
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                entries.put(entry.getName(), new String(zip.readAllBytes(), StandardCharsets.UTF_8));
            }
        }
        return entries;
    }
}
