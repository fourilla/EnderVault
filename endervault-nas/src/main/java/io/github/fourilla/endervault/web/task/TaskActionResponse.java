package io.github.fourilla.endervault.web.task;

import io.github.fourilla.endervault.web.support.FlashNotification;

public record TaskActionResponse(
        boolean ok,
        FlashNotification notification,
        TaskPayload task
) {

    public static TaskActionResponse ok(FlashNotification notification, TaskPayload task) {
        return new TaskActionResponse(true, notification, task);
    }

    public static TaskActionResponse error(String message) {
        return new TaskActionResponse(false, FlashNotification.error(message), null);
    }
}
