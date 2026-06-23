package io.github.fourilla.endervault.notification;

import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.startsWith;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import io.github.fourilla.endervault.activity.ActivityLogEntry;
import io.github.fourilla.endervault.config.NasProperties;
import java.time.Instant;
import java.util.Map;
import org.junit.jupiter.api.Test;

class TelegramActivityLogNotifierTest {

    @Test
    void sendsConfiguredActivityType() {
        NasProperties properties = properties(true);
        TelegramNotificationService telegram = mock(TelegramNotificationService.class);
        TelegramActivityLogNotifier notifier = new TelegramActivityLogNotifier(properties, telegram);

        try {
            notifier.notify(entry("LOGIN_SUCCESS"));

            verify(telegram, timeout(1000)).send(contains("type: LOGIN_SUCCESS"));
        } finally {
            notifier.shutdown();
        }
    }

    @Test
    void ignoresUnconfiguredActivityType() {
        NasProperties properties = properties(true);
        TelegramNotificationService telegram = mock(TelegramNotificationService.class);
        TelegramActivityLogNotifier notifier = new TelegramActivityLogNotifier(properties, telegram);

        try {
            notifier.notify(entry("UPLOAD"));

            verifyNoInteractions(telegram);
        } finally {
            notifier.shutdown();
        }
    }

    @Test
    void sendsExplicitlyEnabledActivityType() {
        NasProperties properties = properties(true);
        properties.getNotifications().getTelegram().getActivity().put("upload", true);
        TelegramNotificationService telegram = mock(TelegramNotificationService.class);
        TelegramActivityLogNotifier notifier = new TelegramActivityLogNotifier(properties, telegram);

        try {
            notifier.notify(entry("UPLOAD"));

            verify(telegram, timeout(1000)).send(startsWith("EnderVault activity"));
        } finally {
            notifier.shutdown();
        }
    }

    @Test
    void redactsSensitiveMetadataBeforeSendingToTelegram() {
        NasProperties properties = properties(true);
        properties.getNotifications().getTelegram().getActivity().put("passkey-register", true);
        TelegramNotificationService telegram = mock(TelegramNotificationService.class);
        TelegramActivityLogNotifier notifier = new TelegramActivityLogNotifier(properties, telegram);

        try {
            notifier.notify(new ActivityLogEntry(
                    "id",
                    Instant.parse("2026-06-17T00:00:00Z"),
                    "PASSKEY_REGISTER",
                    "admin",
                    "127.0.0.1",
                    null,
                    null,
                    true,
                    "Passkey registered.",
                    Map.of("credentialId", "credential-secret-value", "label", "laptop")
            ));

            verify(telegram, timeout(1000)).send(argThat(message ->
                    message.contains("credentialId=cred...alue [redacted]")
                            && message.contains("label=laptop")
                            && !message.contains("credential-secret-value")
            ));
        } finally {
            notifier.shutdown();
        }
    }

    private NasProperties properties(boolean enabled) {
        NasProperties properties = new NasProperties();
        properties.getNotifications().getTelegram().setEnabled(enabled);
        return properties;
    }

    private ActivityLogEntry entry(String type) {
        return new ActivityLogEntry(
                "id",
                Instant.parse("2026-06-17T00:00:00Z"),
                type,
                "admin",
                "127.0.0.1",
                "path.txt",
                null,
                true,
                "Test activity",
                Map.of("authMethod", "password")
        );
    }
}
