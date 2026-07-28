package io.github.fourilla.endervault.session;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.fourilla.endervault.config.NasProperties;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpSession;

class SessionPolicyServiceTest {

    @Test
    void mapsUnlimitedValuesToSpringAndServletConventions() {
        NasProperties properties = new NasProperties();
        properties.getSecurity().setMaxConcurrentSessions(0);
        properties.getSecurity().setSessionIdleTimeoutMinutes(0);
        SessionPolicyService service = new SessionPolicyService(properties);
        MockHttpSession session = new MockHttpSession();

        service.applyIdleTimeout(session);

        assertThat(service.maximumSessionsForSecurity()).isEqualTo(-1);
        assertThat(service.idleTimeoutUnlimited()).isTrue();
        assertThat(session.getMaxInactiveInterval()).isEqualTo(-1);
    }

    @Test
    void appliesConfiguredIdleTimeoutInSeconds() {
        NasProperties properties = new NasProperties();
        properties.getSecurity().setMaxConcurrentSessions(3);
        properties.getSecurity().setSessionIdleTimeoutMinutes(45);
        SessionPolicyService service = new SessionPolicyService(properties);
        MockHttpSession session = new MockHttpSession();

        service.applyIdleTimeout(session);

        assertThat(service.maximumSessionsForSecurity()).isEqualTo(3);
        assertThat(session.getMaxInactiveInterval()).isEqualTo(2700);
    }
}
