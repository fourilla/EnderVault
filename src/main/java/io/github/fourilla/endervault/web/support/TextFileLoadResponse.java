package io.github.fourilla.endervault.web.support;

public record TextFileLoadResponse(
        boolean ok,
        FlashNotification notification,
        TextFilePayload text
) {
    public static TextFileLoadResponse ok(TextFilePayload text) {
        return new TextFileLoadResponse(true, null, text);
    }
}
