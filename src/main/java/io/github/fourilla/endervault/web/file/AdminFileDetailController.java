package io.github.fourilla.endervault.web.file;

import io.github.fourilla.endervault.web.support.AdminSpaViewService;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;

@Controller
public class AdminFileDetailController {

    private final AdminSpaViewService adminSpaViewService;

    public AdminFileDetailController(AdminSpaViewService adminSpaViewService) {
        this.adminSpaViewService = adminSpaViewService;
    }

    @GetMapping("/files/detail")
    public String detail(
            @RequestParam("path") String path,
            @RequestParam(value = "comicPage", required = false) Integer comicPage,
            Model model
    ) {
        return adminSpaViewService.render(model);
    }
}
