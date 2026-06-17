package io.github.fourilla.endervault.notification;

import io.github.fourilla.endervault.config.NasProperties;
import java.net.URI;
import java.time.Duration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.web.client.RestTemplateBuilder;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestTemplate;

@Service
public class TelegramNotificationService {

    private static final Logger log = LoggerFactory.getLogger(TelegramNotificationService.class);

    private final NasProperties nasProperties;
    private final RestTemplate restTemplate;

    public TelegramNotificationService(NasProperties nasProperties, RestTemplateBuilder restTemplateBuilder) {
        this.nasProperties = nasProperties;
        this.restTemplate = restTemplateBuilder
                .setConnectTimeout(Duration.ofSeconds(5))
                .setReadTimeout(Duration.ofSeconds(10))
                .build();
    }

    public void send(String message) {
        NasProperties.Telegram telegram = nasProperties.getNotifications().getTelegram();
        if (!telegram.isEnabled()) {
            return;
        }
        String botToken = telegram.getBotToken().trim();
        String chatId = telegram.getChatId().trim();
        if (botToken.isBlank() || chatId.isBlank()) {
            log.warn("Telegram notification is enabled, but bot token or chat id is blank.");
            return;
        }

        URI uri = URI.create("https://api.telegram.org/bot" + botToken + "/sendMessage");
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_FORM_URLENCODED);
        MultiValueMap<String, String> body = new LinkedMultiValueMap<>();
        body.add("chat_id", chatId);
        body.add("text", message);

        try {
            restTemplate.postForEntity(uri, new HttpEntity<>(body, headers), String.class);
        } catch (RestClientException ex) {
            log.warn("Failed to send Telegram notification: {}", ex.getClass().getSimpleName());
        }
    }
}
