package io.github.fourilla.endervault.web.settings;

import io.github.fourilla.endervault.filerequest.UploaderNamePolicy;
import io.github.fourilla.endervault.settings.FileRequestSettingsService;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;

@Controller
public class AdminFileRequestSettingsController {

    private final FileRequestSettingsService fileRequestSettingsService;

    public AdminFileRequestSettingsController(FileRequestSettingsService fileRequestSettingsService) {
        this.fileRequestSettingsService = fileRequestSettingsService;
    }

    @GetMapping("/admin/settings/file-requests")
    public String fileRequestSettings(Model model) {
        model.addAttribute("settings", fileRequestSettingsService.currentSettings());
        model.addAttribute("uploaderNamePolicies", UploaderNamePolicy.values());
        return "file-request-settings";
    }
}
