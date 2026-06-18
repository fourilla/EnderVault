package io.github.fourilla.endervault.web.settings;

import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

@Controller
public class AdminSettingsController {

    @GetMapping("/admin/settings")
    public String settings() {
        return "settings";
    }
}
