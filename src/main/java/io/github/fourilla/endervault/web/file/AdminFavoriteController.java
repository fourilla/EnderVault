package io.github.fourilla.endervault.web.file;

import io.github.fourilla.endervault.web.support.AdminSpaViewService;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;

@Controller
public class AdminFavoriteController {

    private final AdminSpaViewService adminSpaViewService;

    public AdminFavoriteController(AdminSpaViewService adminSpaViewService) {
        this.adminSpaViewService = adminSpaViewService;
    }

    @GetMapping("/files/favorites")
    public String favorites(Model model) {
        return adminSpaViewService.render(model);
    }
}
