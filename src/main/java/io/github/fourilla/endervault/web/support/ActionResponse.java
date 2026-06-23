package io.github.fourilla.endervault.web.support;

import java.util.List;

public record ActionResponse(
        boolean ok,
        FlashNotification notification,
        ShareLinkPayload shareLink,
        List<UploadedFilePayload> uploadedFiles,
        String redirectUrl
) {

    public static ActionResponse ok(FlashNotification notification) {
        return new ActionResponse(true, notification, null, List.of(), null);
    }

    public static ActionResponse ok(FlashNotification notification, ShareLinkPayload shareLink) {
        return new ActionResponse(true, notification, shareLink, List.of(), null);
    }

    public static ActionResponse ok(FlashNotification notification, List<UploadedFilePayload> uploadedFiles) {
        return new ActionResponse(true, notification, null, uploadedFiles, null);
    }

    public static ActionResponse redirect(FlashNotification notification, String redirectUrl) {
        return new ActionResponse(true, notification, null, List.of(), redirectUrl);
    }

    public static ActionResponse error(String message) {
        return new ActionResponse(false, FlashNotification.error(message), null, List.of(), null);
    }
}
