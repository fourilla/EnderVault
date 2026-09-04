package io.github.fourilla.endervault.web.filerequest;

import io.github.fourilla.endervault.web.support.AdminSpaViewService;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;

@Controller
public class AdminFileRequestController {

    private final AdminSpaViewService adminSpaViewService;

    public AdminFileRequestController(AdminSpaViewService adminSpaViewService) {
        this.adminSpaViewService = adminSpaViewService;
    }

    @GetMapping("/admin/file-requests")
    public String requests(Model model) {
        return adminSpaViewService.render(model);
    }

    @GetMapping("/admin/file-requests/{id}")
    public String requestDetail(@PathVariable String id, Model model) {
        return adminSpaViewService.render(model);
    }
}
