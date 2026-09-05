package io.github.fourilla.endervault.activity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import tools.jackson.core.exc.JacksonIOException;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;
import io.github.fourilla.endervault.common.StorageAccessException;
import io.github.fourilla.endervault.config.NasProperties;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ActivityLogServiceTest {

    private static final DateTimeFormatter INPUT_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm");

    @TempDir
    Path root;

    private ActivityLogService activityLogService;

    @BeforeEach
    void setUp() throws Exception {
        NasProperties properties = new NasProperties();
        properties.getStorage().setRoot(root);
        ObjectMapper objectMapper = JsonMapper.builder().findAndAddModules().build();
        activityLogService = new ActivityLogService(objectMapper, properties);
        activityLogService.initialize();
    }

    @Test
    void writesAndReadsRecentJsonLines() throws Exception {
        activityLogService.record("UPLOAD", null, "a.txt", null, "Uploaded a.txt");
        activityLogService.record("RENAME", null, "a.txt", "b.txt", "Renamed item");

        List<ActivityLogEntry> entries = activityLogService.recentCurrentEntries(10);

        assertThat(entries).extracting(ActivityLogEntry::type).containsExactly("RENAME", "UPLOAD");
        assertThat(entries.get(0).displayMessage()).contains("RENAME");
    }

    @Test
    void notifiesAfterWritingActivityLogEntry() throws Exception {
        NasProperties properties = new NasProperties();
        properties.getStorage().setRoot(root);
        ObjectMapper objectMapper = JsonMapper.builder().findAndAddModules().build();
        List<ActivityLogEntry> notifiedEntries = new ArrayList<>();
        ActivityLogService service = new ActivityLogService(objectMapper, properties, notifiedEntries::add);
        service.initialize();

        service.record("LOGIN_SUCCESS", "admin", "127.0.0.1", null, null, true, "Login succeeded", Map.of());

        assertThat(service.recentCurrentEntries(10)).extracting(ActivityLogEntry::type).contains("LOGIN_SUCCESS");
        assertThat(notifiedEntries).hasSize(1);
        assertThat(notifiedEntries.get(0).type()).isEqualTo("LOGIN_SUCCESS");
    }

    @Test
    void rollsCurrentLogWhenItExceedsLimit() throws Exception {
        Path currentLog = currentLogFile();
        Files.writeString(currentLog, "x".repeat(10 * 1024 * 1024));

        activityLogService.record("UPLOAD", null, "new.txt", null, "Uploaded new.txt");

        assertThat(Files.readString(currentLog)).contains("Uploaded new.txt");
        assertThat(activityLogService.listLogFiles())
                .extracting(ActivityLogFile::name)
                .anyMatch(name -> name.startsWith("activity-log-") && name.endsWith(".jsonl"));
    }

    @Test
    void rejectsDeletingCurrentLog() {
        assertThatThrownBy(() -> activityLogService.deleteArchive("activity-log.jsonl"))
                .isInstanceOf(StorageAccessException.class);
    }

    @Test
    void searchesFiltersAndSortsLogEntries() throws Exception {
        activityLogService.record("UPLOAD", "admin", "127.0.0.1", "a.txt", null, true, "Uploaded a.txt", Map.of());
        activityLogService.record(
                "SHARE_ACCESS",
                "anonymous",
                "203.0.113.10",
                "media/video.mp4",
                "preview",
                true,
                "Accessed share link guest",
                Map.of("token", "guest")
        );
        activityLogService.record("DOWNLOAD", "admin", "127.0.0.1", "a.txt", null, true, "Downloaded a.txt", Map.of());

        ActivityLogSearchResult result = activityLogService.searchEntries(
                "activity-log.jsonl",
                new ActivityLogQuery("203.0.113.10", "share_access", "success", "oldest", "", "", 1, 10)
        );

        assertThat(result.totalCount()).isEqualTo(3);
        assertThat(result.matchedCount()).isEqualTo(1);
        assertThat(result.totalPages()).isEqualTo(1);
        assertThat(result.typeOptions()).contains("DOWNLOAD", "SHARE_ACCESS", "UPLOAD");
        assertThat(result.entries()).extracting(ActivityLogEntry::type).containsExactly("SHARE_ACCESS");
        assertThat(result.entries().get(0).detailLine()).contains("ip=203.0.113.10", "token=guest");
    }

    @Test
    void paginatesMatchedLogEntriesAndFiltersByTime() throws Exception {
        activityLogService.record("UPLOAD", null, "a.txt", null, "Uploaded a.txt");
        activityLogService.record("RENAME", null, "a.txt", "b.txt", "Renamed item");
        activityLogService.record("DOWNLOAD", null, "b.txt", null, "Downloaded b.txt");

        ActivityLogSearchResult secondPage = activityLogService.searchEntries(
                "activity-log.jsonl",
                new ActivityLogQuery(null, null, "all", "oldest", "", "", 2, 2)
        );

        assertThat(secondPage.page()).isEqualTo(2);
        assertThat(secondPage.totalPages()).isEqualTo(2);
        assertThat(secondPage.entries()).extracting(ActivityLogEntry::type).containsExactly("DOWNLOAD");

        String tomorrow = LocalDateTime.now().plusDays(1).format(INPUT_FORMATTER);
        ActivityLogSearchResult futureOnly = activityLogService.searchEntries(
                "activity-log.jsonl",
                new ActivityLogQuery(null, null, "all", "oldest", tomorrow, "", 1, 2)
        );

        assertThat(futureOnly.matchedCount()).isZero();
        assertThat(futureOnly.entries()).isEmpty();
    }

    @Test
    void serializationFailureDoesNotEscapeLoggingOrNotifyAnUnwrittenEntry() throws Exception {
        NasProperties properties = new NasProperties();
        properties.getStorage().setRoot(root);
        ObjectMapper failingMapper = mock(ObjectMapper.class);
        when(failingMapper.writeValueAsString(any(ActivityLogEntry.class)))
                .thenThrow(JacksonIOException.construct(new IOException("Serialization failed")));
        List<ActivityLogEntry> notifications = new ArrayList<>();
        ActivityLogService service = new ActivityLogService(failingMapper, properties, notifications::add);
        service.initialize();

        assertThatCode(() -> service.record("UPLOAD", null, "a.txt", null, "Uploaded a.txt"))
                .doesNotThrowAnyException();
        assertThatCode(() -> service.record("UPLOAD", "admin", "127.0.0.1", "a.txt", null,
                true, "Uploaded a.txt", Map.of())).doesNotThrowAnyException();
        assertThat(notifications).isEmpty();
        assertThat(currentLogFile()).doesNotExist();
    }

    private Path currentLogFile() throws Exception {
        return root.resolve(".endervault").resolve("logs").resolve("activity-log.jsonl");
    }
}
