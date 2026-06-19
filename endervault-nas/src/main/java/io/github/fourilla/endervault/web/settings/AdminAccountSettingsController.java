package io.github.fourilla.endervault.web.settings;

import io.github.fourilla.endervault.activity.ActivityLogService;
import io.github.fourilla.endervault.settings.AccountSettingsService;
import io.github.fourilla.endervault.web.support.ActionResponseSupport;
import io.github.fourilla.endervault.web.support.FlashNotification;
import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.util.MultiValueMap;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

@Controller
public class AdminAccountSettingsController {

    private final AccountSettingsService accountSettingsService;
    private final ActivityLogService activityLogService;

    public AdminAccountSettingsController(
            AccountSettingsService accountSettingsService,
            ActivityLogService activityLogService
    ) {
        this.accountSettingsService = accountSettingsService;
        this.activityLogService = activityLogService;
    }

    @GetMapping("/admin/settings/account")
    public String accountSettings(Model model) {
        model.addAttribute("accountSettings", accountSettingsService.currentSettings());
        return "account-settings";
    }

    @PostMapping("/admin/settings/account")
    public Object saveAccountSettings(
            @RequestParam MultiValueMap<String, String> parameters,
            HttpServletRequest request,
            RedirectAttributes redirectAttributes
    ) {
        try {
            AccountSettingsService.AccountSettingsUpdate update = accountSettingsService.updateFrom(parameters);
            accountSettingsService.save(update);
            activityLogService.record(
                    "ACCOUNT_UPDATE",
                    request,
                    null,
                    null,
                    "Admin account settings updated.",
                    Map.of(
                            "usernameChanged", Boolean.toString(update.usernameChanged()),
                            "passwordChanged", Boolean.toString(update.passwordChanged()),
                            "passwordLoginEnabled", Boolean.toString(update.passwordLoginEnabled())
                    )
            );
            return ActionResponseSupport.ok(
                    request,
                    redirectAttributes,
                    FlashNotification.success("Account settings saved."),
                    redirectToAccountSettings()
            );
        } catch (IllegalArgumentException ex) {
            return ActionResponseSupport.badRequest(
                    request,
                    redirectAttributes,
                    FlashNotification.error(ex.getMessage()),
                    redirectToAccountSettings()
            );
        } catch (IOException ex) {
            return ActionResponseSupport.error(
                    HttpStatus.INTERNAL_SERVER_ERROR,
                    request,
                    redirectAttributes,
                    FlashNotification.error("Account settings could not be saved."),
                    redirectToAccountSettings()
            );
        }
    }

    private String redirectToAccountSettings() {
        return "redirect:/admin/settings/account";
    }
}
