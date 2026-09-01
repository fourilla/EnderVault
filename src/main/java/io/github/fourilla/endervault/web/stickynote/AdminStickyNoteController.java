package io.github.fourilla.endervault.web.stickynote;

import io.github.fourilla.endervault.web.support.AdminSpaViewService;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;

@Controller
public class AdminStickyNoteController {

    private final AdminSpaViewService adminSpaViewService;

    public AdminStickyNoteController(AdminSpaViewService adminSpaViewService) {
        this.adminSpaViewService = adminSpaViewService;
    }

    @GetMapping("/admin/sticky-notes")
    public String notes(Model model) {
        return adminSpaViewService.render(model);
    }
}
