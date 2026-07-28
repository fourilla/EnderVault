package io.github.fourilla.endervault.settings;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.github.fourilla.endervault.config.LocalPropertiesFile;
import io.github.fourilla.endervault.config.NasProperties;
import io.github.fourilla.endervault.session.SessionManagementService;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;

class SessionSettingsServiceTest {

    @TempDir
    Path tempDir;

    @Test
    void savesSessionSettingsAndAppliesThemToActiveSessions() throws Exception {
        Path configFile = tempDir.resolve("endervault-nas.properties");
        Files.writeString(configFile, """
                nas.setup.accepted=true
                nas.security.max-concurrent-sessions=2
                nas.security.session-idle-timeout-minutes=30
                """, StandardCharsets.UTF_8);
        NasProperties properties = new NasProperties();
        SessionManagementService sessionManagementService = mock(SessionManagementService.class);
        when(sessionManagementService.applyRuntimePolicy()).thenReturn(2);
        SessionSettingsService service = new SessionSettingsService(
                properties,
                new LocalPropertiesFile(configFile),
                sessionManagementService
        );
        MultiValueMap<String, String> parameters = new LinkedMultiValueMap<>();
        parameters.add("maxConcurrentSessions", "4");
        parameters.add("sessionIdleTimeoutMinutes", "120");

        int updated = service.save(service.updateFrom(parameters));

        assertThat(updated).isEqualTo(2);
        assertThat(properties.getSecurity().getMaxConcurrentSessions()).isEqualTo(4);
        assertThat(properties.getSecurity().getSessionIdleTimeoutMinutes()).isEqualTo(120);
        assertThat(Files.readString(configFile, StandardCharsets.UTF_8))
                .contains("nas.security.max-concurrent-sessions=4")
                .contains("nas.security.session-idle-timeout-minutes=120");
        verify(sessionManagementService).applyRuntimePolicy();
    }

    @Test
    void rejectsValuesOutsideTheSupportedRange() {
        SessionSettingsService service = new SessionSettingsService(
                new NasProperties(),
                new LocalPropertiesFile(tempDir.resolve("missing.properties")),
                mock(SessionManagementService.class)
        );
        MultiValueMap<String, String> parameters = new LinkedMultiValueMap<>();
        parameters.add("maxConcurrentSessions", "-1");
        parameters.add("sessionIdleTimeoutMinutes", "30");

        assertThatThrownBy(() -> service.updateFrom(parameters))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Maximum concurrent sessions");
    }
}
