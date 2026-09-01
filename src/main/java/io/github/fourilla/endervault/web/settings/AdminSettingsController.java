package io.github.fourilla.endervault.web.settings;

import io.github.fourilla.endervault.web.support.AdminSpaViewService;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;

@Controller
public class AdminSettingsController {

    private final AdminSpaViewService adminSpaViewService;

    public AdminSettingsController(AdminSpaViewService adminSpaViewService) {
        this.adminSpaViewService = adminSpaViewService;
    }

    @GetMapping("/admin/settings")
    public String settings(Model model) {
        return adminSpaViewService.render(model);
    }
}
