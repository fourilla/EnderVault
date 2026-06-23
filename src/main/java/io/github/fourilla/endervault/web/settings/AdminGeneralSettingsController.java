package io.github.fourilla.endervault.web.settings;

import io.github.fourilla.endervault.settings.GeneralSettingsService;
import io.github.fourilla.endervault.web.support.ActionResponseSupport;
import io.github.fourilla.endervault.web.support.FlashNotification;
import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.util.MultiValueMap;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

@Controller
public class AdminGeneralSettingsController {

    private final GeneralSettingsService generalSettingsService;

    public AdminGeneralSettingsController(GeneralSettingsService generalSettingsService) {
        this.generalSettingsService = generalSettingsService;
    }

    @GetMapping("/admin/settings/general")
    public String generalSettings(Model model) {
        model.addAttribute("settings", generalSettingsService.currentSettings());
        return "general-settings";
    }

    @PostMapping("/admin/settings/general")
    public Object saveGeneralSettings(
            @RequestParam MultiValueMap<String, String> parameters,
            HttpServletRequest request,
            RedirectAttributes redirectAttributes
    ) {
        try {
            generalSettingsService.save(generalSettingsService.updateFrom(parameters));
            return ActionResponseSupport.ok(
                    request,
                    redirectAttributes,
                    FlashNotification.success("General settings saved."),
                    redirectToGeneralSettings()
            );
        } catch (IllegalArgumentException ex) {
            return ActionResponseSupport.badRequest(
                    request,
                    redirectAttributes,
                    FlashNotification.error(ex.getMessage()),
                    redirectToGeneralSettings()
            );
        } catch (IOException ex) {
            return ActionResponseSupport.error(
                    HttpStatus.INTERNAL_SERVER_ERROR,
                    request,
                    redirectAttributes,
                    FlashNotification.error("General settings could not be saved."),
                    redirectToGeneralSettings()
            );
        }
    }

    private String redirectToGeneralSettings() {
        return "redirect:/admin/settings/general";
    }
}
