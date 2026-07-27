package io.github.fourilla.endervault.web.support;

import io.github.fourilla.endervault.filetool.text.TextDraftStatus;

public record TextDraftResponse(
        boolean ok,
        String code,
        TextDraftStatus draft,
        String content,
        FlashNotification notification
) {

    public static TextDraftResponse status(TextDraftStatus status) {
        return new TextDraftResponse(true, "STATUS", status, null, null);
    }

    public static TextDraftResponse autosaved(TextDraftStatus status) {
        return new TextDraftResponse(true, "AUTOSAVED", status, null, null);
    }

    public static TextDraftResponse restored(TextDraftStatus status, String content) {
        return new TextDraftResponse(true, "RESTORED", status, content, null);
    }

    public static TextDraftResponse discarded() {
        return new TextDraftResponse(
                true,
                "DISCARDED",
                TextDraftStatus.missing(),
                null,
                FlashNotification.info("Text draft discarded.")
        );
    }

    public static TextDraftResponse saved() {
        return new TextDraftResponse(
                true,
                "SAVED",
                TextDraftStatus.missing(),
                null,
                FlashNotification.success("Text file saved.")
        );
    }

    public static TextDraftResponse error(String code, String message, TextDraftStatus status) {
        return new TextDraftResponse(false, code, status, null, FlashNotification.error(message));
    }
}
