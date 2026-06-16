package io.github.fourilla.endervault.web.auth;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.fourilla.endervault.activity.ActivityLogService;
import io.github.fourilla.endervault.auth.ClientIpResolver;
import io.github.fourilla.endervault.passkey.PasskeyLoginResult;
import io.github.fourilla.endervault.passkey.PasskeyService;
import io.github.fourilla.endervault.web.support.ActionResponse;
import io.github.fourilla.endervault.web.support.FlashNotification;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import java.util.Map;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.web.authentication.WebAuthenticationDetailsSource;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class PasskeyLoginController {

    private final PasskeyService passkeyService;
    private final ObjectMapper objectMapper;
    private final UserDetailsService userDetailsService;
    private final ActivityLogService activityLogService;

    public PasskeyLoginController(
            PasskeyService passkeyService,
            ObjectMapper objectMapper,
            UserDetailsService userDetailsService,
            ActivityLogService activityLogService
    ) {
        this.passkeyService = passkeyService;
        this.objectMapper = objectMapper;
        this.userDetailsService = userDetailsService;
        this.activityLogService = activityLogService;
    }

    @PostMapping(value = "/login/passkey/options", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<String> start(HttpSession session) {
        try {
            return ResponseEntity.ok()
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(passkeyService.startLogin(session));
        } catch (RuntimeException ex) {
            return jsonError(ex.getMessage());
        }
    }

    @PostMapping(value = "/login/passkey/finish", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<ActionResponse> finish(
            @RequestBody PasskeyCredentialPayload payload,
            HttpSession session,
            HttpServletRequest request
    ) {
        try {
            PasskeyLoginResult result = passkeyService.finishLogin(
                    session,
                    objectMapper.writeValueAsString(payload.credential())
            );
            authenticate(result.username(), request);
            activityLogService.record(
                    "LOGIN_SUCCESS",
                    result.username(),
                    ClientIpResolver.resolve(request),
                    null,
                    null,
                    true,
                    "Passkey login succeeded.",
                    Map.of(
                            "authMethod", "passkey",
                            "credentialId", result.credentialId(),
                            "userVerified", Boolean.toString(result.userVerified()),
                            "backedUp", Boolean.toString(result.backedUp())
                    )
            );
            return ResponseEntity.ok(ActionResponse.redirect(
                    FlashNotification.success("Passkey login succeeded."),
                    "/files"
            ));
        } catch (Exception ex) {
            activityLogService.record(
                    "LOGIN_FAILURE",
                    "unknown",
                    ClientIpResolver.resolve(request),
                    null,
                    null,
                    false,
                    "Passkey login failed.",
                    Map.of("authMethod", "passkey", "reason", ex.getMessage())
            );
            return ResponseEntity.badRequest().body(ActionResponse.error("Passkey login failed. Try again."));
        }
    }

    private void authenticate(String username, HttpServletRequest request) {
        UserDetails userDetails = userDetailsService.loadUserByUsername(username);
        UsernamePasswordAuthenticationToken authentication =
                new UsernamePasswordAuthenticationToken(userDetails, "passkey", userDetails.getAuthorities());
        authentication.setDetails(new WebAuthenticationDetailsSource().buildDetails(request));

        SecurityContext context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(authentication);
        SecurityContextHolder.setContext(context);
        request.getSession(true).setAttribute(
                HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY,
                context
        );
    }

    private ResponseEntity<String> jsonError(String message) {
        try {
            return ResponseEntity.badRequest()
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(objectMapper.writeValueAsString(ActionResponse.error(message)));
        } catch (JsonProcessingException ex) {
            return ResponseEntity.badRequest()
                    .contentType(MediaType.APPLICATION_JSON)
                    .body("{\"ok\":false}");
        }
    }
}
