package io.github.fourilla.endervault.web.support;

import io.github.fourilla.endervault.filetool.text.TextDraftSaveAsSuggestion;
import io.github.fourilla.endervault.filetool.text.TextDraftStatus;

public record TextDraftResponse(
        boolean ok,
        String code,
        TextDraftStatus draft,
        String content,
        TextDraftSaveAsSuggestion saveAs,
        FlashNotification notification
) {

    public static TextDraftResponse status(TextDraftStatus status) {
        return new TextDraftResponse(true, "STATUS", status, null, null, null);
    }

    public static TextDraftResponse autosaved(TextDraftStatus status) {
        return new TextDraftResponse(true, "AUTOSAVED", status, null, null, null);
    }

    public static TextDraftResponse autosavedDetached(
            TextDraftStatus status,
            TextDraftSaveAsSuggestion saveAs
    ) {
        return new TextDraftResponse(true, "AUTOSAVED_DETACHED", status, null, saveAs, null);
    }

    public static TextDraftResponse restored(TextDraftStatus status, String content) {
        return new TextDraftResponse(true, "RESTORED", status, content, null, null);
    }

    public static TextDraftResponse discarded() {
        return new TextDraftResponse(
                true,
                "DISCARDED",
                TextDraftStatus.missing(),
                null,
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
                null,
                FlashNotification.success("Text file saved.")
        );
    }

    public static TextDraftResponse error(String code, String message, TextDraftStatus status) {
        return error(code, message, status, null);
    }

    public static TextDraftResponse error(
            String code,
            String message,
            TextDraftStatus status,
            TextDraftSaveAsSuggestion saveAs
    ) {
        return new TextDraftResponse(false, code, status, null, saveAs, FlashNotification.error(message));
    }
}
