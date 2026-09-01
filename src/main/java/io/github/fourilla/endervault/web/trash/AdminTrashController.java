package io.github.fourilla.endervault.web.trash;

import io.github.fourilla.endervault.web.support.AdminSpaViewService;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;

@Controller
public class AdminTrashController {

    private final AdminSpaViewService adminSpaViewService;

    public AdminTrashController(AdminSpaViewService adminSpaViewService) {
        this.adminSpaViewService = adminSpaViewService;
    }

    @GetMapping("/admin/trash")
    public String trash(Model model) {
        return adminSpaViewService.render(model);
    }

}
