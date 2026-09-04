package io.github.fourilla.endervault.web.dashboard;

import io.github.fourilla.endervault.web.support.AdminSpaViewService;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;

@Controller
public class AdminDashboardController {

    private final AdminSpaViewService adminSpaViewService;

    public AdminDashboardController(AdminSpaViewService adminSpaViewService) {
        this.adminSpaViewService = adminSpaViewService;
    }

    @GetMapping("/admin/dashboard")
    public String dashboard(Model model) {
        return adminSpaViewService.render(model);
    }
}
