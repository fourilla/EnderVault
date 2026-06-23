package io.github.fourilla.endervault.web.remote;

import io.github.fourilla.endervault.web.support.FlashNotification;

public record RemoteDownloadActionResponse(
        boolean ok,
        FlashNotification notification,
        RemoteDownloadTaskPayload task
) {

    public static RemoteDownloadActionResponse ok(FlashNotification notification) {
        return new RemoteDownloadActionResponse(true, notification, null);
    }

    public static RemoteDownloadActionResponse ok(FlashNotification notification, RemoteDownloadTaskPayload task) {
        return new RemoteDownloadActionResponse(true, notification, task);
    }
}
