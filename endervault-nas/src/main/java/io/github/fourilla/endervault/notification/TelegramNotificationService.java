package io.github.fourilla.endervault.notification;

import io.github.fourilla.endervault.config.NasProperties;
import java.net.URI;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.web.client.RestTemplateBuilder;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.util.UriComponentsBuilder;

@Service
public class TelegramNotificationService {

    private static final Logger log = LoggerFactory.getLogger(TelegramNotificationService.class);

    private final NasProperties nasProperties;
    private final RestTemplate restTemplate;

    public TelegramNotificationService(NasProperties nasProperties, RestTemplateBuilder restTemplateBuilder) {
        this.nasProperties = nasProperties;
        this.restTemplate = restTemplateBuilder.build();
    }

    public void send(String message) {
        NasProperties.Telegram telegram = nasProperties.getNotifications().getTelegram();
        if (!telegram.isEnabled()) {
            return;
        }
        if (telegram.getBotToken().isBlank() || telegram.getChatId().isBlank()) {
            log.warn("Telegram notification is enabled, but bot token or chat id is blank.");
            return;
        }

        URI uri = UriComponentsBuilder
                .fromUriString("https://api.telegram.org/bot" + telegram.getBotToken() + "/sendMessage")
                .queryParam("chat_id", telegram.getChatId())
                .queryParam("text", message)
                .build()
                .toUri();

        try {
            restTemplate.getForEntity(uri, String.class);
        } catch (RestClientException ex) {
            log.warn("Failed to send Telegram notification.", ex);
        }
    }
}

