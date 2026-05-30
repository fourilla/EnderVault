package io.github.fourilla.endervault.web;

import java.util.ArrayList;
import java.util.List;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

final class FlashNotifications {

    static final String ATTRIBUTE_NAME = "notifications";

    private FlashNotifications() {
    }

    static void success(RedirectAttributes redirectAttributes, String message) {
        add(redirectAttributes, FlashNotification.success(message));
    }

    static void info(RedirectAttributes redirectAttributes, String message) {
        add(redirectAttributes, FlashNotification.info(message));
    }

    static void info(RedirectAttributes redirectAttributes, String message, String actionLabel, String actionValue) {
        add(redirectAttributes, FlashNotification.info(message, actionLabel, actionValue));
    }

    static void warning(RedirectAttributes redirectAttributes, String message) {
        add(redirectAttributes, FlashNotification.warning(message));
    }

    static void error(RedirectAttributes redirectAttributes, String message) {
        add(redirectAttributes, FlashNotification.error(message));
    }

    private static void add(RedirectAttributes redirectAttributes, FlashNotification notification) {
        List<FlashNotification> notifications = existingNotifications(redirectAttributes);
        notifications.add(notification);
        redirectAttributes.addFlashAttribute(ATTRIBUTE_NAME, notifications);
    }

    private static List<FlashNotification> existingNotifications(RedirectAttributes redirectAttributes) {
        Object existing = redirectAttributes.getFlashAttributes().get(ATTRIBUTE_NAME);
        if (!(existing instanceof List<?> values)) {
            return new ArrayList<>();
        }

        List<FlashNotification> notifications = new ArrayList<>();
        for (Object value : values) {
            if (value instanceof FlashNotification notification) {
                notifications.add(notification);
            }
        }
        return notifications;
    }
}
