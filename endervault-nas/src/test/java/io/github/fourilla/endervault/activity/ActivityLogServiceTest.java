package io.github.fourilla.endervault.activity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import io.github.fourilla.endervault.common.StorageAccessException;
import io.github.fourilla.endervault.config.NasProperties;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ActivityLogServiceTest {

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

    private Path currentLogFile() throws Exception {
        return root.resolve(".endervault").resolve("logs").resolve("activity-log.jsonl");
    }
}
