package io.github.fourilla.endervault.web.support;

public record FileConflictResponse(
        boolean ok,
        FlashNotification notification,
        FileConflictPayload conflict,
        String redirectUrl
) {

    public static FileConflictResponse conflict(FileConflictPayload conflict, String redirectUrl) {
        return new FileConflictResponse(
                false,
                FlashNotification.warning(conflict.message()),
                conflict,
                redirectUrl
        );
    }
}
