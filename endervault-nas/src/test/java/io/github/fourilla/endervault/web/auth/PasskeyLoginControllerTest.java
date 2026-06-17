package io.github.fourilla.endervault.web.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.same;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import io.github.fourilla.endervault.activity.ActivityLogService;
import io.github.fourilla.endervault.passkey.PasskeyLoginResult;
import io.github.fourilla.endervault.passkey.PasskeyService;
import io.github.fourilla.endervault.web.support.ActionResponse;
import jakarta.servlet.http.HttpSession;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.web.authentication.session.SessionAuthenticationStrategy;
import org.springframework.security.web.context.SecurityContextRepository;

class PasskeyLoginControllerTest {

    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void successfulPasskeyLoginUsesSpringSecuritySessionInfrastructure() throws Exception {
        PasskeyService passkeyService = mock(PasskeyService.class);
        UserDetailsService userDetailsService = mock(UserDetailsService.class);
        ActivityLogService activityLogService = mock(ActivityLogService.class);
        SecurityContextRepository securityContextRepository = mock(SecurityContextRepository.class);
        SessionAuthenticationStrategy sessionAuthenticationStrategy = mock(SessionAuthenticationStrategy.class);
        ObjectMapper objectMapper = JsonMapper.builder().findAndAddModules().build();

        when(passkeyService.finishLogin(any(HttpSession.class), anyString()))
                .thenReturn(new PasskeyLoginResult("admin", "credential-1", true, false));
        UserDetails admin = User.withUsername("admin")
                .password("{noop}unused")
                .roles("ADMIN")
                .build();
        when(userDetailsService.loadUserByUsername("admin")).thenReturn(admin);

        PasskeyLoginController controller = new PasskeyLoginController(
                passkeyService,
                objectMapper,
                userDetailsService,
                activityLogService,
                securityContextRepository,
                sessionAuthenticationStrategy
        );

        MockHttpSession session = new MockHttpSession();
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/login/passkey/finish");
        request.setSession(session);
        request.setRemoteAddr("127.0.0.1");
        MockHttpServletResponse response = new MockHttpServletResponse();
        PasskeyCredentialPayload payload =
                new PasskeyCredentialPayload(null, objectMapper.createObjectNode());

        ResponseEntity<ActionResponse> result = controller.finish(payload, session, request, response);

        assertThat(result.getStatusCode().is2xxSuccessful()).isTrue();
        assertThat(result.getBody()).isNotNull();
        assertThat(result.getBody().redirectUrl()).isEqualTo("/files");

        ArgumentCaptor<Authentication> authenticationCaptor =
                ArgumentCaptor.forClass(Authentication.class);
        verify(sessionAuthenticationStrategy).onAuthentication(
                authenticationCaptor.capture(),
                same(request),
                same(response)
        );
        Authentication authentication = authenticationCaptor.getValue();
        assertThat(authentication)
                .isInstanceOf(UsernamePasswordAuthenticationToken.class)
                .extracting(Authentication::getName)
                .isEqualTo("admin");
        assertThat(authentication.getAuthorities())
                .extracting(Object::toString)
                .contains("ROLE_ADMIN");

        ArgumentCaptor<SecurityContext> contextCaptor = ArgumentCaptor.forClass(SecurityContext.class);
        verify(securityContextRepository).saveContext(
                contextCaptor.capture(),
                same(request),
                same(response)
        );
        assertThat(contextCaptor.getValue().getAuthentication()).isSameAs(authentication);
    }
}
