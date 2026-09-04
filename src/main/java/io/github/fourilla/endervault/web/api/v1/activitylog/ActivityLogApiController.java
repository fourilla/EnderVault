package io.github.fourilla.endervault.web.api.v1.activitylog;

import io.github.fourilla.endervault.activity.ActivityLogFile;
import io.github.fourilla.endervault.activity.ActivityLogQuery;
import io.github.fourilla.endervault.activity.ActivityLogSearchResult;
import io.github.fourilla.endervault.activity.ActivityLogService;
import io.github.fourilla.endervault.config.NasProperties;
import io.github.fourilla.endervault.web.support.ActionResponse;
import io.github.fourilla.endervault.web.support.FlashNotification;
import java.io.IOException;
import java.util.List;
import java.util.stream.Stream;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/activity-logs")
public class ActivityLogApiController {

    private final ActivityLogService activityLogService;
    private final NasProperties.ActivityLog activityLogProperties;

    public ActivityLogApiController(ActivityLogService activityLogService, NasProperties nasProperties) {
        this.activityLogService = activityLogService;
        this.activityLogProperties = nasProperties.getActivityLog();
    }

    @GetMapping
    public ActivityLogBrowserPayload list(
            @RequestParam(value = "file", required = false) String fileName,
            @RequestParam(value = "q", required = false) String queryText,
            @RequestParam(value = "type", required = false) String type,
            @RequestParam(value = "status", required = false) String status,
            @RequestParam(value = "order", required = false) String order,
            @RequestParam(value = "from", required = false) String from,
            @RequestParam(value = "to", required = false) String to,
            @RequestParam(value = "page", required = false) Integer page,
            @RequestParam(value = "size", required = false) Integer size
    ) throws IOException {
        List<ActivityLogFile> logFiles = activityLogService.listLogFiles();
        String selectedFile = selectedFile(fileName, logFiles);
        ActivityLogQuery query = new ActivityLogQuery(
                queryText,
                type,
                status,
                order,
                from,
                to,
                page == null ? 1 : page,
                size == null ? activityLogProperties.getDefaultPageSize() : size
        );
        ActivityLogSearchResult result = activityLogService.searchEntries(selectedFile, query);
        ActivityLogFile selectedLogFile = logFiles.stream()
                .filter(logFile -> logFile.name().equals(selectedFile))
                .findFirst()
                .orElse(null);

        return ActivityLogBrowserPayload.from(
                logFiles,
                selectedFile,
                selectedLogFile,
                result,
                pageSizeOptions(result.size()),
                query
        );
    }

    @PostMapping("/delete")
    public ActionResponse deleteArchive(@RequestParam("file") String fileName) throws IOException {
        activityLogService.deleteArchive(fileName);
        return ActionResponse.ok(FlashNotification.success("Activity log deleted."));
    }

    private String selectedFile(String requestedFile, List<ActivityLogFile> logFiles) {
        if (requestedFile != null && !requestedFile.isBlank()) {
            return requestedFile;
        }
        return logFiles.stream()
                .filter(ActivityLogFile::current)
                .map(ActivityLogFile::name)
                .findFirst()
                .orElse("activity-log.jsonl");
    }

    private List<Integer> pageSizeOptions(int selectedSize) {
        List<Integer> configured = activityLogProperties.getPageSizeOptions();
        return Stream.concat(
                        configured == null ? Stream.empty() : configured.stream(),
                        Stream.of(selectedSize)
                )
                .filter(option -> option != null && option > 0)
                .map(option -> Math.min(option, ActivityLogQuery.MAX_SIZE))
                .distinct()
                .sorted()
                .toList();
    }
}
