package io.github.fourilla.endervault.web;

import io.github.fourilla.endervault.activity.ActivityLogService;
import java.io.IOException;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

@Controller
public class AdminLogController {

    private static final int LOG_PAGE_LIMIT = 200;

    private final ActivityLogService activityLogService;

    public AdminLogController(ActivityLogService activityLogService) {
        this.activityLogService = activityLogService;
    }

    @GetMapping("/files/logs")
    public String logs(
            @RequestParam(value = "file", required = false) String fileName,
            Model model
    ) throws IOException {
        String selectedFile = fileName == null || fileName.isBlank() ? "activity-log.jsonl" : fileName;
        model.addAttribute("logFiles", activityLogService.listLogFiles());
        model.addAttribute("selectedFile", selectedFile);
        model.addAttribute("entries", activityLogService.readEntries(selectedFile, LOG_PAGE_LIMIT));
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
