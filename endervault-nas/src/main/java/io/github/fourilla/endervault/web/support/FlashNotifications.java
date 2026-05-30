package io.github.fourilla.endervault.web.support;

import java.util.ArrayList;
import java.util.List;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

public final class FlashNotifications {

    public static final String ATTRIBUTE_NAME = "notifications";

    private FlashNotifications() {
    }

    public static void success(RedirectAttributes redirectAttributes, String message) {
        add(redirectAttributes, FlashNotification.success(message));
    }

    public static void info(RedirectAttributes redirectAttributes, String message) {
        add(redirectAttributes, FlashNotification.info(message));
    }

    public static void info(
            RedirectAttributes redirectAttributes,
            String message,
            String actionLabel,
            String actionValue
    ) {
        add(redirectAttributes, FlashNotification.info(message, actionLabel, actionValue));
    }

    public static void warning(RedirectAttributes redirectAttributes, String message) {
        add(redirectAttributes, FlashNotification.warning(message));
    }

    public static void error(RedirectAttributes redirectAttributes, String message) {
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
