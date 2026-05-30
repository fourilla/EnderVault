package io.github.fourilla.endervault.web;

import io.github.fourilla.endervault.activity.ActivityLogService;
import io.github.fourilla.endervault.trash.TrashRecord;
import io.github.fourilla.endervault.trash.TrashService;
import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.util.List;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

@Controller
public class AdminTrashController {

    private final TrashService trashService;
    private final ActivityLogService activityLogService;

    public AdminTrashController(TrashService trashService, ActivityLogService activityLogService) {
        this.trashService = trashService;
        this.activityLogService = activityLogService;
    }

    @GetMapping("/files/trash")
    public String trash(Model model) throws IOException {
        List<TrashRecord> records = trashService.list();
        model.addAttribute("records", records);
        return "trash";
    }

    @PostMapping("/files/trash/restore")
    public String restore(
            @RequestParam("id") String id,
            HttpServletRequest request,
            RedirectAttributes redirectAttributes
    ) throws IOException {
        TrashRecord record = trashService.restore(id);
        activityLogService.record("TRASH_RESTORE", request, record.originalPath(), null, "Restored item from trash");
        FlashNotifications.success(redirectAttributes, "Item restored.");
        return "redirect:/files/trash";
    }

    @PostMapping("/files/trash/delete")
    public String delete(
            @RequestParam("id") String id,
            HttpServletRequest request,
            RedirectAttributes redirectAttributes
    ) throws IOException {
        TrashRecord record = trashService.deletePermanently(id);
        activityLogService.record("TRASH_DELETE", request, record.originalPath(), null, "Permanently deleted trash item");
        FlashNotifications.success(redirectAttributes, "Item permanently deleted.");
        return "redirect:/files/trash";
    }

    @PostMapping("/files/trash/empty")
    public String empty(HttpServletRequest request, RedirectAttributes redirectAttributes) throws IOException {
        int deletedCount = trashService.empty();
        activityLogService.record("TRASH_EMPTY", request, null, null, "Emptied trash (" + deletedCount + " item(s))");
        FlashNotifications.success(redirectAttributes, "Emptied trash (" + deletedCount + " item(s)).");
        return "redirect:/files/trash";
    }
}
