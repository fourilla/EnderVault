package io.github.fourilla.endervault.session;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.fourilla.endervault.config.NasProperties;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.session.SessionInformation;
import org.springframework.security.core.session.SessionRegistryImpl;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetails;

class DynamicConcurrentSessionStrategyTest {

    @Test
    void expiresTheOldestSessionAtTheConfiguredLimit() {
        NasProperties properties = new NasProperties();
        properties.getSecurity().setMaxConcurrentSessions(1);
        SessionRegistryImpl registry = new SessionRegistryImpl();
        DynamicConcurrentSessionStrategy strategy =
                new DynamicConcurrentSessionStrategy(registry, new SessionPolicyService(properties));
        UserDetails admin = admin();
        MockHttpSession existingSession = new MockHttpSession();
        registry.registerNewSession(existingSession.getId(), admin);
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setSession(new MockHttpSession());

        strategy.onAuthentication(authentication(admin), request, new MockHttpServletResponse());

        SessionInformation existing = registry.getSessionInformation(existingSession.getId());
        assertThat(existing).isNotNull();
        assertThat(existing.isExpired()).isTrue();
    }

    @Test
    void zeroAllowsUnlimitedConcurrentSessions() {
        NasProperties properties = new NasProperties();
        properties.getSecurity().setMaxConcurrentSessions(0);
        SessionRegistryImpl registry = new SessionRegistryImpl();
        DynamicConcurrentSessionStrategy strategy =
                new DynamicConcurrentSessionStrategy(registry, new SessionPolicyService(properties));
        UserDetails admin = admin();
        MockHttpSession existingSession = new MockHttpSession();
        registry.registerNewSession(existingSession.getId(), admin);
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setSession(new MockHttpSession());

        strategy.onAuthentication(authentication(admin), request, new MockHttpServletResponse());

        assertThat(registry.getSessionInformation(existingSession.getId()).isExpired()).isFalse();
    }

    @Test
    void appliesTheSingleAdminLimitAcrossAUsernameChange() {
        NasProperties properties = new NasProperties();
        properties.getSecurity().setMaxConcurrentSessions(1);
        SessionRegistryImpl registry = new SessionRegistryImpl();
        DynamicConcurrentSessionStrategy strategy =
                new DynamicConcurrentSessionStrategy(registry, new SessionPolicyService(properties));
        UserDetails previousAdmin = User.withUsername("previous-admin")
                .password("{noop}secret")
                .roles("ADMIN")
                .build();
        MockHttpSession existingSession = new MockHttpSession();
        registry.registerNewSession(existingSession.getId(), previousAdmin);
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setSession(new MockHttpSession());

        strategy.onAuthentication(authentication(admin()), request, new MockHttpServletResponse());

        assertThat(registry.getSessionInformation(existingSession.getId()).isExpired()).isTrue();
    }

    private Authentication authentication(UserDetails admin) {
        return new UsernamePasswordAuthenticationToken(admin, "secret", admin.getAuthorities());
    }

    private UserDetails admin() {
        return User.withUsername("admin")
                .password("{noop}secret")
                .roles("ADMIN")
                .build();
    }
}
