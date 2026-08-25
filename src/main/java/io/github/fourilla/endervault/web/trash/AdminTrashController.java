package io.github.fourilla.endervault.web.trash;

import io.github.fourilla.endervault.trash.TrashRecord;
import io.github.fourilla.endervault.trash.TrashService;
import java.io.IOException;
import java.util.List;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;

@Controller
public class AdminTrashController {

    private final TrashService trashService;

    public AdminTrashController(TrashService trashService) {
        this.trashService = trashService;
    }

    @GetMapping("/admin/trash")
    public String trash(Model model) throws IOException {
        List<TrashRecord> records = trashService.list();
        model.addAttribute("records", records);
        return "trash";
    }

}
