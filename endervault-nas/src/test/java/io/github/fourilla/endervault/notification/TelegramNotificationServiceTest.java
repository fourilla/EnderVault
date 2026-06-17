package io.github.fourilla.endervault.notification;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.fourilla.endervault.config.NasProperties;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.boot.web.client.RestTemplateBuilder;
import org.springframework.web.client.ResourceAccessException;

@ExtendWith(OutputCaptureExtension.class)
class TelegramNotificationServiceTest {

    @Test
    void failureLogsDoNotExposeBotToken(CapturedOutput output) {
        String botToken = "123456:secret-token";
        TelegramNotificationService service = new TelegramNotificationService(
                new NasProperties(),
                new RestTemplateBuilder()
                        .additionalCustomizers(restTemplate -> restTemplate.setRequestFactory((uri, method) -> {
                            throw new ResourceAccessException("Failed to reach " + uri);
                        }))
        );

        boolean sent = service.send(botToken, "-100123456", "test");

        assertThat(sent).isFalse();
        assertThat(output)
                .contains("Failed to send Telegram notification")
                .contains("ResourceAccessException")
                .doesNotContain(botToken)
                .doesNotContain("bot" + botToken)
                .doesNotContain("api.telegram.org/bot");
    }
}
