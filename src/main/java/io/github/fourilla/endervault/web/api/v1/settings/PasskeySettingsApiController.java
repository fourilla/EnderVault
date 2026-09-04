package io.github.fourilla.endervault.web.api.v1.settings;

import tools.jackson.databind.ObjectMapper;
import io.github.fourilla.endervault.activity.ActivityLogService;
import io.github.fourilla.endervault.passkey.PasskeyCredential;
import io.github.fourilla.endervault.passkey.PasskeyService;
import io.github.fourilla.endervault.web.auth.PasskeyCredentialPayload;
import io.github.fourilla.endervault.web.support.ActionResponse;
import io.github.fourilla.endervault.web.support.FlashNotification;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import java.util.Map;
import java.util.List;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/settings/passkeys")
public class PasskeySettingsApiController {

    private final PasskeyService passkeyService;
    private final ObjectMapper objectMapper;
    private final ActivityLogService activityLogService;

    public PasskeySettingsApiController(
            PasskeyService passkeyService,
            ObjectMapper objectMapper,
            ActivityLogService activityLogService
    ) {
        this.passkeyService = passkeyService;
        this.objectMapper = objectMapper;
        this.activityLogService = activityLogService;
    }

    @GetMapping
    public PasskeySettingsSnapshot current() {
        List<PasskeyCredentialSummary> credentials = passkeyService.listCredentials().stream()
                .map(PasskeyCredentialSummary::from)
                .toList();
        return new PasskeySettingsSnapshot(
                passkeyService.isEnabled(),
                passkeyService.isPasswordLoginEnabled(),
                passkeyService.rpId(),
                passkeyService.allowedOrigins(),
                credentials
        );
    }

    @PostMapping(value = "/register/options", produces = MediaType.APPLICATION_JSON_VALUE)
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

    @PostMapping(value = "/register/finish", consumes = MediaType.APPLICATION_JSON_VALUE)
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
                    "/admin/settings?section=passkeys"
            ));
        } catch (Exception ex) {
            return ResponseEntity.badRequest().body(ActionResponse.error("Passkey registration failed. Try again."));
        }
    }

    @PostMapping("/{id}/delete")
    public ResponseEntity<ActionResponse> delete(
            @PathVariable String id,
            HttpServletRequest request
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
            return ResponseEntity.ok(ActionResponse.redirect(
                    FlashNotification.success("Passkey deleted."),
                    "/admin/settings?section=passkeys"
            ));
        } catch (Exception ex) {
            return ResponseEntity.badRequest().body(ActionResponse.error("Passkey delete failed."));
        }
    }

    public record PasskeySettingsSnapshot(
            boolean enabled,
            boolean passwordLoginEnabled,
            String rpId,
            java.util.Set<String> allowedOrigins,
            List<PasskeyCredentialSummary> credentials
    ) {
    }

    public record PasskeyCredentialSummary(
            String id,
            String label,
            String credentialId,
            String shortCredentialId,
            String transports,
            boolean backedUp,
            String created,
            String lastUsed
    ) {
        static PasskeyCredentialSummary from(PasskeyCredential credential) {
            return new PasskeyCredentialSummary(
                    credential.id(),
                    credential.displayLabel(),
                    credential.credentialId(),
                    credential.shortCredentialId(),
                    credential.transportLabel(),
                    credential.backedUp(),
                    credential.createdLabel(),
                    credential.lastUsedLabel()
            );
        }
    }
}
