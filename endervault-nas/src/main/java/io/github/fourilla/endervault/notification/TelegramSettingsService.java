package io.github.fourilla.endervault.notification;

import io.github.fourilla.endervault.activity.ActivityTypeCatalog;
import io.github.fourilla.endervault.activity.ActivityTypeCatalog.ActivityTypeOption;
import io.github.fourilla.endervault.config.LocalPropertiesFile;
import io.github.fourilla.endervault.config.NasProperties;
import java.io.IOException;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.util.MultiValueMap;
import org.springframework.util.StringUtils;

@Service
public class TelegramSettingsService {

    private static final String PREFIX = "nas.notifications.telegram.";
    private static final String ENABLED_KEY = PREFIX + "enabled";
    private static final String BOT_TOKEN_KEY = PREFIX + "bot-token";
    private static final String CHAT_ID_KEY = PREFIX + "chat-id";
    private static final String ACTIVITY_PREFIX = PREFIX + "activity.";

    private final NasProperties nasProperties;
    private final TelegramNotificationService telegramNotificationService;
    private final LocalPropertiesFile localPropertiesFile;

    @Autowired
    public TelegramSettingsService(
            NasProperties nasProperties,
            TelegramNotificationService telegramNotificationService,
            LocalPropertiesFile localPropertiesFile
    ) {
        this.nasProperties = nasProperties;
        this.telegramNotificationService = telegramNotificationService;
        this.localPropertiesFile = localPropertiesFile;
    }

    TelegramSettingsService(
            NasProperties nasProperties,
            TelegramNotificationService telegramNotificationService,
            Path configFile
    ) {
        this.nasProperties = nasProperties;
        this.telegramNotificationService = telegramNotificationService;
        this.localPropertiesFile = new LocalPropertiesFile(configFile);
    }

    public TelegramSettingsSnapshot currentSettings() {
        NasProperties.Telegram telegram = nasProperties.getNotifications().getTelegram();
        Map<String, List<TelegramActivitySetting>> groups = new LinkedHashMap<>();

        for (ActivityTypeOption option : ActivityTypeCatalog.options()) {
            groups.computeIfAbsent(option.group(), ignored -> new ArrayList<>())
                    .add(new TelegramActivitySetting(
                            option.key(),
                            option.type(),
                            option.label(),
                            telegram.isActivityEnabled(option.type())
                    ));
        }

        List<TelegramActivityGroup> activityGroups = groups.entrySet().stream()
                .map(entry -> new TelegramActivityGroup(entry.getKey(), List.copyOf(entry.getValue())))
                .toList();

        return new TelegramSettingsSnapshot(
                telegram.isEnabled(),
                telegram.getBotToken(),
                telegram.getChatId(),
                activityGroups,
                localPropertiesFile.configFile().toString()
        );
    }

    public TelegramSettingsUpdate updateFrom(MultiValueMap<String, String> parameters) {
        Set<String> allowedKeys = allowedActivityKeys();
        Map<String, Boolean> activity = new LinkedHashMap<>();
        List<String> submittedKeys = parameters.getOrDefault("activityKeys", List.of());

        for (String key : submittedKeys) {
            if (allowedKeys.contains(key)) {
                activity.put(key, parameters.containsKey("activity." + key));
            }
        }

        for (ActivityTypeOption option : ActivityTypeCatalog.options()) {
            activity.putIfAbsent(option.key(), false);
        }

        return new TelegramSettingsUpdate(
                parameters.containsKey("enabled"),
                cleanValue(firstValue(parameters, "botToken")),
                cleanValue(firstValue(parameters, "chatId")),
                activity
        );
    }

    public void save(TelegramSettingsUpdate update) throws IOException {
        validateForSave(update);
        persist(update);
        applyToRuntime(update);
    }

    public boolean sendTest(TelegramSettingsUpdate update) {
        validateForTest(update);
        String message = "EnderVault Telegram test\n"
                + "time: " + Instant.now() + "\n"
                + "message: Settings test";
        return telegramNotificationService.send(update.botToken(), update.chatId(), message);
    }

    public Path configFile() {
        return localPropertiesFile.configFile();
    }

    private void validateForSave(TelegramSettingsUpdate update) {
        if (!update.enabled()) {
            return;
        }
        validateTokenAndChat(update);
    }

    private void validateForTest(TelegramSettingsUpdate update) {
        validateTokenAndChat(update);
    }

    private void validateTokenAndChat(TelegramSettingsUpdate update) {
        if (!StringUtils.hasText(update.botToken())) {
            throw new IllegalArgumentException("Telegram bot token is required.");
        }
        if (!StringUtils.hasText(update.chatId())) {
            throw new IllegalArgumentException("Telegram chat id is required.");
        }
    }

    private void applyToRuntime(TelegramSettingsUpdate update) {
        NasProperties.Telegram telegram = nasProperties.getNotifications().getTelegram();
        telegram.setEnabled(update.enabled());
        telegram.setBotToken(update.botToken());
        telegram.setChatId(update.chatId());

        Map<String, Boolean> mergedActivity = new LinkedHashMap<>(telegram.getActivity());
        mergedActivity.putAll(update.activity());
        telegram.setActivity(mergedActivity);
    }

    private void persist(TelegramSettingsUpdate update) throws IOException {
        Map<String, String> updates = new LinkedHashMap<>();
        updates.put(ENABLED_KEY, Boolean.toString(update.enabled()));
        updates.put(BOT_TOKEN_KEY, update.botToken());
        updates.put(CHAT_ID_KEY, update.chatId());
        update.activity().forEach((key, enabled) ->
                updates.put(ACTIVITY_PREFIX + key, Boolean.toString(Boolean.TRUE.equals(enabled))));

        localPropertiesFile.update(updates, "# Telegram alert settings managed from EnderVault Settings.");
    }

    private Set<String> allowedActivityKeys() {
        Set<String> keys = new LinkedHashSet<>();
        for (ActivityTypeOption option : ActivityTypeCatalog.options()) {
            keys.add(option.key());
        }
        return keys;
    }

    private static String firstValue(MultiValueMap<String, String> parameters, String key) {
        String value = parameters.getFirst(key);
        return value == null ? "" : value;
    }

    private static String cleanValue(String value) {
        return value == null ? "" : value.replace("\r", "").replace("\n", "").trim();
    }

    public record TelegramSettingsSnapshot(
            boolean enabled,
            String botToken,
            String chatId,
            List<TelegramActivityGroup> groups,
            String configPath
    ) {
    }

    public record TelegramActivityGroup(
            String name,
            List<TelegramActivitySetting> activities
    ) {
    }

    public record TelegramActivitySetting(
            String key,
            String type,
            String label,
            boolean enabled
    ) {
    }

    public record TelegramSettingsUpdate(
            boolean enabled,
            String botToken,
            String chatId,
            Map<String, Boolean> activity
    ) {
    }
}
