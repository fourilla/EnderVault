package io.github.fourilla.endervault.web.dashboard;

import io.github.fourilla.endervault.activity.ActivityLogQuery;
import io.github.fourilla.endervault.activity.ActivityLogFile;
import io.github.fourilla.endervault.activity.ActivityLogSearchResult;
import io.github.fourilla.endervault.activity.ActivityLogService;
import io.github.fourilla.endervault.web.support.FlashNotifications;
import java.io.IOException;
import java.util.List;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

@Controller
public class AdminLogController {

    private static final List<Integer> PAGE_SIZE_OPTIONS = List.of(50, 100, 200, 500);

    private final ActivityLogService activityLogService;

    public AdminLogController(ActivityLogService activityLogService) {
        this.activityLogService = activityLogService;
    }

    @GetMapping("/files/logs")
    public String logs(
            @RequestParam(value = "file", required = false) String fileName,
            @RequestParam(value = "q", required = false) String queryText,
            @RequestParam(value = "type", required = false) String type,
            @RequestParam(value = "status", required = false) String status,
            @RequestParam(value = "order", required = false) String order,
            @RequestParam(value = "from", required = false) String from,
            @RequestParam(value = "to", required = false) String to,
            @RequestParam(value = "page", required = false) Integer page,
            @RequestParam(value = "size", required = false) Integer size,
            Model model
    ) throws IOException {
        String selectedFile = fileName == null || fileName.isBlank() ? "activity-log.jsonl" : fileName;
        ActivityLogQuery query = new ActivityLogQuery(
                queryText,
                type,
                status,
                order,
                from,
                to,
                page == null ? 1 : page,
                size == null ? ActivityLogQuery.DEFAULT_SIZE : size
        );
        ActivityLogSearchResult result = activityLogService.searchEntries(selectedFile, query);
        List<ActivityLogFile> logFiles = activityLogService.listLogFiles();
        ActivityLogFile selectedLogFile = logFiles.stream()
                .filter(logFile -> logFile.name().equals(selectedFile))
                .findFirst()
                .orElse(null);

        model.addAttribute("logFiles", logFiles);
        model.addAttribute("selectedLogFile", selectedLogFile);
        model.addAttribute("selectedFile", selectedFile);
        model.addAttribute("entries", result.entries());
        model.addAttribute("typeOptions", result.typeOptions());
        model.addAttribute("totalCount", result.totalCount());
        model.addAttribute("matchedCount", result.matchedCount());
        model.addAttribute("logResult", result);
        model.addAttribute("pageSizeOptions", PAGE_SIZE_OPTIONS);
        model.addAttribute("logQuery", query);
        return "logs";
    }

    @PostMapping("/files/logs/delete")
    public String deleteArchive(
            @RequestParam("file") String fileName,
            RedirectAttributes redirectAttributes
    ) throws IOException {
        activityLogService.deleteArchive(fileName);
        FlashNotifications.success(redirectAttributes, "Activity log deleted.");
        return "redirect:/files/logs";
    }
}
