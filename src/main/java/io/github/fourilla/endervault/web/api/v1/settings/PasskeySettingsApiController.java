package io.github.fourilla.endervault.web.api.v1.settings;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.fourilla.endervault.activity.ActivityLogService;
import io.github.fourilla.endervault.passkey.PasskeyCredential;
import io.github.fourilla.endervault.passkey.PasskeyService;
import io.github.fourilla.endervault.web.auth.PasskeyCredentialPayload;
import io.github.fourilla.endervault.web.support.ActionResponse;
import io.github.fourilla.endervault.web.support.FlashNotification;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import java.util.Map;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
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
                    "/admin/settings/passkeys"
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
                    "/admin/settings/passkeys"
            ));
        } catch (Exception ex) {
            return ResponseEntity.badRequest().body(ActionResponse.error("Passkey delete failed."));
        }
    }
}
