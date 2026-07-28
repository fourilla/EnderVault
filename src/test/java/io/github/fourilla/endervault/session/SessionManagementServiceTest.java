package io.github.fourilla.endervault.session;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.fourilla.endervault.auth.ClientIpResolver;
import io.github.fourilla.endervault.config.NasProperties;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.session.SessionInformation;
import org.springframework.security.core.session.SessionRegistryImpl;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetails;

class SessionManagementServiceTest {

    @Test
    void registersMetadataWithoutExposingTheServletSessionId() {
        NasProperties properties = properties(2, 15);
        SessionRegistryImpl registry = new SessionRegistryImpl();
        SessionManagementService service = service(properties, registry);
        UserDetails admin = admin();
        Authentication authentication =
                new UsernamePasswordAuthenticationToken(admin, "secret", admin.getAuthorities());
        MockHttpSession session = new MockHttpSession();
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setSession(session);
        request.setRemoteAddr("203.0.113.10");
        request.addHeader(
                "User-Agent",
                "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 Chrome/126.0 Safari/537.36"
        );
        registry.registerNewSession(session.getId(), admin);

        service.registerAuthenticatedSession(request, authentication, "password");
        List<SessionView> sessions = service.listActive(session.getId());

        assertThat(session.getMaxInactiveInterval()).isEqualTo(900);
        assertThat(sessions).singleElement().satisfies(view -> {
            assertThat(view.managementId()).isNotBlank().isNotEqualTo(session.getId());
            assertThat(view.username()).isEqualTo("admin");
            assertThat(view.ip()).isEqualTo("203.0.113.10");
            assertThat(view.deviceLabel()).isEqualTo("Chrome on Windows");
            assertThat(view.authMethodLabel()).isEqualTo("Password");
            assertThat(view.current()).isTrue();
        });
    }

    @Test
    void revokesARegisteredSessionByOpaqueManagementId() {
        NasProperties properties = properties(2, 30);
        SessionRegistryImpl registry = new SessionRegistryImpl();
        SessionManagementService service = service(properties, registry);
        UserDetails admin = admin();
        MockHttpSession session = new MockHttpSession();
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setSession(session);
        registry.registerNewSession(session.getId(), admin);
        service.registerAuthenticatedSession(
                request,
                new UsernamePasswordAuthenticationToken(admin, "secret", admin.getAuthorities()),
                "password"
        );
        SessionView view = service.listActive("").getFirst();

        SessionManagementService.RevokedSession revoked =
                service.revoke(view.managementId(), "another-session");
        SessionInformation information = registry.getSessionInformation(session.getId());

        assertThat(revoked.current()).isFalse();
        assertThat(information).isNotNull();
        assertThat(information.isExpired()).isTrue();
        assertThat(service.activeCount()).isZero();
    }

    @Test
    void changingTheTimeoutUpdatesExistingManagedSessions() {
        NasProperties properties = properties(2, 30);
        SessionRegistryImpl registry = new SessionRegistryImpl();
        SessionManagementService service = service(properties, registry);
        UserDetails admin = admin();
        MockHttpSession session = new MockHttpSession();
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setSession(session);
        registry.registerNewSession(session.getId(), admin);
        service.registerAuthenticatedSession(
                request,
                new UsernamePasswordAuthenticationToken(admin, "secret", admin.getAuthorities()),
                "password"
        );

        properties.getSecurity().setSessionIdleTimeoutMinutes(5);

        assertThat(service.applyRuntimePolicy()).isEqualTo(1);
        assertThat(session.getMaxInactiveInterval()).isEqualTo(300);
    }

    private SessionManagementService service(NasProperties properties, SessionRegistryImpl registry) {
        return new SessionManagementService(
                registry,
                new SessionPolicyService(properties),
                new ClientIpResolver(properties)
        );
    }

    private NasProperties properties(int maxSessions, int timeoutMinutes) {
        NasProperties properties = new NasProperties();
        properties.getSecurity().setMaxConcurrentSessions(maxSessions);
        properties.getSecurity().setSessionIdleTimeoutMinutes(timeoutMinutes);
        return properties;
    }

    private UserDetails admin() {
        return User.withUsername("admin")
                .password("{noop}secret")
                .roles("ADMIN")
                .build();
    }
}
