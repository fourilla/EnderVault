package io.github.fourilla.endervault.web.dashboard;

import io.github.fourilla.endervault.web.support.AdminSpaViewService;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;

@Controller
public class AdminLogController {

    private final AdminSpaViewService adminSpaViewService;

    public AdminLogController(AdminSpaViewService adminSpaViewService) {
        this.adminSpaViewService = adminSpaViewService;
    }

    @GetMapping("/admin/logs")
    public String logs(Model model) {
        return adminSpaViewService.render(model);
    }
}
