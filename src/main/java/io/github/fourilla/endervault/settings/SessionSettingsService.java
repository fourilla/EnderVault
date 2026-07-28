package io.github.fourilla.endervault.settings;

import io.github.fourilla.endervault.config.LocalPropertiesFile;
import io.github.fourilla.endervault.config.NasProperties;
import io.github.fourilla.endervault.session.SessionManagementService;
import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.stereotype.Service;
import org.springframework.util.MultiValueMap;

@Service
public class SessionSettingsService {

    private static final int MAX_CONCURRENT_SESSIONS = 1000;
    private static final int MAX_IDLE_TIMEOUT_MINUTES = 525600;

    private final NasProperties nasProperties;
    private final LocalPropertiesFile localPropertiesFile;
    private final SessionManagementService sessionManagementService;

    public SessionSettingsService(
            NasProperties nasProperties,
            LocalPropertiesFile localPropertiesFile,
            SessionManagementService sessionManagementService
    ) {
        this.nasProperties = nasProperties;
        this.localPropertiesFile = localPropertiesFile;
        this.sessionManagementService = sessionManagementService;
    }

    public SessionSettingsSnapshot currentSettings() {
        NasProperties.Security security = nasProperties.getSecurity();
        return new SessionSettingsSnapshot(
                security.getMaxConcurrentSessions(),
                security.getSessionIdleTimeoutMinutes(),
                sessionManagementService.activeCount(),
                localPropertiesFile.configFile().toString()
        );
    }

    public SessionSettingsUpdate updateFrom(MultiValueMap<String, String> parameters) {
        return new SessionSettingsUpdate(
                intRange(
                        first(parameters, "maxConcurrentSessions"),
                        0,
                        MAX_CONCURRENT_SESSIONS,
                        "Maximum concurrent sessions"
                ),
                intRange(
                        first(parameters, "sessionIdleTimeoutMinutes"),
                        0,
                        MAX_IDLE_TIMEOUT_MINUTES,
                        "Session idle timeout"
                )
        );
    }

    public int save(SessionSettingsUpdate update) throws IOException {
        Map<String, String> updates = new LinkedHashMap<>();
        updates.put("nas.security.max-concurrent-sessions", Integer.toString(update.maxConcurrentSessions()));
        updates.put("nas.security.session-idle-timeout-minutes", Integer.toString(update.sessionIdleTimeoutMinutes()));
        localPropertiesFile.update(updates, "# Session settings managed from EnderVault Settings.");

        NasProperties.Security security = nasProperties.getSecurity();
        security.setMaxConcurrentSessions(update.maxConcurrentSessions());
        security.setSessionIdleTimeoutMinutes(update.sessionIdleTimeoutMinutes());
        return sessionManagementService.applyRuntimePolicy();
    }

    private static int intRange(String rawValue, int min, int max, String label) {
        try {
            int value = Integer.parseInt(clean(rawValue));
            if (value < min || value > max) {
                throw new IllegalArgumentException(label + " must be between " + min + " and " + max + ".");
            }
            return value;
        } catch (NumberFormatException ex) {
            throw new IllegalArgumentException(label + " must be a number.");
        }
    }

    private static String first(MultiValueMap<String, String> parameters, String key) {
        String value = parameters.getFirst(key);
        return value == null ? "" : value;
    }

    private static String clean(String value) {
        return value == null ? "" : value.replace("\r", "").replace("\n", "").trim();
    }

    public record SessionSettingsSnapshot(
            int maxConcurrentSessions,
            int sessionIdleTimeoutMinutes,
            int activeSessions,
            String configPath
    ) {
        public String maxConcurrentLabel() {
            return maxConcurrentSessions == 0 ? "Unlimited" : Integer.toString(maxConcurrentSessions);
        }

        public String idleTimeoutLabel() {
            return sessionIdleTimeoutMinutes == 0 ? "Unlimited" : sessionIdleTimeoutMinutes + " minutes";
        }
    }

    public record SessionSettingsUpdate(
            int maxConcurrentSessions,
            int sessionIdleTimeoutMinutes
    ) {
    }
}
