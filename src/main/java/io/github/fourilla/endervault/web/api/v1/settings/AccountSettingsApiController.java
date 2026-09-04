package io.github.fourilla.endervault.web.api.v1.settings;

import io.github.fourilla.endervault.activity.ActivityLogService;
import io.github.fourilla.endervault.settings.AccountSettingsService;
import io.github.fourilla.endervault.web.support.ActionResponse;
import io.github.fourilla.endervault.web.support.FlashNotification;
import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.util.Map;
import org.springframework.util.MultiValueMap;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/settings/account")
public class AccountSettingsApiController {

    private final AccountSettingsService accountSettingsService;
    private final ActivityLogService activityLogService;

    public AccountSettingsApiController(
            AccountSettingsService accountSettingsService,
            ActivityLogService activityLogService
    ) {
        this.accountSettingsService = accountSettingsService;
        this.activityLogService = activityLogService;
    }

    @GetMapping
    public AccountSettingsService.AccountSettingsSnapshot current() {
        return accountSettingsService.currentSettings();
    }

    @PostMapping
    public ActionResponse save(
            @RequestParam MultiValueMap<String, String> parameters,
            HttpServletRequest request
    ) throws IOException {
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
        return ActionResponse.ok(FlashNotification.success("Account settings saved and applied."));
    }
}
