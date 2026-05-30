package io.github.fourilla.endervault.web;

import io.github.fourilla.endervault.trash.TrashRecord;
import io.github.fourilla.endervault.trash.TrashService;
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

    public AdminTrashController(TrashService trashService) {
        this.trashService = trashService;
    }

    @GetMapping("/files/trash")
    public String trash(Model model) throws IOException {
        List<TrashRecord> records = trashService.list();
        model.addAttribute("records", records);
        return "trash";
    }

    @PostMapping("/files/trash/restore")
    public String restore(@RequestParam("id") String id, RedirectAttributes redirectAttributes) throws IOException {
        trashService.restore(id);
        FlashNotifications.success(redirectAttributes, "Item restored.");
        return "redirect:/files/trash";
    }

    @PostMapping("/files/trash/delete")
    public String delete(@RequestParam("id") String id, RedirectAttributes redirectAttributes) throws IOException {
        trashService.deletePermanently(id);
        FlashNotifications.success(redirectAttributes, "Item permanently deleted.");
        return "redirect:/files/trash";
    }

    @PostMapping("/files/trash/empty")
    public String empty(RedirectAttributes redirectAttributes) throws IOException {
        int deletedCount = trashService.empty();
        FlashNotifications.success(redirectAttributes, "Emptied trash (" + deletedCount + " item(s)).");
        return "redirect:/files/trash";
    }
}
