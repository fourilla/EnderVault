package io.github.fourilla.endervault.filetool.archive;

import static org.assertj.core.api.Assertions.assertThat;

import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;
import io.github.fourilla.endervault.activity.ActivityLogService;
import io.github.fourilla.endervault.auth.ClientIpResolver;
import io.github.fourilla.endervault.config.NasProperties;
import io.github.fourilla.endervault.filecommit.FileCommitBatchCoordinator;
import io.github.fourilla.endervault.filecommit.FileCommitCoordinator;
import io.github.fourilla.endervault.filecommit.FileCommitJournalStore;
import io.github.fourilla.endervault.filetool.FileActionRegistry;
import io.github.fourilla.endervault.storage.ConflictPolicy;
import io.github.fourilla.endervault.storage.StorageService;
import io.github.fourilla.endervault.task.AppTask;
import io.github.fourilla.endervault.task.TaskManagerService;
import io.github.fourilla.endervault.task.TaskStatus;
import io.github.fourilla.endervault.temporary.TemporaryArtifactRegistry;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mock.web.MockHttpServletRequest;

class ArchiveExtractionTaskServiceTest {

    @TempDir
    Path root;

    private StorageService storageService;
    private TaskManagerService taskManagerService;
    private ArchiveExtractionTaskService service;
    private FileCommitJournalStore journalStore;

    @BeforeEach
    void setUp() throws Exception {
        NasProperties properties = new NasProperties();
        properties.getStorage().setRoot(root);
        properties.getTasks().setWorkerThreads(1);
        TemporaryArtifactRegistry artifacts = new TemporaryArtifactRegistry();
        storageService = new StorageService(properties, new FileActionRegistry(), artifacts);
        storageService.initialize();
        ObjectMapper objectMapper = JsonMapper.builder().findAndAddModules().build();
        journalStore = new FileCommitJournalStore(objectMapper, properties);
        journalStore.initialize();
        FileCommitCoordinator singleCoordinator = new FileCommitCoordinator(
                journalStore, storageService, properties
        );
        FileCommitBatchCoordinator batchCoordinator = new FileCommitBatchCoordinator(
                journalStore, singleCoordinator, storageService
        );
        taskManagerService = new TaskManagerService(properties);
        ActivityLogService activityLogService = new ActivityLogService(objectMapper, properties);
        activityLogService.initialize();
        ArchiveService archiveService = new ArchiveService(List.of(new ZipArchiveBackend()), properties);
        service = new ArchiveExtractionTaskService(
                archiveService,
                storageService,
                taskManagerService,
                activityLogService,
                new ClientIpResolver(properties),
                artifacts,
                batchCoordinator
        );
    }

    @AfterEach
    void tearDown() {
        taskManagerService.shutdown();
    }

    @Test
    void extractsDirectArchiveContentsThroughTheBatchJournal() throws Exception {
        Path archive = root.resolve("bundle.zip");
        try (OutputStream output = Files.newOutputStream(archive);
                ZipOutputStream zip = new ZipOutputStream(output)) {
            write(zip, "a.txt", "a");
            write(zip, "docs/guide.txt", "guide");
        }

        AppTask task = service.queue(
                "bundle.zip",
                "",
                "ignored",
                false,
                ConflictPolicy.CANCEL,
                request()
        );
        waitUntilFinished(task);

        assertThat(task.status()).isEqualTo(TaskStatus.COMPLETE);
        assertThat(root.resolve("a.txt")).hasContent("a");
        assertThat(root.resolve("docs/guide.txt")).hasContent("guide");
        assertThat(root.resolve(".endervault/archive-staging")).isEmptyDirectory();
        assertThat(journalStore.list()).isEmpty();
    }

    private void write(ZipOutputStream zip, String name, String content) throws Exception {
        zip.putNextEntry(new ZipEntry(name));
        zip.write(content.getBytes(StandardCharsets.UTF_8));
        zip.closeEntry();
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
}
