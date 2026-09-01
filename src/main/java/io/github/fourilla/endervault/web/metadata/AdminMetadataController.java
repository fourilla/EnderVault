package io.github.fourilla.endervault.web.metadata;

import io.github.fourilla.endervault.web.support.AdminSpaViewService;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;

@Controller
public class AdminMetadataController {

    private final AdminSpaViewService adminSpaViewService;

    public AdminMetadataController(AdminSpaViewService adminSpaViewService) {
        this.adminSpaViewService = adminSpaViewService;
    }

    @GetMapping("/admin/metadata")
    public String metadata(Model model) {
        return adminSpaViewService.render(model);
    }
}
