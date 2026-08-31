package io.github.fourilla.endervault.web.dashboard;

import io.github.fourilla.endervault.web.support.AdminSpaViewService;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;

@Controller
public class AdminSessionController {

    private final AdminSpaViewService adminSpaViewService;

    public AdminSessionController(AdminSpaViewService adminSpaViewService) {
        this.adminSpaViewService = adminSpaViewService;
    }

    @GetMapping("/admin/sessions")
    public String sessions(Model model) {
        return adminSpaViewService.render(model);
    }
}
