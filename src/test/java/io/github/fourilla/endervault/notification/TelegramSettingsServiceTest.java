package io.github.fourilla.endervault.notification;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import io.github.fourilla.endervault.config.NasProperties;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;

class TelegramSettingsServiceTest {

    @TempDir
    Path tempDir;

    @Test
    void savesTelegramSettingsToLocalConfigAndRuntimeProperties() throws Exception {
        Path configFile = tempDir.resolve("endervault-nas.properties");
        Files.writeString(configFile, """
                nas.setup.accepted=true
                nas.notifications.telegram.enabled=false
                nas.notifications.telegram.bot-token=
                nas.notifications.telegram.chat-id=
                nas.notifications.telegram.activity.login-success=true
                nas.notifications.telegram.activity.upload=false
                """, StandardCharsets.UTF_8);

        NasProperties properties = new NasProperties();
        TelegramSettingsService service = new TelegramSettingsService(
                properties,
                mock(TelegramNotificationService.class),
                configFile
        );

        MultiValueMap<String, String> parameters = new LinkedMultiValueMap<>();
        parameters.add("enabled", "true");
        parameters.add("botToken", "123:abc");
        parameters.add("chatId", "-100123");
        parameters.add("activityKeys", "login-success");
        parameters.add("activityKeys", "upload");
        parameters.add("activity.upload", "true");

        service.save(service.updateFrom(parameters));

        String savedConfig = Files.readString(configFile, StandardCharsets.UTF_8);
        assertThat(savedConfig)
                .contains("nas.notifications.telegram.enabled=true")
                .contains("nas.notifications.telegram.bot-token=123:abc")
                .contains("nas.notifications.telegram.chat-id=-100123")
                .contains("nas.notifications.telegram.activity.login-success=false")
                .contains("nas.notifications.telegram.activity.upload=true");

        NasProperties.Telegram telegram = properties.getNotifications().getTelegram();
        assertThat(telegram.isEnabled()).isTrue();
        assertThat(telegram.getBotToken()).isEqualTo("123:abc");
        assertThat(telegram.getChatId()).isEqualTo("-100123");
        assertThat(telegram.isActivityEnabled("LOGIN_SUCCESS")).isFalse();
        assertThat(telegram.isActivityEnabled("UPLOAD")).isTrue();
    }
}
