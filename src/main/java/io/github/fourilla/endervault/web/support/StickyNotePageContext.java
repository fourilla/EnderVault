package io.github.fourilla.endervault.web.support;

import io.github.fourilla.endervault.stickynote.StickyNoteContext;

public record StickyNotePageContext(
        boolean available,
        String targetType,
        String targetKey,
        String surface,
        String label
) {
    public static StickyNotePageContext unavailable() {
        return new StickyNotePageContext(false, "", "", "", "");
    }

    public static StickyNotePageContext from(StickyNoteContext context, String label) {
        return new StickyNotePageContext(
                true,
                context.targetType().name(),
                context.targetKey(),
                context.surface().name(),
                label
        );
    }
}
