package io.github.fourilla.endervault.web.auth;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;
import io.github.fourilla.endervault.activity.ActivityLogService;
import io.github.fourilla.endervault.auth.ClientIpResolver;
import io.github.fourilla.endervault.passkey.PasskeyLoginResult;
import io.github.fourilla.endervault.passkey.PasskeyService;
import io.github.fourilla.endervault.web.support.ActionResponse;
import io.github.fourilla.endervault.web.support.FlashNotification;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
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
import org.springframework.security.web.authentication.session.SessionAuthenticationStrategy;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class PasskeyLoginController {

    private final PasskeyService passkeyService;
    private final ObjectMapper objectMapper;
    private final UserDetailsService userDetailsService;
    private final ActivityLogService activityLogService;
    private final ClientIpResolver clientIpResolver;
    private final SecurityContextRepository securityContextRepository;
    private final SessionAuthenticationStrategy sessionAuthenticationStrategy;

    public PasskeyLoginController(
            PasskeyService passkeyService,
            ObjectMapper objectMapper,
            UserDetailsService userDetailsService,
            ActivityLogService activityLogService,
            ClientIpResolver clientIpResolver,
            SecurityContextRepository securityContextRepository,
            SessionAuthenticationStrategy sessionAuthenticationStrategy
    ) {
        this.passkeyService = passkeyService;
        this.objectMapper = objectMapper;
        this.userDetailsService = userDetailsService;
        this.activityLogService = activityLogService;
        this.clientIpResolver = clientIpResolver;
        this.securityContextRepository = securityContextRepository;
        this.sessionAuthenticationStrategy = sessionAuthenticationStrategy;
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
            HttpServletRequest request,
            HttpServletResponse response
    ) {
        try {
            PasskeyLoginResult result = passkeyService.finishLogin(
                    session,
                    objectMapper.writeValueAsString(payload.credential())
            );
            authenticate(result.username(), request, response);
            activityLogService.record(
                    "LOGIN_SUCCESS",
                    result.username(),
                    clientIpResolver.resolve(request),
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
                    clientIpResolver.resolve(request),
                    null,
                    null,
                    false,
                    "Passkey login failed.",
                    Map.of("authMethod", "passkey", "reason", cleanReason(ex))
            );
            return ResponseEntity.badRequest().body(ActionResponse.error("Passkey login failed. Try again."));
        }
    }

    private void authenticate(String username, HttpServletRequest request, HttpServletResponse response) {
        UserDetails userDetails = userDetailsService.loadUserByUsername(username);
        UsernamePasswordAuthenticationToken authentication =
                new UsernamePasswordAuthenticationToken(userDetails, "passkey", userDetails.getAuthorities());
        authentication.setDetails(new WebAuthenticationDetailsSource().buildDetails(request));

        sessionAuthenticationStrategy.onAuthentication(authentication, request, response);

        SecurityContext context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(authentication);
        SecurityContextHolder.setContext(context);
        securityContextRepository.saveContext(context, request, response);
    }

    private ResponseEntity<String> jsonError(String message) {
        try {
            return ResponseEntity.badRequest()
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(objectMapper.writeValueAsString(ActionResponse.error(message)));
        } catch (JacksonException ex) {
            return ResponseEntity.badRequest()
                    .contentType(MediaType.APPLICATION_JSON)
                    .body("{\"ok\":false}");
        }
    }

    private String cleanReason(Exception ex) {
        String message = ex.getMessage();
        return message == null || message.isBlank() ? ex.getClass().getSimpleName() : message;
    }
}
