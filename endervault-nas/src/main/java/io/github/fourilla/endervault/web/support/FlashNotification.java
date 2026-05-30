package io.github.fourilla.endervault.web.support;

import java.io.Serializable;

public record FlashNotification(
        String type,
        String message,
        String actionLabel,
        String actionValue
) implements Serializable {

    private static final long serialVersionUID = 1L;

    public static FlashNotification success(String message) {
        return new FlashNotification("success", message, null, null);
    }

    public static FlashNotification info(String message) {
        return new FlashNotification("info", message, null, null);
    }

    public static FlashNotification info(String message, String actionLabel, String actionValue) {
        return new FlashNotification("info", message, actionLabel, actionValue);
    }

    public static FlashNotification warning(String message) {
        return new FlashNotification("warning", message, null, null);
    }

    public static FlashNotification error(String message) {
        return new FlashNotification("error", message, null, null);
    }

    public boolean hasAction() {
        return actionValue != null && !actionValue.isBlank();
    }

    public String iconClass() {
        return switch (type) {
            case "success" -> "fas fa-circle-check";
            case "warning" -> "fas fa-triangle-exclamation";
            case "error" -> "fas fa-circle-exclamation";
            default -> "fas fa-circle-info";
        };
    }
}
