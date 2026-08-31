package io.github.fourilla.endervault.web.pending;

import io.github.fourilla.endervault.web.support.AdminSpaViewService;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;

@Controller
public class AdminPendingFileDecisionController {

    private final AdminSpaViewService adminSpaViewService;

    public AdminPendingFileDecisionController(AdminSpaViewService adminSpaViewService) {
        this.adminSpaViewService = adminSpaViewService;
    }

    @GetMapping("/admin/pending-decisions")
    public String decisions(Model model) {
        return adminSpaViewService.render(model);
    }
}
