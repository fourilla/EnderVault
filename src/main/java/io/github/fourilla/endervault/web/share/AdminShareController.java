package io.github.fourilla.endervault.web.share;

import io.github.fourilla.endervault.web.support.AdminSpaViewService;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;

@Controller
public class AdminShareController {

    private final AdminSpaViewService adminSpaViewService;

    public AdminShareController(AdminSpaViewService adminSpaViewService) {
        this.adminSpaViewService = adminSpaViewService;
    }

    @GetMapping("/admin/shares")
    public String shares(Model model) {
        return adminSpaViewService.render(model);
    }
}
