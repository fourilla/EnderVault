package io.github.fourilla.endervault.web.auth;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.fourilla.endervault.activity.ActivityLogService;
import io.github.fourilla.endervault.passkey.PasskeyCredential;
import io.github.fourilla.endervault.passkey.PasskeyService;
import io.github.fourilla.endervault.web.support.ActionResponse;
import io.github.fourilla.endervault.web.support.FlashNotification;
import io.github.fourilla.endervault.web.support.FlashNotifications;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import java.util.Map;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

@Controller
public class AdminPasskeyController {

    private final PasskeyService passkeyService;
    private final ObjectMapper objectMapper;
    private final ActivityLogService activityLogService;

    public AdminPasskeyController(
            PasskeyService passkeyService,
            ObjectMapper objectMapper,
            ActivityLogService activityLogService
    ) {
        this.passkeyService = passkeyService;
        this.objectMapper = objectMapper;
        this.activityLogService = activityLogService;
    }

    @GetMapping("/admin/settings/passkeys")
    public String page(Model model) {
        model.addAttribute("passkeys", passkeyService.listCredentials());
        model.addAttribute("passkeysEnabled", passkeyService.isEnabled());
        model.addAttribute("passwordLoginEnabled", passkeyService.isPasswordLoginEnabled());
        model.addAttribute("rpId", passkeyService.rpId());
        model.addAttribute("allowedOrigins", passkeyService.allowedOrigins());
        return "passkeys";
    }

    @PostMapping(value = "/admin/settings/passkeys/register/options", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<String> startRegistration(HttpSession session) {
        try {
            return ResponseEntity.ok()
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(passkeyService.startRegistration(session));
        } catch (RuntimeException ex) {
            return ResponseEntity.badRequest()
                    .contentType(MediaType.APPLICATION_JSON)
                    .body("{\"ok\":false}");
        }
    }

    @PostMapping(value = "/admin/settings/passkeys/register/finish", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<ActionResponse> finishRegistration(
            @RequestBody PasskeyCredentialPayload payload,
            HttpSession session,
            HttpServletRequest request
    ) {
        try {
            PasskeyCredential credential = passkeyService.finishRegistration(
                    session,
                    objectMapper.writeValueAsString(payload.credential()),
                    payload.label()
            );
            activityLogService.record(
                    "PASSKEY_REGISTER",
                    request,
                    null,
                    null,
                    "Passkey registered.",
                    Map.of("label", credential.displayLabel(), "credentialId", credential.credentialId())
            );
            return ResponseEntity.ok(ActionResponse.redirect(
                    FlashNotification.success("Passkey registered."),
                    "/admin/settings/passkeys"
            ));
        } catch (Exception ex) {
            return ResponseEntity.badRequest().body(ActionResponse.error("Passkey registration failed. Try again."));
        }
    }

    @PostMapping("/admin/settings/passkeys/{id}/delete")
    public String delete(
            @PathVariable String id,
            HttpServletRequest request,
            RedirectAttributes redirectAttributes
    ) {
        try {
            PasskeyCredential credential = passkeyService.deleteCredential(id);
            activityLogService.record(
                    "PASSKEY_DELETE",
                    request,
                    null,
                    null,
                    "Passkey deleted.",
                    Map.of("label", credential.displayLabel(), "credentialId", credential.credentialId())
            );
            FlashNotifications.success(redirectAttributes, "Passkey deleted.");
        } catch (Exception ex) {
            FlashNotifications.error(redirectAttributes, "Passkey delete failed.");
        }
        return "redirect:/admin/settings/passkeys";
    }
}
