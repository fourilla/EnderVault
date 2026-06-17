package io.github.fourilla.endervault.notification;

import io.github.fourilla.endervault.activity.ActivityLogEntry;
import io.github.fourilla.endervault.activity.ActivityLogNotifier;
import io.github.fourilla.endervault.config.NasProperties;
import jakarta.annotation.PreDestroy;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

@Service
public class TelegramActivityLogNotifier implements ActivityLogNotifier {

    private static final Logger logger = LoggerFactory.getLogger(TelegramActivityLogNotifier.class);
    private static final int MAX_MESSAGE_LENGTH = 3500;
    private static final int MAX_METADATA_VALUE_LENGTH = 120;
    private static final Set<String> SENSITIVE_METADATA_KEYS = Set.of(
            "credentialid",
            "token",
            "url",
            "bottoken",
            "chatid",
            "sourceurl"
    );

    private final NasProperties nasProperties;
    private final TelegramNotificationService telegramNotificationService;
    private final ExecutorService executorService;

    public TelegramActivityLogNotifier(
            NasProperties nasProperties,
            TelegramNotificationService telegramNotificationService
    ) {
        this.nasProperties = nasProperties;
        this.telegramNotificationService = telegramNotificationService;
        this.executorService = Executors.newSingleThreadExecutor(telegramThreadFactory());
    }

    @Override
    public void notify(ActivityLogEntry entry) {
        if (!shouldNotify(entry)) {
            return;
        }

        executorService.submit(() -> {
            try {
                telegramNotificationService.send(messageFor(entry));
            } catch (RuntimeException ex) {
                logger.warn("Failed to send Telegram activity notification.", ex);
            }
        });
    }

    @PreDestroy
    public void shutdown() {
        executorService.shutdownNow();
    }

    private boolean shouldNotify(ActivityLogEntry entry) {
        if (entry == null) {
            return false;
        }
        NasProperties.Telegram telegram = nasProperties.getNotifications().getTelegram();
        if (telegram == null || !telegram.isEnabled()) {
            return false;
        }
        return telegram.isActivityEnabled(entry.safeType());
    }

    private String messageFor(ActivityLogEntry entry) {
        StringBuilder message = new StringBuilder()
                .append("EnderVault activity\n")
                .append("type: ").append(entry.safeType()).append('\n')
                .append("status: ").append(entry.statusLabel()).append('\n')
                .append("time: ").append(entry.timestampLabel()).append('\n')
                .append("actor: ").append(entry.actorLabel()).append('\n')
                .append("ip: ").append(entry.ipLabel()).append('\n');
        appendIfPresent(message, "path", entry.pathLabel());
        appendIfPresent(message, "target", entry.targetPathLabel());
        appendIfPresent(message, "message", safeMessageLabel(entry));
        if (entry.hasMetadata()) {
            appendIfPresent(message, "metadata", safeMetadataLabel(entry));
        }
        return truncate(message.toString());
    }

    private String safeMessageLabel(ActivityLogEntry entry) {
        String message = entry.messageLabel();
        if (message == null || message.isBlank() || "-".equals(message)) {
            return message;
        }
        if (entry.safeType().startsWith("SHARE_")) {
            return message.replaceAll("(?i)(share link)\\s+\\S+", "$1 [redacted]");
        }
        return message;
    }

    private String safeMetadataLabel(ActivityLogEntry entry) {
        return entry.metadataView().entrySet().stream()
                .map(metadata -> metadata.getKey() + "="
                        + safeMetadataValue(metadata.getKey(), metadata.getValue()))
                .collect(Collectors.joining(", "));
    }

    private String safeMetadataValue(String key, String value) {
        if (value == null || value.isBlank()) {
            return "";
        }
        if (SENSITIVE_METADATA_KEYS.contains(normalizeMetadataKey(key))) {
            return redactedValue(value);
        }
        if (value.length() > MAX_METADATA_VALUE_LENGTH) {
            return value.substring(0, MAX_METADATA_VALUE_LENGTH - 13) + "... truncated";
        }
        return value;
    }

    private String redactedValue(String value) {
        if (value.length() <= 8) {
            return "[redacted]";
        }
        return value.substring(0, 4) + "..." + value.substring(value.length() - 4) + " [redacted]";
    }

    private String normalizeMetadataKey(String key) {
        return key == null
                ? ""
                : key.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]", "");
    }

    private void appendIfPresent(StringBuilder message, String label, String value) {
        if (value == null || value.isBlank() || "-".equals(value)) {
            return;
        }
        message.append(label).append(": ").append(value).append('\n');
    }

    private String truncate(String message) {
        if (message.length() <= MAX_MESSAGE_LENGTH) {
            return message;
        }
        return message.substring(0, MAX_MESSAGE_LENGTH - 15) + "\n... truncated";
    }

    private ThreadFactory telegramThreadFactory() {
        return runnable -> {
            Thread thread = new Thread(runnable, "endervault-telegram-activity");
            thread.setDaemon(true);
            return thread;
        };
    }
}
